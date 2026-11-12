// src/main/java/com/syncshare/resume/ResumeStore.java
package com.syncshare.resume;

import com.syncshare.service.ReceiveDestinationValidator;
import com.syncshare.transfer.HashUtils;

import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;

public final class ResumeStore implements AutoCloseable {

    private final Path part;
    private final Path checkpoint;
    private final Path destination;

    private final String name;
    private final String hash;
    private final long size;

    private final FileChannel lockChannel;
    private FileLock lock;
    private RandomAccessFile data;

    private long offset;
    private MessageDigest digest;

    public ResumeStore(
            Path folder,
            String name,
            long size,
            String hash
    ) throws IOException {

        this.name = name;
        this.size = size;
        this.hash = hash;

        Path resumeFolder = folder.resolve(".resume");
        Files.createDirectories(resumeFolder);

        String identity = name + "\n" + size + "\n" + hash;

        String id = HexFormat.of().formatHex(
            HashUtils.newSha256().digest(
                identity.getBytes(StandardCharsets.UTF_8)
            )
        );

        part = resumeFolder.resolve(id + ".part");
        checkpoint = resumeFolder.resolve(id + ".properties");
        destination = ReceiveDestinationValidator.resolveSafeFile(folder, id + "-" + name);

        lockChannel = FileChannel.open(
            resumeFolder.resolve(id + ".lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE
        );

        try {
            try {
                lock = lockChannel.tryLock();
            } catch (OverlappingFileLockException e) {
                throw new IOException("Transfer already active", e);
            }

            if (lock == null) {
                throw new IOException("Transfer already active");
            }

            // Recover when the previous VERIFIED response was lost.
            if (Files.isRegularFile(destination)
                    && Files.size(destination) == size
                    && HashUtils.calculateSHA256(destination).equals(hash)) {

                offset = size;
                return;
            }

            data = new RandomAccessFile(part.toFile(), "rw");
            digest = HashUtils.newSha256();

            Properties properties = new Properties();

            if (Files.exists(checkpoint)) {
                try (InputStream input = Files.newInputStream(checkpoint)) {
                    properties.load(input);
                } catch (IllegalArgumentException e) {
                    properties.clear();
                }
            }

            boolean valid = false;

            try {
                offset = Long.parseLong(
                    properties.getProperty("offset", "-1")
                );

                valid =
                    name.equals(properties.getProperty("name"))
                    && hash.equals(properties.getProperty("hash"))
                    && size == Long.parseLong(
                        properties.getProperty("size", "-1")
                    )
                    && offset >= 0
                    && offset <= size
                    && data.length() >= offset;

                if (valid) {
                    byte[] buffer = new byte[64 * 1024];
                    long remaining = offset;

                    while (remaining > 0) {
                        int count = data.read(
                            buffer,
                            0,
                            (int) Math.min(buffer.length, remaining)
                        );

                        if (count < 0) {
                            throw new EOFException("Partial file ended early");
                        }

                        digest.update(buffer, 0, count);
                        remaining -= count;
                    }

                    valid = snapshot().equals(
                        properties.getProperty("prefixHash")
                    );
                }

            } catch (NumberFormatException e) {
                valid = false;
            }

            if (!valid) {
                offset = 0;
                digest = HashUtils.newSha256();
            }

            // Remove bytes written after the last saved checkpoint.
            data.setLength(offset);
            data.seek(offset);
            data.getFD().sync();

            save();

        } catch (IOException | RuntimeException e) {
            try {
                close();
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }

            throw e;
        }
    }

    public long offset() {
        return offset;
    }

    private String snapshot() throws IOException {
        try {
            MessageDigest copy = (MessageDigest) digest.clone();
            return HexFormat.of().formatHex(copy.digest());

        } catch (CloneNotSupportedException e) {
            // Hash exactly the committed prefix for alternate providers.
            MessageDigest copy = HashUtils.newSha256();

            try (InputStream input = Files.newInputStream(part)) {
                byte[] buffer = new byte[64 * 1024];
                long remaining = offset;

                while (remaining > 0) {
                    int count = input.read(
                        buffer,
                        0,
                        (int) Math.min(buffer.length, remaining)
                    );

                    if (count < 0) {
                        throw new EOFException("Partial file ended early");
                    }

                    copy.update(buffer, 0, count);
                    remaining -= count;
                }
            }

            return HexFormat.of().formatHex(copy.digest());
        }
    }

    private void save() throws IOException {
        Properties properties = new Properties();

        properties.setProperty("name", name);
        properties.setProperty("size", Long.toString(size));
        properties.setProperty("hash", hash);
        properties.setProperty("offset", Long.toString(offset));
        properties.setProperty("prefixHash", snapshot());

        Path temporary = checkpoint.resolveSibling(
            checkpoint.getFileName() + ".tmp"
        );

        try (FileOutputStream output =
                new FileOutputStream(temporary.toFile())) {

            properties.store(output, "SyncShare checkpoint");
            output.getFD().sync();
        }

        try {
            Files.move(
                temporary,
                checkpoint,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            );

        } catch (AtomicMoveNotSupportedException e) {
            Files.move(
                temporary,
                checkpoint,
                StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    public void append(byte[] bytes, int length) throws IOException {
        if (data == null
                || length <= 0
                || length > bytes.length
                || length > size - offset) {

            throw new IOException("Invalid append");
        }

        data.write(bytes, 0, length);
        data.getFD().sync();

        digest.update(bytes, 0, length);
        offset += length;

        // Receiver must send CHUNK_ACK only after this returns.
        save();
    }

    public Path finish() throws IOException {
        if (offset != size) {
            throw new IOException("Incomplete file");
        }

        if (data != null) {
            String actualHash = HashUtils.calculateSHA256(part);

            if (!actualHash.equals(hash)) {
                data.setLength(0);
                data.seek(0);
                data.getFD().sync();

                offset = 0;
                digest = HashUtils.newSha256();
                save();

                throw new IOException(
                    "SHA-256 mismatch; next retry starts at zero"
                );
            }

            data.close();
            data = null;

            Path finalDestination = ReceiveDestinationValidator.resolveNonOverwritingPath(destination);
            Files.move(
                part,
                finalDestination
            );
            Files.deleteIfExists(checkpoint);
            return finalDestination;
        }

        Files.deleteIfExists(checkpoint);
        return destination;
    }

    @Override
    public void close() throws IOException {
        try {
            if (data != null) {
                data.close();
            }
        } finally {
            try {
                if (lock != null) {
                    lock.release();
                }
            } finally {
                lockChannel.close();
            }
        }
    }
}