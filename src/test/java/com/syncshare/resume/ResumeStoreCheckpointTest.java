package com.syncshare.resume;

import com.syncshare.transfer.HashUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ResumeStoreCheckpointTest {

    @Test
    void reopensSavedCheckpointAndFinishesVerifiedFile(@TempDir Path tempDir) throws Exception {
        byte[] bytes = new byte[150_000];
        new Random(17).nextBytes(bytes);
        Path source = tempDir.resolve("source.bin");
        Files.write(source, bytes);
        String hash = HashUtils.calculateSHA256(source);
        Path peerFolder = tempDir.resolve("received").resolve("test-peer");

        try (ResumeStore store = new ResumeStore(peerFolder, "source.bin", bytes.length, hash)) {
            assertEquals(0L, store.offset());
            store.append(bytes, 65_536);
            assertEquals(65_536L, store.offset());
        }

        Path finished;
        try (ResumeStore store = new ResumeStore(peerFolder, "source.bin", bytes.length, hash)) {
            assertEquals(65_536L, store.offset());
            byte[] remainder = Arrays.copyOfRange(bytes, 65_536, bytes.length);
            store.append(remainder, remainder.length);
            finished = store.finish();
        }
        assertTrue(Files.isRegularFile(finished));
        assertArrayEquals(bytes, Files.readAllBytes(finished));
        assertEquals(hash, HashUtils.calculateSHA256(finished));
    }

    @Test
    void damagedSavedPrefixStartsFromZero(@TempDir Path tempDir) throws Exception {
        byte[] bytes = new byte[90_000];
        new Random(23).nextBytes(bytes);
        Path source = tempDir.resolve("source.bin");
        Files.write(source, bytes);
        String hash = HashUtils.calculateSHA256(source);
        Path peerFolder = tempDir.resolve("received").resolve("test-peer");

        try (ResumeStore store = new ResumeStore(peerFolder, "source.bin", bytes.length, hash)) {
            store.append(bytes, 65_536);
        }

        Path partial;
        try (Stream<Path> paths = Files.list(peerFolder.resolve(".resume"))) {
            partial = paths.filter(f -> f.toString().endsWith(".part"))
                .findFirst().orElseThrow();
        }
        byte[] damaged = Files.readAllBytes(partial);
        damaged[0] ^= 1;
        Files.write(partial, damaged);

        try (ResumeStore store = new ResumeStore(peerFolder, "source.bin", bytes.length, hash)) {
            assertEquals(0L, store.offset());
        }
    }

    @Test
    void doesNotAllowWritingBeyondFileSize(@TempDir Path tempDir) throws Exception {
        Path peerFolder = tempDir.resolve("received").resolve("test-peer");
        byte[] data = {1, 2, 3, 4};
        String hash = java.util.HexFormat.of().formatHex(HashUtils.newSha256().digest(data));
        try (ResumeStore store = new ResumeStore(peerFolder, "four.bin", 4L, hash)) {
            assertThrows(IOException.class, () -> store.append(new byte[5], 5));
            assertEquals(0L, store.offset());
        }
    }
}
