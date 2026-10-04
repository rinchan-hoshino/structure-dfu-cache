# Structure DFU Cache

<!-- PROJECT_PAGE_START -->
Moves repetitive structure DataFixerUpper (DFU) migration to the startup phase and caches the result to improve TPS during heavy chunk loading.

## How it works

- Uniformly parses server structure resources during game startup or reload.
- Provides and caches processed structures through a vanilla-compatible resource pack layer.

## Requirements

Install the following mod as a dependency:

- RinLib
<!-- PROJECT_PAGE_END -->
