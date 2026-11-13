package com.syncshare.service;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

/**
 * Validates receive destination directories and incoming filenames to prevent
 * invalid storage targets, path traversal escapes, and accidental overwrites.
 */
public final class ReceiveDestinationValidator {

    private static final Set<String> WINDOWS_RESERVED_NAMES = Set.of(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    );

    public record ValidationResult(
        boolean valid,
        String message,
        Path resolvedDirectory,
        boolean missingDirectory
    ) {
        public static ValidationResult ok(Path resolvedDirectory, String message) {
            return new ValidationResult(true, message, resolvedDirectory, false);
        }

        public static ValidationResult error(String message, Path resolvedDirectory, boolean missingDirectory) {
            return new ValidationResult(false, message, resolvedDirectory, missingDirectory);
        }
    }

    private ReceiveDestinationValidator() {
    }

    public static ValidationResult validateDirectory(String directoryPath) {
        if (directoryPath == null || directoryPath.isBlank()) {
            return ValidationResult.error("Select or enter a destination directory.", null, false);
        }
        String trimmed = directoryPath.trim();
        if (trimmed.indexOf('\0') >= 0) {
            return ValidationResult.error("Destination path contains an invalid NUL character.", null, false);
        }
        try {
            return validateDirectory(Path.of(trimmed));
        } catch (InvalidPathException e) {
            return ValidationResult.error("Destination directory path is invalid: " + e.getReason(), null, false);
        }
    }

    public static ValidationResult validateDirectory(Path directory) {
        if (directory == null) {
            return ValidationResult.error("Select or enter a destination directory.", null, false);
        }

        Path normalized = directory.toAbsolutePath().normalize();
        if (!Files.exists(normalized)) {
            return ValidationResult.error(
                "Destination directory does not exist: " + normalized,
                normalized,
                true
            );
        }
        if (!Files.isDirectory(normalized)) {
            return ValidationResult.error(
                "Selected destination path is not a directory: " + normalized,
                normalized,
                false
            );
        }

        Path realDir;
        try {
            realDir = normalized.toRealPath();
        } catch (AccessDeniedException e) {
            return ValidationResult.error(
                "Destination directory is not writable: " + normalized,
                normalized,
                false
            );
        } catch (IOException | SecurityException e) {
            return ValidationResult.error(
                "Cannot access destination directory: " + describeIoFailure(e),
                normalized,
                false
            );
        }

        try {
            Path probe = Files.createTempFile(realDir, ".syncshare-write-check-", ".tmp");
            Files.deleteIfExists(probe);
        } catch (AccessDeniedException e) {
            return ValidationResult.error(
                "Destination directory is not writable: " + realDir,
                realDir,
                false
            );
        } catch (IOException | SecurityException e) {
            return ValidationResult.error(
                "Cannot create files in destination directory: " + describeIoFailure(e),
                realDir,
                false
            );
        }

        return ValidationResult.ok(realDir, "Destination verified and writable: " + realDir);
    }

    public static ValidationResult createAndValidateDirectory(String directoryPath) {
        if (directoryPath == null || directoryPath.isBlank()) {
            return ValidationResult.error("Select or enter a destination directory.", null, false);
        }
        String trimmed = directoryPath.trim();
        if (trimmed.indexOf('\0') >= 0) {
            return ValidationResult.error("Destination path contains an invalid NUL character.", null, false);
        }
        Path parsed;
        try {
            parsed = Path.of(trimmed).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return ValidationResult.error("Destination directory path is invalid: " + e.getReason(), null, false);
        }

        if (Files.exists(parsed) && !Files.isDirectory(parsed)) {
            return ValidationResult.error(
                "Selected destination path is a regular file, not a directory: " + parsed,
                parsed,
                false
            );
        }

        try {
            Files.createDirectories(parsed);
        } catch (IOException | SecurityException e) {
            return ValidationResult.error(
                "Unable to create destination directory: " + describeIoFailure(e),
                parsed,
                false
            );
        }
        return validateDirectory(parsed);
    }

