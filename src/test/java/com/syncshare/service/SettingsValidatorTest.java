package com.syncshare.service;

import com.syncshare.model.AppSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsValidatorTest {

    @Test
    @DisplayName("Valid device alias is accepted; blank or excessive length is rejected")
    void testValidateDeviceAlias() {
        assertTrue(SettingsValidator.validateDeviceAlias("My-Node-01").valid());
        assertTrue(SettingsValidator.validateDeviceAlias("Desktop Workstation").valid());

        assertFalse(SettingsValidator.validateDeviceAlias(null).valid());
        assertFalse(SettingsValidator.validateDeviceAlias("").valid());
        assertFalse(SettingsValidator.validateDeviceAlias("   ").valid());

        String longAlias = "A".repeat(SettingsValidator.MAX_ALIAS_LENGTH + 1);
        assertFalse(SettingsValidator.validateDeviceAlias(longAlias).valid());

        String controlChar = "Node\nName";
        assertFalse(SettingsValidator.validateDeviceAlias(controlChar).valid());
    }

    @Test
    @DisplayName("Port range bounds 1024 to 65535 are strictly enforced")
    void testValidatePort() {
        assertTrue(SettingsValidator.validatePort(1024).valid());
        assertTrue(SettingsValidator.validatePort(5050).valid());
        assertTrue(SettingsValidator.validatePort(65535).valid());

        assertFalse(SettingsValidator.validatePort(1023).valid());
        assertFalse(SettingsValidator.validatePort(0).valid());
        assertFalse(SettingsValidator.validatePort(-1).valid());
        assertFalse(SettingsValidator.validatePort(65536).valid());
        assertFalse(SettingsValidator.validatePort(70000).valid());

        // String port validation
        assertTrue(SettingsValidator.validatePort("5050").valid());
        assertFalse(SettingsValidator.validatePort("not-a-number").valid());
        assertFalse(SettingsValidator.validatePort("").valid());
        assertFalse(SettingsValidator.validatePort(null).valid());
    }

    @Test
    @DisplayName("Valid directory passes validation and resolves real path")
    void testValidateDirectoryValid(@TempDir Path tempDir) {
        SettingsValidator.ValidationResult result = SettingsValidator.validateDirectory(
            tempDir.toString(),
            "Download directory",
            false,
            true
        );

        assertTrue(result.valid());
        assertNotNull(result.resolvedPath());
        assertFalse(result.missingDirectory());
    }

    @Test
    @DisplayName("Missing directory is reported with missingDirectory flag when createIfMissing is false")
    void testValidateDirectoryMissing(@TempDir Path tempDir) {
        Path missing = tempDir.resolve("non_existent_subdir");

        SettingsValidator.ValidationResult result = SettingsValidator.validateDirectory(
            missing.toString(),
            "Receive directory",
            false,
            true
        );

        assertFalse(result.valid());
        assertTrue(result.missingDirectory());
        assertNotNull(result.resolvedPath());
    }

    @Test
    @DisplayName("Missing directory is created when createIfMissing is true")
    void testValidateDirectoryCreateIfMissing(@TempDir Path tempDir) {
        Path toCreate = tempDir.resolve("new_folder_created");
        assertFalse(Files.exists(toCreate));

        SettingsValidator.ValidationResult result = SettingsValidator.validateDirectory(
            toCreate.toString(),
            "Receive directory",
            true,
            true
        );

        assertTrue(result.valid());
        assertTrue(Files.exists(toCreate));
        assertTrue(Files.isDirectory(toCreate));
    }

    @Test
    @DisplayName("Path pointing to a regular file is rejected as directory")
    void testValidateDirectoryRegularFile(@TempDir Path tempDir) throws IOException {
        Path regularFile = tempDir.resolve("file.txt");
        Files.writeString(regularFile, "sample content");

        SettingsValidator.ValidationResult result = SettingsValidator.validateDirectory(
            regularFile.toString(),
            "Storage directory",
            false,
            true
        );

        assertFalse(result.valid());
        assertFalse(result.missingDirectory());
        assertTrue(result.message().contains("not a directory"));
    }

    @Test
    @DisplayName("Path with invalid characters or NUL is rejected")
    void testValidateDirectoryInvalidSyntax() {
        SettingsValidator.ValidationResult nullByte = SettingsValidator.validateDirectory(
            "bad\0path",
            "Storage",
            false,
            true
        );
        assertFalse(nullByte.valid());
        assertTrue(nullByte.message().contains("NUL"));

        SettingsValidator.ValidationResult empty = SettingsValidator.validateDirectory(
            "",
            "Storage",
            false,
            true
        );
        assertFalse(empty.valid());
    }

    @Test
    @DisplayName("Non-writable directory is detected and rejected")
    void testValidateDirectoryNonWritable(@TempDir Path tempDir) {
        Path readOnlyDir = tempDir.resolve("readonly-check");
        new File(readOnlyDir.toString()).mkdirs();

        File dirFile = readOnlyDir.toFile();
        boolean markedReadOnly = dirFile.setReadOnly();
        try {
            if (markedReadOnly && !Files.isWritable(readOnlyDir)) {
                SettingsValidator.ValidationResult result = SettingsValidator.validateDirectory(
                    readOnlyDir.toString(),
                    "Restricted directory",
                    false,
                    true
                );
                assertFalse(result.valid());
                assertTrue(result.message().contains("not writable"));
            }
        } finally {
            dirFile.setWritable(true);
        }
    }

    @Test
    @DisplayName("Theme validation accepts DEFAULT and SYSTEM and rejects unknown themes")
    void testValidateTheme() {
        assertTrue(SettingsValidator.validateTheme("DEFAULT").valid());
        assertTrue(SettingsValidator.validateTheme("SYSTEM").valid());
        assertTrue(SettingsValidator.validateTheme("default").valid());
        assertTrue(SettingsValidator.validateTheme("").valid());
        assertTrue(SettingsValidator.validateTheme(null).valid());

        assertFalse(SettingsValidator.validateTheme("DARK_CYBERPUNK").valid());
        assertFalse(SettingsValidator.validateTheme("SOLARIZED").valid());
    }

    @Test
    @DisplayName("Complete valid AppSettings passes validation")
    void testValidateCompleteSettings(@TempDir Path tempDir) {
        AppSettings settings = new AppSettings(
            "Valid-Host",
            5050,
            tempDir.resolve("recv").toString(),
            tempDir.resolve("dl").toString(),
            true,
            true,
            false,
            false,
            true,
            true,
            true,
            true,
            "DEFAULT"
        );
        // Create directories so they exist and are writable
        tempDir.resolve("recv").toFile().mkdirs();
        tempDir.resolve("dl").toFile().mkdirs();

        SettingsValidator.ValidationResult result = SettingsValidator.validate(settings);
        assertTrue(result.valid(), "Validation should succeed for valid settings");

        List<String> allErrors = SettingsValidator.validateAll(settings);
        assertTrue(allErrors.isEmpty(), "Should have no errors");
    }

    @Test
    @DisplayName("Complete AppSettings with multiple invalid fields collects all errors")
    void testValidateAllCollectsMultipleErrors() {
        AppSettings badSettings = new AppSettings(
            "",                      // invalid: empty alias
            70000,                   // invalid: port > 65535
            "Z:\\invalid_receive_non_existent_drive_dir_xyz", // missing
            "Z:\\invalid_download_non_existent_drive_dir_xyz", // missing
            true,
            true,
            false,
            false,
            true,
            true,
            true,
            true,
            "UNSUPPORTED_THEME"      // invalid theme
        );

        List<String> errors = SettingsValidator.validateAll(badSettings);
        assertFalse(errors.isEmpty(), "Should report errors");
        assertTrue(errors.size() >= 3, "Should report alias, port, directory, theme errors");
    }
}
