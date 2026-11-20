#!/usr/bin/env python3
"""Build all 8 supported loader/version targets from profiles.json and audit every JAR."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MAX_JAR_BYTES = 100 * 1024
REQUIRED_ENTRIES = (
    "icon.png",
    "structure_dfu_cache.mixins.json",
    "assets/structure_dfu_cache/lang/en_us.json",
    "assets/structure_dfu_cache/lang/zh_cn.json",
    "assets/structure_dfu_cache/lang/zh_tw.json",
    "assets/structure_dfu_cache/lang/ja_jp.json",
)
FORBIDDEN_PATTERNS = (
    "rinlib",
    "com/terraformersmc/modmenu/",
    "testresourceadapter",
)


def discover_jdk(major: int) -> str:
    for env_key in (
        f"JAVA_{major}_HOME",
        f"JAVA_HOME_{major}_X64",
        f"JAVA_HOME_{major}_ARM64",
    ):
        val = os.environ.get(env_key, "").strip()
        if val and Path(val).is_dir():
            return val
    candidates = [
        Path(f"/opt/homebrew/opt/openjdk@{major}/libexec/openjdk.jdk/Contents/Home"),
        Path(f"/Users/rin/Library/Java/JavaVirtualMachines/temurin-{major}.jdk/Contents/Home"),
    ]
    for pattern in (
        f"/opt/homebrew/Cellar/openjdk@{major}/*/libexec/openjdk.jdk/Contents/Home",
        f"{Path.home()}/.gradle/jdks/*-{major}-*/Contents/Home",
    ):
        for p in sorted(Path("/").glob(pattern.lstrip("/")), reverse=True):
            candidates.append(p)
    for c in candidates:
        if (c / "bin/java").is_file():
            return str(c)
    raise RuntimeError(f"Could not find JDK {major}; set {env_key}")


def verify_jar(jar_path: Path):
    size = jar_path.stat().st_size
    if size >= MAX_JAR_BYTES:
        raise RuntimeError(f"JAR exceeds 100KB budget: {jar_path.name} ({size} bytes)")
    with zipfile.ZipFile(jar_path) as zf:
        names = set(zf.namelist())
        for req in REQUIRED_ENTRIES:
            if req not in names:
                raise RuntimeError(f"Missing required entry '{req}' in {jar_path.name}")
        for name in names:
            lower = name.lower()
            for forbidden in FORBIDDEN_PATTERNS:
                if forbidden in lower:
                    raise RuntimeError(f"Forbidden entry found in {jar_path.name}: {name}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--profile", help="Optional single MC version to build")
    args = parser.parse_args()

    profiles = json.loads((ROOT / "profiles.json").read_text())
    version = next(
        line.split("=", 1)[1].strip()
        for line in (ROOT / "gradle.properties").read_text().splitlines()
        if line.startswith("mod_version=")
    )
    output = ROOT / "build/releases"
    if not args.profile and output.exists():
        for stale_jar in output.glob("*.jar"):
            stale_jar.unlink()
    output.mkdir(parents=True, exist_ok=True)
    lock = (ROOT / "build/matrix.lock").open("w")
    fcntl.flock(lock, fcntl.LOCK_EX)

    all_jdks = {major: discover_jdk(major) for major in sorted({p["java"] for p in profiles.values()})}
    installations_paths = ",".join(all_jdks.values())

    manifest = []
    for minecraft, profile in profiles.items():
        if args.profile and minecraft != args.profile:
            continue
        loaders = profile["loaders"]
        env = os.environ.copy()
        daemon_java = all_jdks.get(21) if profile["java"] < 21 else all_jdks[profile["java"]]
        env["JAVA_HOME"] = daemon_java

        tasks = []
        for loader in loaders:
            tasks.append(f":{loader}:clean")
        for loader in loaders:
            tasks.append(f":{loader}:build")

        command = [
            str(ROOT / "gradlew"),
            *tasks,
            "-Pminecraft_version=" + minecraft,
            "-Ploader=all",
            "-Porg.gradle.java.installations.paths=" + installations_paths,
            "--no-configuration-cache",
            "--max-workers=2",
        ]
        subprocess.run(command, cwd=ROOT, env=env, check=True)

        for loader in loaders:
            source = (
                ROOT
                / f"build/profiles/{minecraft}/{loader}/libs/structure_dfu_cache-{loader}-{version}+{minecraft}.jar"
            )
            target = output / f"structure-dfu-cache-{version}+{minecraft}-{loader}.jar"
            shutil.copy2(source, target)
            verify_jar(target)
            binary = target.read_bytes()
            manifest.append(
                dict(
                    minecraft=minecraft,
                    loader=loader,
                    file=str(target),
                    bytes=len(binary),
                    sha256=hashlib.sha256(binary).hexdigest(),
                    sha512=hashlib.sha512(binary).hexdigest(),
                )
            )
            print(f"BUILT & VERIFIED {minecraft} {loader}: {target.name} ({len(binary):,} bytes)", flush=True)

    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    if not args.profile:
        audit_env = os.environ.copy()
        audit_env["JAVA_HOME"] = all_jdks[profiles["1.21.1"]["java"]]
        subprocess.run(
            [
                str(ROOT / "gradlew"),
                ":neoforge:test",
                "--tests",
                "dev.rinchan.structuredfucache.ArchitectureContractTest",
                "--rerun-tasks",
                "-Pminecraft_version=1.21.1",
                "-Ploader=neoforge",
                "-PrequireAllReleases=true",
                "-Porg.gradle.java.installations.paths=" + installations_paths,
                "--no-configuration-cache",
                "--max-workers=2",
            ],
            cwd=ROOT,
            env=audit_env,
            check=True,
        )
    print(f"ALL DONE ({len(manifest)} targets).", flush=True)


if __name__ == "__main__":
    main()
