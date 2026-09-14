# Whole-project review and validation — 2026-09-14

Base: `24c434d` (1.0.1 source), local branch `review/cache-integrity`. This is an unreleased source revision; no platform publication or WMF deployment was performed.

## Resulting design

Preserve the owner's chosen tradeoff: prepare structures during startup/reload to reduce gameplay TPS stalls, accept slower initial preparation, and keep the complete single-pack structure layer.

- Cache preparation, warm validation and deduplication share one immutable blob result per content key. Source and converted files without a trusted index are rewritten.
- Blob digests are computed during writes; warm digests are checked once per shared preparation. Successful conversion does not create an unused source copy.
- `CacheFiles` owns completed-file replacement for both index and blobs, using unique temporary files and preserving hard links held by older resource layers. `CacheIndex` preserves deterministic entry order.
- Executors are scoped to preparation and fully terminated before return, including interruption. Fatal worker errors propagate. Cache writes are not treated as conversion failures.
- A nonblocking file lock protects the production preparation/commit/prune/materialization window. A competing process receives vanilla resources rather than racing the cache writer.
- The Mixin uses one return injection for construction and reload; vanilla already installs the resource manager and clears cached templates. Previous-generation retirement is retained.
- Index paths must match the expected content-addressed path; an index cannot redirect reads or deletion elsewhere. Heap-worker arithmetic is bounded before narrowing to an integer.
- Logs name passthrough resources accurately. Documentation, developer console input and the issue-tracker metadata placement are corrected.

Production Java decreased from **1022 to 836 lines** including the shared file writer. Small records remain where they define actual cache/index schemas; the review did not replace them with opaque arrays or collapse all responsibilities into one class.

## Automated validation

- The two original corruption regressions failed on the pre-repair implementation, then passed after repair.
- Final clean source validation: `./gradlew clean test build --offline --console=plain`.
- After correcting metadata placement: `./gradlew build --offline --console=plain`.
- **30 tests, zero failures, errors or skips.** Coverage includes cold/warm/changed resources, missing or corrupt indexes, modified blobs, duplicate conversions, current/future and malformed passthrough, failed DFU, write failure, bounded reads, stream closure, fatal errors, cancellation with a slow-to-unwind worker, atomic replacement with live hard links, deterministic indexes, lock contention, data-pack priorities/filters, and old-generation reads after replacement.
- JAR inspection checks archive integrity, required metadata/Mixin/icon/license/classes and exclusion of test/source files. Artifact details and SHA-256 are in [build-result.json](validation-2026-09-14/build-result.json).

## Local NeoForge runtime evidence

Environment: macOS arm64, Homebrew OpenJDK 21.0.12.1, Minecraft 1.21.1, NeoForge 21.1.248. Disposable development world bound to `127.0.0.1` on an ephemeral port, with vanilla resources plus one old-version structure fixture.

1. Cold startup prepared 1,181 resources, including one converted fixture, with no capture fallback. Preparation took 5,872 ms in this run.
2. A restarted server reused all 1,181 resources (165 ms preparation). The template placed stone and the command-side block check emitted `DFU_SMOKE_WARM_OK`.
3. Replacing that resource with a gold-block fixture and reloading converted one resource and reused 1,180 (431 ms). Placement and block verification emitted `DFU_SMOKE_RELOAD_OK`.
4. An unchanged reload reused all 1,181 resources (85 ms). Filesystem inspection confirmed the superseded generation was retired.
5. Holding the cache lock in a separate process forced the next reload to retain vanilla loading. Placement still succeeded and emitted `DFU_SMOKE_FALLBACK_OK`.
6. Releasing the lock and reloading restored the generated layer with 1,181 hits (115 ms). Placement emitted `DFU_SMOKE_RECOVERED_OK`.
7. A post-preparation `jcmd Thread.print` snapshot contained zero cache workers. The final `stop` saved all dimensions and the Gradle server task exited successfully. JVM enumeration confirmed no test server remained.

[Cold startup](validation-2026-09-14/cold-start.txt), [reload/placement/shutdown log excerpt](validation-2026-09-14/server-smoke.txt), and [thread observation](validation-2026-09-14/thread-check.txt) retain the observed evidence. [Reproduction steps](../runtime-tests/README.md) replace the previous unexecuted JSON checklist.

These are single-run preparation timings and behavior checks, not a baseline comparison or TPS benchmark. The runtime exercise used the reviewed preparation/lifecycle implementation; subsequent equivalent digest-validator consolidation and metadata placement were covered by the final automated build. The owner's reported long-term WMF validation refers to the previous implementation. This revision has not been accepted in WMF or verified on Windows or in a client session.
