# Changelog

## 1.0.1 — 2026-08-29

- Verify every prepared cache blob against its persisted SHA-256 before reuse.
- Rebuild tampered or truncated blobs instead of exposing them through the generated resource layer.
- Advance the cache format so pre-integrity indexes are ignored and rebuilt automatically.
- Add bounded failure coverage for changed runtime identity, corrupt indexes, and unusable cache roots.

## 1.0.0 — 2026-08-28

- Build a persistent, content-addressed cache of structure NBT upgraded by Minecraft's official structure DataFixer.
- Preserve vanilla resource-pack resolution and fall back to the original resource manager if a complete prepared layer cannot be built.
- Limit cache preparation to startup and explicit resource reload maintenance windows.
