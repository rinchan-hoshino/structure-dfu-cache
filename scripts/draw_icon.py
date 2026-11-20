#!/usr/bin/env python3
"""Generate the 64x64 pixel-art Structure DFU Cache 2.0 icon (nearest-neighbor upscaled to 512x512).

Design:
- Deep rounded chiseled stone-slate badge frame
- Upper half: miniature 3D stone-brick ruin archway with subtle moss & archway shadow
- Lower half: three stacked glowing lapis/cyan high-speed cache crystal plates
- Accent: bright golden-yellow speed lightning bolt energizing the cache stack
"""
from pathlib import Path
import struct
import zlib

ROOT = Path(__file__).resolve().parents[1]
SIZE = 64
SCALE = 8


def hex_rgba(h: str, a: int = 255):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


def blend(dst, src):
    sr, sg, sb, sa = src
    if sa == 0:
        return dst
    if sa == 255:
        return src
    dr, dg, db, da = dst
    inv = 255 - sa
    out_a = sa + (da * inv) // 255
    if out_a == 0:
        return (0, 0, 0, 0)
    r = (sr * sa + dr * da * inv // 255) // out_a
    g = (sg * sa + dg * da * inv // 255) // out_a
    b = (sb * sa + db * da * inv // 255) // out_a
    return (r, g, b, out_a)


def write_png(path: Path, width: int, height: int, rgba_bytes: bytes):
    def chunk(tag: bytes, data: bytes) -> bytes:
        return (
            struct.pack("!I", len(data))
            + tag
            + data
            + struct.pack("!I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        )

    raw = bytearray()
    stride = width * 4
    for y in range(height):
        raw.append(0)
        raw.extend(rgba_bytes[y * stride : (y + 1) * stride])

    png = bytearray(b"\x89PNG\r\n\x1a\n")
    png.extend(chunk(b"IHDR", struct.pack("!IIBBBBB", width, height, 8, 6, 0, 0, 0)))
    png.extend(chunk(b"IDAT", zlib.compress(bytes(raw), 9)))
    png.extend(chunk(b"IEND", b""))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(bytes(png))


def inside_rounded_rect(x: int, y: int, x0: int, y0: int, x1: int, y1: int, r: int) -> bool:
    if x < x0 or x > x1 or y < y0 or y > y1:
        return False
    cx = x0 + r if x < x0 + r else (x1 - r if x > x1 - r else x)
    cy = y0 + r if y < y0 + r else (y1 - r if y > y1 - r else y)
    dx = x - cx
    dy = y - cy
    return dx * dx + dy * dy <= r * r


def draw_icon():
    canvas = [[(0, 0, 0, 0) for _ in range(SIZE)] for _ in range(SIZE)]

    def put(x: int, y: int, color):
        if 0 <= x < SIZE and 0 <= y < SIZE:
            canvas[y][x] = blend(canvas[y][x], color)

    def fill_rect(x0: int, y0: int, x1: int, y1: int, color):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                put(x, y, color)

    # 1. Deep rounded stone-slate badge frame
    outer_border = hex_rgba("#141B26")
    stone_hi = hex_rgba("#6E829B")
    stone_mid = hex_rgba("#4A5A70")
    stone_lo = hex_rgba("#2B3647")
    inner_edge = hex_rgba("#1B2534")
    bg_top = hex_rgba("#141D2D")
    bg_bot = hex_rgba("#0E1726")

    for y in range(SIZE):
        for x in range(SIZE):
            if not inside_rounded_rect(x, y, 2, 2, 61, 61, 11):
                continue
            if not inside_rounded_rect(x, y, 3, 3, 60, 60, 10):
                put(x, y, outer_border)
            elif not inside_rounded_rect(x, y, 6, 6, 57, 57, 8):
                # Chiseled stone bevel rim with subtle masonry ticks
                if y < 18 or x < 14:
                    c = stone_hi if ((x + y) % 7 != 0) else stone_mid
                elif y > 48 or x > 50:
                    c = stone_lo
                else:
                    c = stone_mid if ((x * 3 + y) % 9 != 0) else stone_lo
                put(x, y, c)
            elif not inside_rounded_rect(x, y, 7, 7, 56, 56, 7):
                put(x, y, inner_edge)
            else:
                t = (y - 7) / 49.0
                r = int(bg_top[0] * (1 - t) + bg_bot[0] * t)
                g = int(bg_top[1] * (1 - t) + bg_bot[1] * t)
                b = int(bg_top[2] * (1 - t) + bg_bot[2] * t)
                # Lapis/cyan radial glow centered behind the crystal cache plates
                dx = x - 31
                dy = y - 41
                dist2 = dx * dx + dy * dy
                if dist2 < 460:
                    glow = max(0.0, 1.0 - dist2 / 460.0)
                    r = min(255, int(r + 14 * glow))
                    g = min(255, int(g + 68 * glow))
                    b = min(255, int(b + 115 * glow))
                put(x, y, (r, g, b, 255))

    # Corner stone rivets on badge bezel
    for rx, ry in [(10, 10), (53, 10), (10, 53), (53, 53)]:
        put(rx, ry, hex_rgba("#222C3B"))
        put(rx, ry - 1, hex_rgba("#8095B0", 180))

    # 2. Upper half: Miniature 3D stone-brick ruin archway (y = 10..33)
    arch_outline = hex_rgba("#111722")
    brick_hi = hex_rgba("#B2BECB")
    brick_mid = hex_rgba("#8593A3")
    brick_shade = hex_rgba("#5C6978")
    brick_dark = hex_rgba("#3D4754")
    brick_mortar = hex_rgba("#2A323D")
    moss_hi = hex_rgba("#63A864")
    moss_lo = hex_rgba("#3F7546")

    # Dark atmospheric archway interior opening
    fill_rect(22, 16, 41, 32, hex_rgba("#0B111C"))
    # Subtle upward lapis mist inside archway portal
    for y in range(24, 33):
        alpha = int(18 + (y - 24) * 14)
        for x in range(23, 41):
            put(x, y, hex_rgba("#29A8E0", alpha))

    # Archway silhouette outline
    fill_rect(14, 15, 49, 19, arch_outline)
    fill_rect(17, 11, 46, 16, arch_outline)
    fill_rect(21, 9, 42, 12, arch_outline)
    # Left & right pillar outlines
    fill_rect(15, 19, 23, 33, arch_outline)
    fill_rect(40, 19, 48, 33, arch_outline)

    # Top lintel / stepped arch stonework
    fill_rect(22, 10, 41, 12, brick_hi)
    fill_rect(18, 12, 45, 15, brick_mid)
    fill_rect(15, 16, 48, 18, brick_shade)
    # 3D top bevel highlight
    fill_rect(22, 10, 41, 10, hex_rgba("#CBD6E2"))
    fill_rect(18, 12, 21, 12, brick_hi)
    fill_rect(42, 12, 45, 12, brick_hi)
    # Keystone center block
    fill_rect(29, 10, 34, 16, brick_hi)
    fill_rect(30, 11, 33, 15, hex_rgba("#C2CEDB"))
    put(29, 16, brick_shade)
    put(34, 16, brick_shade)
    # Lintel mortar joints
    for mx in (24, 28, 35, 39):
        for my in range(12, 16):
            put(mx, my, brick_mortar)
    for mx in (20, 26, 32, 38, 44):
        for my in range(16, 19):
            put(mx, my, brick_mortar)

    # Cut arched inner ceiling corners
    fill_rect(24, 17, 39, 19, hex_rgba("#0B111C"))
    fill_rect(26, 16, 37, 17, hex_rgba("#0B111C"))
    # Inner arch bevel shadow
    fill_rect(24, 16, 25, 17, brick_dark)
    fill_rect(38, 16, 39, 17, brick_dark)
    fill_rect(23, 18, 23, 32, brick_dark)
    fill_rect(40, 18, 40, 32, brick_dark)

    # Left pillar (3D front + shaded right return)
    fill_rect(16, 19, 21, 32, brick_mid)
    fill_rect(16, 19, 17, 32, brick_hi)
    fill_rect(21, 19, 22, 32, brick_shade)
    # Right pillar (3D front + shaded right edge)
    fill_rect(42, 19, 46, 32, brick_mid)
    fill_rect(41, 19, 42, 32, brick_hi)
    fill_rect(46, 19, 47, 32, brick_shade)

    # Pillar horizontal brick courses
    for by in (22, 26, 30):
        fill_rect(16, by, 22, by, brick_mortar)
        fill_rect(41, by, 47, by, brick_mortar)
    # Pillar bases
    fill_rect(14, 31, 23, 32, brick_shade)
    fill_rect(15, 31, 21, 31, brick_hi)
    fill_rect(40, 31, 49, 32, brick_shade)
    fill_rect(41, 31, 47, 31, brick_hi)

    # Subtle ruin moss pixels on arch top-left & pillar
    for mx, my, mc in [
        (18, 12, moss_hi), (19, 12, moss_hi), (19, 13, moss_lo), (20, 13, moss_lo),
        (16, 23, moss_hi), (17, 23, moss_lo), (16, 24, moss_lo),
        (44, 13, moss_hi), (45, 13, moss_lo), (45, 14, moss_lo),
    ]:
        put(mx, my, mc)

    # 3. Lower half: Three stacked glowing lapis/cyan cache crystal plates (y = 35..54)
    plate_outline = hex_rgba("#0A1322")
    lapis_top = hex_rgba("#7CF2FF")
    lapis_hi = hex_rgba("#38BDF8")
    lapis_mid = hex_rgba("#1D6FD8")
    lapis_deep = hex_rgba("#124494")
    lapis_side = hex_rgba("#0E3270")
    glow_core = hex_rgba("#C8FCFF")

    def draw_crystal_plate(top_y: int, half_w: int, depth: int = 4):
        cx = 31
        # Soft cyan underglow
        for x in range(cx - half_w - 1, cx + half_w + 2):
            put(x, top_y + depth + 1, hex_rgba("#22C7F2", 70))
        # Outline + beveled trapezoid/slab silhouette
        for dy in range(depth + 2):
            inset = 2 if dy == 0 else (1 if dy == 1 else 0)
            x0 = cx - half_w + inset
            x1 = cx + half_w - inset
            for x in range(x0 - 1, x1 + 2):
                if dy == 0 or dy == depth + 1 or x == x0 - 1 or x == x1 + 1:
                    put(x, top_y + dy, plate_outline)
                elif dy == 1:
                    # Luminous cyan top facet
                    if abs(x - cx) < half_w - 5:
                        put(x, top_y + dy, lapis_top)
                    else:
                        put(x, top_y + dy, lapis_hi)
                elif dy == 2:
                    # Upper crystal body with bright data segments
                    if (x - cx) % 6 in (0, 1) and abs(x - cx) < half_w - 4:
                        put(x, top_y + dy, glow_core)
                    elif x < cx:
                        put(x, top_y + dy, lapis_hi)
                    else:
                        put(x, top_y + dy, lapis_mid)
                elif dy == 3:
                    # Mid lapis body
                    put(x, top_y + dy, lapis_mid if x < cx + 4 else lapis_deep)
                else:
                    # Shaded bottom bevel
                    put(x, top_y + dy, lapis_side)

    # Bottom, middle, and top crystal cache layers (widest at bottom for depth)
    draw_crystal_plate(48, 19, 4)
    draw_crystal_plate(41, 17, 4)
    draw_crystal_plate(34, 15, 4)

    # 4. Crisp bright golden-yellow speed lightning bolt emblem (x = 34..52, y = 19..46)
    bolt_pixels = [
        # (y, x0, x1)
        (20, 40, 44),
        (21, 39, 44),
        (22, 38, 43),
        (23, 37, 42),
        (24, 36, 41),
        (25, 35, 40),
        (26, 34, 40),
        (27, 33, 46),
        (28, 33, 46),
        (29, 34, 45),
        (30, 39, 44),
        (31, 38, 43),
        (32, 37, 42),
        (33, 36, 41),
        (34, 35, 40),
        (35, 34, 39),
        (36, 34, 38),
        (37, 33, 37),
        (38, 33, 36),
        (39, 32, 35),
        (40, 32, 34),
        (41, 32, 33),
    ]
    bolt_set = set()
    for by, bx0, bx1 in bolt_pixels:
        for bx in range(bx0, bx1 + 1):
            bolt_set.add((bx, by))

    # Draw dark contrast outline around lightning bolt
    bolt_outline = hex_rgba("#23180A")
    for bx, by in bolt_set:
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                if (bx + dx, by + dy) not in bolt_set:
                    put(bx + dx, by + dy, bolt_outline)

    # Fill lightning bolt with vibrant highlight -> gold -> amber gradient
    bolt_hi = hex_rgba("#FFF9C4")
    bolt_mid = hex_rgba("#FFD93B")
    bolt_lo = hex_rgba("#F59E0B")
    for by, bx0, bx1 in bolt_pixels:
        for bx in range(bx0, bx1 + 1):
            if bx == bx0 or by in (20, 27):
                put(bx, by, bolt_hi)
            elif bx >= bx1 - 1 or by >= 36:
                put(bx, by, bolt_lo)
            else:
                put(bx, by, bolt_mid)

    # Tiny energy sparks near lightning & crystal stack
    for sx, sy, sc in [
        (48, 24, hex_rgba("#FFE566")),
        (50, 29, hex_rgba("#7CF2FF")),
        (14, 39, hex_rgba("#7CF2FF")),
        (12, 44, hex_rgba("#38BDF8", 210)),
        (51, 44, hex_rgba("#7CF2FF", 210)),
    ]:
        put(sx, sy, sc)

    # Nearest-neighbor upscale 8x -> 512x512
    out_w = SIZE * SCALE
    out_h = SIZE * SCALE
    rgba = bytearray(out_w * out_h * 4)
    for y in range(SIZE):
        for x in range(SIZE):
            r, g, b, a = canvas[y][x]
            for sy in range(SCALE):
                row_offset = ((y * SCALE + sy) * out_w + x * SCALE) * 4
                for sx in range(SCALE):
                    idx = row_offset + sx * 4
                    rgba[idx] = r
                    rgba[idx + 1] = g
                    rgba[idx + 2] = b
                    rgba[idx + 3] = a

    icon_bytes = bytes(rgba)
    write_png(ROOT / "common/src/main/resources/icon.png", out_w, out_h, icon_bytes)
    write_png(ROOT / "docs/icon.png", out_w, out_h, icon_bytes)
    print(f"Generated {out_w}x{out_h} icon.png in common/src/main/resources/icon.png and docs/icon.png")


if __name__ == "__main__":
    draw_icon()
