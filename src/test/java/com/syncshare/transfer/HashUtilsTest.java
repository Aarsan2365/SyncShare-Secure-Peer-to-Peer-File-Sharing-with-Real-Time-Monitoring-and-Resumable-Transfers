package com.syncshare.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class HashUtilsTest {
    @Test
    void calculatesKnownSha256(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("abc.txt");
        Files.writeString(file, "abc");
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            HashUtils.calculateSHA256(file)
        );
    }

    @Test
    void hashesEmptyFile(@TempDir Path dir) throws Exception {
        Path file = Files.createFile(dir.resolve("empty.bin"));
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            HashUtils.calculateSHA256(file)
        );
    }
}
