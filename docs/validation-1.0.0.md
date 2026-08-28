# Structure DFU Cache 1.0.0 validation

## Focused tests

- Date: 2026-08-28
- Minecraft: 1.21.1
- NeoForge: 21.1.248
- Result: 11 tests, 0 failures, 0 errors, 0 skipped
- Scope: content addressing, runtime identity invalidation, bounded input, stream closure, passthrough behavior, atomic index replacement, worker budgeting, and vanilla `PathPackResources` materialization

## Minimal dedicated-server startup

A dedicated NeoForge development server loaded Structure DFU Cache 1.0.0 and reported:

```text
Structure DFU cache prepared before gameplay: resources=1180, converted=0,
current=1178, cacheHits=2, vanillaFallbacks=0, workers=1, elapsed=2031 ms
```

The server then reached:

```text
Done (3.438s)! For help, type "help"
```

The controlling tool channel exited after startup and did not preserve a graceful-stop transcript. Process inspection confirmed that no server or Gradle process remained. This evidence proves the minimal dedicated-server startup path only; it is not a client, full-pack, gameplay, or profiler claim.
