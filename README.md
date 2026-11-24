# Structure DFU Cache

<p align="center">
  <img src="docs/icon.png" alt="Structure DFU Cache" width="128" height="128" />
</p>

**Speeds up world loading and `/reload` in modpacks by caching upgraded structure templates on disk.**

Many structure and dungeon mods ship `.nbt` structure files saved on older Minecraft versions, forcing vanilla DataFixerUpper (DFU) to re-upgrade the exact same templates every time you open a world or run `/reload`. **Structure DFU Cache** upgrades outdated structure templates once into a local `cache/structure_dfu_cache/` folder and serves the upgraded files directly on future launches.

![Structure DFU Cache Dashboard](docs/screenshots/01-cache-dashboard.png)

## Why Use It

- **Upgrade once, skip repeat work**: Automatically detects modpack or datapack changes; when nothing changed, warm world loads validate the cache in a few milliseconds instead of re-converting thousands of structure files.
- **Only touches outdated templates**: Structure files that already match the current Minecraft version (or exceed safe size limits) are left untouched without duplicating disk space.
- **RAM-friendly on heavy packs**: Automatically throttles background worker threads when your client or server runs with tight heap headroom (`-Xmx <= 6.8G`) so initial cache builds don't spike memory.
- **Built-in status screen & OP commands**: Check prepare time, upgraded structure counts, and cache disk usage — or trigger a clean rebuild — from the Mod Menu / Forge / NeoForge config screen or via `/structuredfucache status` and `/structuredfucache clear` (OPs only).
- **Safe to add or remove anytime**: Works alongside **Lithium**, **ModernFix**, **FerriteCore**, **C2ME**, and **LazyDFU**, and never modifies your world saves.

![Operator Status Command](docs/screenshots/02-status-command.png)

## Configuration

Edit in-game or in `config/structure_dfu_cache.toml`:

```toml
# Master switch. Set false to bypass the cache and load raw mod/datapack structures directly.
enabled = true

# Max background threads during initial/incremental cache builds (0 = automatic 1..4 based on free heap).
maxWorkers = 0

# Log detailed cache build diagnostics at INFO level.
verboseLogging = false
```

## Supported Versions

Standalone JAR (`< 100 KB`) with no required library mods.

| Minecraft | Fabric | Forge | NeoForge | Java |
| --- | --- | --- | --- | --- |
| `1.20.1` | ✅ | ✅ (`47.4+`) | — | 17 |
| `1.21.1` | ✅ | — | ✅ (`21.1+`) | 21 |
| `26.1.2` | ✅ | — | ✅ (`26.1.2+`) | 25 |
| `26.2` | ✅ | — | ✅ (`26.2+`) | 25 |
| `26.3` | ✅ | — | ✅ (`26.3+`) | 25 |

## Building

```bash
./scripts/setup-hooks.sh
./scripts/verify.sh [--container]
```

## License

`GPL-3.0-or-later` — see [LICENSE](LICENSE).
