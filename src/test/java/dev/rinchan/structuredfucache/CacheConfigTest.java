package dev.rinchan.structuredfucache;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CacheConfigTest {
    @TempDir Path directory;
    @Test void firstLoadCreatesAnEditableTomlFile() throws Exception {
        assertEquals(CacheConfig.defaults(), CacheConfig.load(directory));
        assertTrue(Files.isRegularFile(directory.resolve("structure_dfu_cache.toml")));
    }
    @Test void editedLimitsAreReadInMebibytesWithoutLosingOtherSettings() throws Exception {
        var file = directory.resolve("structure_dfu_cache.toml");
        Files.writeString(file,"maxDecodedNbtMiB = 2048\nmaxCompressedResourceMiB = 128\ncustom = \"retained\"\n");
        var config = CacheConfig.load(directory);
        assertEquals(2147483648L,config.maxDecodedNbtBytes());
        assertEquals(134217728,config.maxCompressedResourceBytes());
        assertTrue(Files.readString(file).contains("retained"));
    }
    @Test void limitsMustBePositiveWholeNumbers() throws Exception {
        var file=directory.resolve("structure_dfu_cache.toml");
        for(String value:new String[]{"0","-1","1.5","\"large\""}) {
            Files.writeString(file,"maxDecodedNbtMiB = "+value+"\nmaxCompressedResourceMiB = 64\n");
            assertThrows(IllegalArgumentException.class,()->CacheConfig.load(directory),value);
        }
    }
}
