# Changelog

## 2.0.0

- Replace `blobs/`, `sources/`, `generations/<uuid>`, and `index.json` with a single `overlay/data/<ns>/<path>` layer and streaming `manifest.v2` line format.
- Add sub-5ms warm-boot fast-path mounting by checking only line 1 of `manifest.v2` against `PackFingerprint` (zero structure file reads or NBT decoding).
- Only materialize structures with `DataVersion < targetDataVersion`; record current-version, non-template, corrupt, and oversized (`>32 MiB` decoded) NBT as `P` with zero disk writes.
- Force single-threaded streaming conversion with immediate buffer release on `<=6.8G` heaps, drop per-file `fsync`, and auto-clean legacy `1.x` cache directories.

## 1.0.1

- Verify every prepared cache blob against its persisted SHA-256 before reuse.
- Rebuild tampered or truncated blobs instead of exposing them through the generated resource layer.
- Advance the cache format so pre-integrity indexes are ignored and rebuilt automatically.
- Add bounded failure coverage for changed runtime identity, corrupt indexes, and unusable cache roots.

## 1.0.0

- Build a persistent, content-addressed cache of structure NBT upgraded by Minecraft's official structure DataFixer.
- Preserve vanilla resource-pack resolution and fall back to the original resource manager if a complete prepared layer cannot be built.
- Limit cache preparation to startup and explicit resource reload maintenance windows.
