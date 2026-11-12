package com.syncshare.history;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Persistence contract for saving and loading terminal {@link TransferRecord} history.
 */
public interface TransferHistoryRepository {

    record LoadResult(
        List<TransferRecord> records,
        boolean corrupted,
        int skippedInvalidCount,
        List<String> warnings
    ) {
        public LoadResult {
            records = List.copyOf(Objects.requireNonNull(records, "records"));
            warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
        }

        public static LoadResult empty() {
            return new LoadResult(List.of(), false, 0, List.of());
        }

        public static LoadResult corrupted(String message) {
            String warning = (message != null && !message.isBlank())
                ? message
                : "History storage file is corrupted.";
            return new LoadResult(List.of(), true, 0, List.of(warning));
        }

        public boolean hasWarnings() {
            return corrupted || skippedInvalidCount > 0 || !warnings.isEmpty();
        }
    }

    LoadResult load() throws IOException;

    void save(List<TransferRecord> records) throws IOException;

    void clear() throws IOException;

    Path storageLocation();
}
