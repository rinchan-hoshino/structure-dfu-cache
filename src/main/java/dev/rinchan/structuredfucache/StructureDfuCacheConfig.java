package dev.rinchan.structuredfucache;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class StructureDfuCacheConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.ConfigValue<Integer> COLD_BUILD_TIMEOUT_SECONDS;
    public static final ModConfigSpec.IntValue WORKER_THREADS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        COLD_BUILD_TIMEOUT_SECONDS = builder
            .comment(
                "Maximum wall-clock time for a cold or invalidated cache build.",
                "Use 0 only when an administrator explicitly accepts unlimited waiting.",
                "A finite value must be between 60 and 1800 seconds. Timeout fails the reload; it never falls back silently."
            )
            .define("coldBuildTimeoutSeconds", 300, StructureDfuCacheConfig::validTimeout);
        WORKER_THREADS = builder
            .comment("Parallel DFU worker count. Keep this bounded to control peak memory use.")
            .defineInRange("workerThreads", 4, 1, 16);
        SPEC = builder.build();
    }

    private StructureDfuCacheConfig() {
    }

    private static boolean validTimeout(Object value) {
        return value instanceof Integer seconds && (seconds == 0 || seconds >= 60 && seconds <= 1800);
    }

}
