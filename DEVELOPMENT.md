# Development

## Build All 8 Targets

Requires JDK 17, JDK 21, and JDK 25 (auto-discovered via Homebrew / Temurin / Gradle JDKs or `JAVA_17_HOME`, `JAVA_21_HOME`, `JAVA_25_HOME`):

```sh
python3 scripts/build-matrix.py
```

Build a single Minecraft profile:

```sh
python3 scripts/build-matrix.py --profile 1.21.1
```

Outputs are written and audited in `build/releases/` along with `build/releases/manifest.json`.

## Regenerating the Pixel-Art Icon

```sh
python3 scripts/draw_icon.py
```

Writes `512x512` (nearest-neighbor upscaled from `64x64`) PNGs to `common/src/main/resources/icon.png` and `docs/icon.png`.
