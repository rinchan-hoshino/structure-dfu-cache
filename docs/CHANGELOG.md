# 1.1.0

- Require RinLib 1.2.0 and use its shared structure resource and lifecycle API.
- Build the same cache implementation for Minecraft 1.20.1, 1.21.1, 26.1.2 and 26.2.
- Rebuild unindexed or checksum-invalid cached blobs from the original resource.
- Atomically replace blobs without changing active resource generations.
- Reuse conversion work for identical resources and release worker pools after preparation.
- Close generated resource layers when the server stops.
