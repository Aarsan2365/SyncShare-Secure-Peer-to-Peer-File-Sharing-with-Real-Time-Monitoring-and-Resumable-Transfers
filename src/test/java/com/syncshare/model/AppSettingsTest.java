package com.syncshare.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppSettingsTest {

    @Test
    @DisplayName("Default settings contain sensible non-null values")
    void testDefaultsSensible() {
        AppSettings defaults = AppSettings.defaults();

        assertNotNull(defaults.deviceAlias(), "deviceAlias should not be null");
        assertFalse(defaults.deviceAlias().isBlank(), "deviceAlias should not be blank");
        assertEquals(5050, defaults.listeningPort(), "Default port should be 5050");
        assertNotNull(defaults.receiveDirectory(), "receiveDirectory should not be null");
        assertNotNull(defaults.downloadDirectory(), "downloadDirectory should not be null");
        assertTrue(defaults.requireApproval(), "requireApproval should default to true");
        assertTrue(defaults.verifySha256(), "verifySha256 should default to true");
        assertFalse(defaults.autoStartReceiver(), "autoStartReceiver should default to false");
        assertFalse(defaults.startMinimized(), "startMinimized should default to false");
        assertTrue(defaults.notificationsEnabled(), "notificationsEnabled should default to true");
        assertTrue(defaults.notifyOnTransferComplete(), "notifyOnTransferComplete should default to true");
        assertTrue(defaults.notifyOnIncomingRequest(), "notifyOnIncomingRequest should default to true");
        assertTrue(defaults.soundEnabled(), "soundEnabled should default to true");
        assertEquals("DEFAULT", defaults.theme());
    }

    @Test
    @DisplayName("Backward-compatible constructor maintains defaults for new fields")
    void testLegacyConstructorCompatibility() {
        AppSettings legacy = new AppSettings(
            "Legacy-Node",
            6060,
            "custom_received",
            false,
            true,
            true
        );

        assertEquals("Legacy-Node", legacy.deviceAlias());
        assertEquals(6060, legacy.listeningPort());
        assertEquals("custom_received", legacy.receiveDirectory());
        assertEquals("custom_received", legacy.defaultSaveLocation());
        assertEquals("custom_received", legacy.saveLocation());
        assertFalse(legacy.requireApproval());
        assertTrue(legacy.verifySha256());
        assertTrue(legacy.autoStartReceiver());
        assertFalse(legacy.startMinimized());
        assertTrue(legacy.notificationsEnabled());
        assertEquals("DEFAULT", legacy.theme());
    }

    @Test
    @DisplayName("Builder correctly modifies individual settings while preserving others")
    void testBuilderModification(@TempDir Path tempDir) {
        AppSettings original = AppSettings.defaults();
        AppSettings modified = original.toBuilder()
            .deviceAlias("Fast-Workstation")
            .listeningPort(5080)
            .receiveDirectory(tempDir.resolve("recv").toString())
            .downloadDirectory(tempDir.resolve("dl").toString())
            .autoStartReceiver(true)
            .startMinimized(true)
            .notificationsEnabled(false)
            .theme("SYSTEM")
            .build();

        assertEquals("Fast-Workstation", modified.deviceAlias());
        assertEquals(5080, modified.listeningPort());
        assertEquals(tempDir.resolve("recv").toString(), modified.receiveDirectory());
        assertEquals(tempDir.resolve("dl").toString(), modified.downloadDirectory());
        assertTrue(modified.autoStartReceiver());
        assertTrue(modified.startMinimized());
        assertFalse(modified.notificationsEnabled());
        assertEquals("SYSTEM", modified.theme());

        // Unmodified flags remain intact
        assertEquals(original.requireApproval(), modified.requireApproval());
        assertEquals(original.verifySha256(), modified.verifySha256());
    }

    @Test
    @DisplayName("With-methods produce expected immutable copies")
    void testWithMethods() {
        AppSettings initial = AppSettings.defaults();

        AppSettings updatedAlias = initial.withDeviceAlias("New-Alias");
        assertEquals("New-Alias", updatedAlias.deviceAlias());
        assertEquals(initial.listeningPort(), updatedAlias.listeningPort());

        AppSettings updatedPort = initial.withListeningPort(8080);
        assertEquals(8080, updatedPort.listeningPort());

        AppSettings updatedSound = initial.withSoundEnabled(false);
        assertFalse(updatedSound.soundEnabled());

        AppSettings updatedMinimized = initial.withStartMinimized(true);
        assertTrue(updatedMinimized.startMinimized());
    }
}
