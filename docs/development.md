# Development

## Layout

`src/main/java` contains one cache implementation for every supported game version.
`fabric`, `neoforge` and `forge` contain only loader entry points. Minecraft resource
and lifecycle differences live in RinLib's structure adapter.

## Build

Build the matching RinLib profile first, then run:

```sh
./gradlew test build -Pprofile=1.21.1 -Ploader=neoforge
```

Available profiles are listed in `profiles.json`. Fabric builds for 1.20.1 require a
Java 21 Gradle runtime and compile with a Java 17 toolchain. Minecraft 26.x uses Java 25.
Forge 1.20.1 uses Gradle 8.10.2; launch its build with RinLib's
`profiles/wrapper-forge/gradlew -p /path/to/structure-dfu-cache`.
By default the sibling `../rinlib` checkout supplies the dependency jar. Override
its path with `-PrinlibJar=/path/to/rinlib.jar`.

## Cache lifecycle

On startup or resource reload, Minecraft resolves pack priority and filters first.
RinLib hands that resource manager and Minecraft's own data fixer to the cache.
The cache hashes effective bytes, verifies indexed blob checksums, and upgrades old
structure tags through `DataFixTypes.STRUCTURE`. Source and converted bytes are
persisted in content-addressed files.

Successful entries are materialized as a vanilla directory pack; individual failures
delegate to their already-resolved vanilla resources in the same layer. Atomic blob
replacement keeps older hard-linked generations readable during reload. The
current and preceding generation remain available until a later reload; stopping
the server closes them. Persistent files are reused on the next launch.

The cache identity includes its format, target DataVersion, game version and loader
version. Missing or invalid indexes never make an existing blob trustworthy.
Individual read, write or materialization failures delegate only that structure to
its original resource. A cache-root, lock, generation metadata or index commit
failure leaves the original manager active for the whole generation. Malformed or
unconvertible NBT retains its exact original bytes and is retried on the next
preparation rather than indexed as a successful result.

Every preparation logs a console summary. Normal format, runtime or data-pack
updates are informational. Repairs, unoptimized resources and vanilla fallbacks
also produce a current-generation notice delivered once to operators on each
login. Ordinary players receive nothing; a healthy preparation clears the notice.

All workers finish during preparation. Ordinary structure reads use vanilla
resource classes without a mod-owned gameplay worker.
