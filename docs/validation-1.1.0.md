# Validation: Structure DFU Cache 1.1.0 / RinLib 1.2.0

Date: 2026-10-05. Every row used the packaged production JARs on a local dedicated
server, not a development classpath. Both jars are required; Fabric API is not a
dependency. The same `src/main/java` implements the cache for all eight profiles.

| Minecraft | Loader | Effective structures | Reload cache hits | Restart cache hits | Result |
| --- | --- | ---: | ---: | ---: | --- |
| 1.21.1 | neoforge | 1181 | 1181/1181 | 1181/1181 | PASS |
| 1.21.1 | fabric | 1181 | 1181/1181 | 1181/1181 | PASS |
| 26.2 | neoforge | 1213 | 1213/1213 | 1213/1213 | PASS |
| 26.2 | fabric | 1213 | 1213/1213 | 1213/1213 | PASS |
| 26.1.2 | neoforge | 1203 | 1203/1203 | 1203/1203 | PASS |
| 26.1.2 | fabric | 1203 | 1203/1203 | 1203/1203 | PASS |
| 1.20.1 | forge | 1011 | 1011/1011 | 1011/1011 | PASS |
| 1.20.1 | fabric | 1011 | 1011/1011 | 1011/1011 | PASS |

Each profile passed:

- 30 cache tests and 3 RinLib structure adapter tests.
- Preparation before server readiness; old `grass_path` upgrades to `dirt_path`.
- Placement of block data, a chest retaining its loot table, and an armor stand.
- A resource reload that reuses every prepared structure without another DFU conversion.
- Highest-priority data-pack replacement, followed by restoration of the original template.
- Clean shutdown with no remaining generated resource layers.
- Restart with complete persistent cache hits and successful template placement.
- Loader rejection of missing RinLib before world startup.

Additional real-server checks on 1.20.1 Forge and 26.2 NeoForge removed the index
and damaged a cached converted blob. Both rebuilt it from the resolved original
resource and placed the upgraded structure normally.

RinLib's existing binary API was compared with published API fixtures on all eight
profiles; every existing class signature was preserved. Bytecode targets, archive
metadata, licenses and exclusion of test probes were also checked. The existing
portable/Fabric/NeoForge JVM suites passed 15/15/18 tests; Python tooling passed
12 tests.

The fixture represents Minecraft 1.16-era structure NBT. Counts include vanilla
structures and this fixture. These checks verify the preparation and reuse path;
they do not establish a numerical TPS gain for a particular modpack.

## Evidence and reproducibility

`output/release/manifest.json` records the shared core source fingerprint, exact
artifact hashes, test totals and runtime checks. `output/release/validation/`
contains server logs and result JSON. The workspace's `work/build-matrix.py`,
`runtime-check.py`, `missing-dependency-check.py` and `corrupt-cache-check.py`
contain the build commands and fixtures used for this run.
