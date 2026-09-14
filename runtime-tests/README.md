# Local server smoke test

Run only in a disposable development world. Java 21 and Python 3 are required. The fixture generator uses only the Python standard library and creates one old-version, one-block structure.

1. Generate the initial data pack:

   ```sh
   python3 runtime-tests/structure_fixture.py run/world/datapacks/dfu-smoke
   ```

2. Configure `run/server.properties` with `server-ip=127.0.0.1`, `server-port=0`, and a small view distance. Review the Minecraft EULA and set up `run/eula.txt` before starting. Run `./gradlew runServer --console=plain`. The development task forwards console input. Wait for `Done` and the cache preparation log.
3. In the server console:

   ```text
   forceload add 0 0
   place template dfu_smoke:probe 0 80 0
   execute if block 0 80 0 minecraft:stone run say DFU_SMOKE_WARM_OK
   ```

4. In a separate shell, change the same resource:

   ```sh
   python3 runtime-tests/structure_fixture.py run/world/datapacks/dfu-smoke --block minecraft:gold_block
   ```

   Run `reload` in the server console and wait for the next cache preparation log. Place the same template again and check for `minecraft:gold_block`. The modified template must convert; unchanged resources should hit the cache. Reload again without modifying the resource to test warm preparation and generation retirement.
5. To exercise unavailable-cache fallback on POSIX, hold an exclusive `fcntl.lockf` lock on `run/cache/structure_dfu_cache/prepare.lock` in another process. Reload while the lock is held, confirm the vanilla-fallback log and correct template placement, then release the lock. Reload again and confirm the generated layer recovers.
6. After a completed preparation, inspect the server JVM with `jcmd <pid> Thread.print`: no `structure-dfu-cache-build-` threads should remain. Issue `stop`, confirm all dimensions are saved, and verify the process exits.

The automated tests separately inject unreadable inputs and interrupted workers, check current/future and malformed passthrough, and verify pack priority/filter preservation. This smoke test does not establish multiplayer TPS gains, Windows behavior, or WMF deployment acceptance.

Evidence from the 2026-09-14 run is linked in [the validation report](../docs/validation-cache-integrity-2026-09-14.md).
