"""Write a one-block old-version structure for the local server smoke test."""
import argparse
import gzip
import json
import struct
from pathlib import Path


def string(value):
    encoded = value.encode("utf-8")
    return struct.pack(">H", len(encoded)) + encoded


def tag(kind, name, payload):
    return bytes([kind]) + string(name) + payload


def integer(name, value):
    return tag(3, name, struct.pack(">i", value))


def compound(*tags):
    return b"".join(tags) + b"\0"


def ints(name, values):
    return tag(9, name, b"\3" + struct.pack(">i", len(values)) + b"".join(struct.pack(">i", x) for x in values))


def write_fixture(pack, block):
    palette = compound(tag(8, "Name", string(block)))
    placed = compound(integer("state", 0), ints("pos", [0, 0, 0]))
    data = tag(10, "", compound(
        integer("DataVersion", 1000), ints("size", [1, 1, 1]),
        tag(9, "palette", b"\x0a" + struct.pack(">i", 1) + palette),
        tag(9, "blocks", b"\x0a" + struct.pack(">i", 1) + placed),
        tag(9, "entities", b"\x0a" + struct.pack(">i", 0)),
    ))
    structure = pack / "data/dfu_smoke/structure/probe.nbt"
    structure.parent.mkdir(parents=True, exist_ok=True)
    structure.write_bytes(gzip.compress(data, mtime=0))
    (pack / "pack.mcmeta").write_text(json.dumps({"pack": {"pack_format": 48, "description": "Structure cache smoke test"}}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pack", type=Path)
    parser.add_argument("--block", default="minecraft:stone")
    args = parser.parse_args()
    write_fixture(args.pack, args.block)