    public static boolean hasUsableSpace(Path directory, long requiredBytes) {
        if (directory == null || requiredBytes <= 0) {
            return true;
        }
        try {
            Path target = Files.exists(directory) ? directory : directory.toAbsolutePath().getParent();
            while (target != null && !Files.exists(target)) {
                target = target.getParent();
            }
            if (target == null) {
                return true;
            }
            java.nio.file.FileStore store = Files.getFileStore(target);
            long usable = store.getUsableSpace();
            if (usable > 0 && usable < requiredBytes) {
                return false;
            }
            return true;
        } catch (IOException | SecurityException ignored) {
            return true;
        }
    }

    public static ValidationResult validateStorageCapacity(Path directory, long requiredBytes) {
        if (directory == null) {
            return ValidationResult.error("Destination directory cannot be null.", null, false);
        }
        if (!hasUsableSpace(directory, requiredBytes)) {
            return ValidationResult.error(
                "Insufficient storage: Destination drive does not have enough free space ("
                    + requiredBytes + " bytes required).",
                directory,
                false
            );
        }
        return ValidationResult.ok(directory, "Storage capacity verified.");
    }

    public static boolean isSafeSingleFileName(String rawFileName) {
        if (rawFileName == null || rawFileName.isBlank()) {
            return false;
        }
        if (rawFileName.equals(".") || rawFileName.equals("..")) {
            return false;
        }
        if (rawFileName.contains("..")
            || rawFileName.indexOf('/') >= 0
            || rawFileName.indexOf('\\') >= 0
            || rawFileName.indexOf(':') >= 0
            || rawFileName.indexOf('\0') >= 0) {
            return false;
        }
        for (int i = 0; i < rawFileName.length(); i++) {
            char ch = rawFileName.charAt(i);
            if (ch < 32 || ch == '<' || ch == '>' || ch == '"' || ch == '|' || ch == '?' || ch == '*') {
                return false;
            }
        }
        String upper = rawFileName.toUpperCase();
        int dotIdx = upper.indexOf('.');
        String base = dotIdx >= 0 ? upper.substring(0, dotIdx) : upper;
        if (WINDOWS_RESERVED_NAMES.contains(base)) {
            return false;
        }
        try {
            Path parsed = Path.of(rawFileName);
            return parsed.getNameCount() == 1 && !parsed.isAbsolute();
        } catch (InvalidPathException e) {
            return false;
        }
    }

    public static Path resolveSafeFile(Path destinationDirectory, String rawFileName) throws IOException {
        Objects.requireNonNull(destinationDirectory, "destinationDirectory");
        if (!isSafeSingleFileName(rawFileName)) {
            throw new IOException("Unsafe or invalid filename rejected: " + rawFileName);
        }

        Path base = destinationDirectory.toAbsolutePath().normalize();
        Path resolved = base.resolve(rawFileName).normalize();

        if (resolved.getParent() == null
            || !resolved.getParent().equals(base)
            || !resolved.startsWith(base)
            || resolved.equals(base)) {
            throw new IOException("Path traversal attempt blocked for filename: " + rawFileName);
        }

        if (Files.isSymbolicLink(resolved)) {
            throw new IOException("Symbolic link target rejected at destination: " + resolved);
        }

        return resolved;
    }

    public static Path resolveNonOverwritingPath(Path candidate) throws IOException {
        Objects.requireNonNull(candidate, "candidate");
        Path normalizedCandidate = candidate.toAbsolutePath().normalize();
        Path parent = normalizedCandidate.getParent();
        if (parent == null) {
            throw new IOException("Destination path has no parent directory: " + normalizedCandidate);
        }

        if (!Files.exists(normalizedCandidate, LinkOption.NOFOLLOW_LINKS)) {
            return normalizedCandidate;
        }

        String fileName = normalizedCandidate.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        String stem = dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
        String extension = dotIndex > 0 ? fileName.substring(dotIndex) : "";

        for (int attempt = 1; attempt <= 10_000; attempt++) {
            String numberedName = stem + " (" + attempt + ")" + extension;
            Path next = parent.resolve(numberedName).normalize();
            if (!parent.equals(next.getParent()) || !next.startsWith(parent)) {
                throw new IOException("Path containment check failed for: " + next);
            }
            if (!Files.exists(next, LinkOption.NOFOLLOW_LINKS)) {
                return next;
            }
        }
        throw new IOException("Unable to allocate unique filename in " + parent);
    }

    private static String describeIoFailure(Exception error) {
        String message = error.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }
        return error.getClass().getSimpleName();
    }
}
