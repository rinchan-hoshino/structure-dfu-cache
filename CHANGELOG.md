# Changelog

## Unreleased

- Rebuild unverified cache files when an index is missing, corrupt, or points to an unexpected path.
- Share conversion and warm-cache validation per content key. Compute blob digests while writing instead of rereading files; skip unused source copies after successful conversion.
- Use one file-commit implementation for blobs and indexes, preserving active generations' hard links. Keep index serialization deterministic.
- Scope workers to preparation and wait for them to terminate on interruption. Use a nonblocking cache-directory lock to isolate competing preparations.
- Simplify startup and reload integration to one post-vanilla Mixin hook. Keep the complete single-pack resource layer and vanilla fallback.
- Report passthrough resources accurately in logs, correct compatibility documentation and issue-tracker metadata, and enable the development server console.
- Expand regression tests and validate cold/warm startup, changed-resource reload, cache-unavailable fallback, recovery, and clean shutdown on a local NeoForge server.

## 1.0.1 — 2026-08-29

- Verify every prepared cache blob against its persisted SHA-256 before reuse.
- Rebuild tampered or truncated blobs instead of exposing them through the generated resource layer.
- Advance the cache format so pre-integrity indexes are ignored and rebuilt automatically.
- Add bounded failure coverage for changed runtime identity, corrupt indexes, and unusable cache roots.

## 1.0.0 — 2026-08-28

- Build a persistent, content-addressed cache of structure NBT upgraded by Minecraft's official structure DataFixer.
- Preserve vanilla resource-pack resolution and fall back to the original resource manager if a complete prepared layer cannot be built.
- Limit cache preparation to startup and explicit resource reload maintenance windows.
