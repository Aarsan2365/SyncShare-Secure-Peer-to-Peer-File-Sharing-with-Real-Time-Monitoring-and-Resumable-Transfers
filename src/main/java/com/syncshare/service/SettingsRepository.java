package com.syncshare.service;

import com.syncshare.model.AppSettings;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Persistence abstraction for loading and saving application configuration.
 */
public interface SettingsRepository {

    record LoadResult(
        AppSettings settings,
        boolean fallbackToDefaults,
        boolean corrupted,
        List<String> warnings
    ) {
        public static LoadResult success(AppSettings settings) {
            return new LoadResult(settings, false, false, List.of());
        }

        public static LoadResult fallback(AppSettings defaults, String message) {
            return new LoadResult(defaults, true, false, List.of(message));
        }

        public static LoadResult corrupted(AppSettings defaults, String message) {
            return new LoadResult(defaults, true, true, List.of(message));
        }
    }

    Path storageLocation();

    LoadResult load() throws IOException;

    void save(AppSettings settings) throws IOException;
}
