package com.syncshare.ui;

import javafx.application.Platform;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Starts the JavaFX toolkit once per test JVM and shuts it down cleanly after
 * all test classes complete.
 */
public final class JavaFxToolkitExtension implements BeforeAllCallback, BeforeEachCallback, ExtensionContext.Store.CloseableResource {

    private static final String STORE_KEY = JavaFxToolkitExtension.class.getName();
    private static Path isolatedDataDir;

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        ExtensionContext.Store rootStore = context.getRoot().getStore(ExtensionContext.Namespace.GLOBAL);
        rootStore.getOrComputeIfAbsent(STORE_KEY, key -> {
            startToolkit();
            return this;
        }, JavaFxToolkitExtension.class);
    }

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        if (isolatedDataDir != null) {
            Files.deleteIfExists(isolatedDataDir.resolve("transfer-history.json"));
            Files.deleteIfExists(isolatedDataDir.resolve("settings.json"));
        }
    }

    private static void startToolkit() {
        System.setProperty("syncshare.ui.suppressDialogs", "true");
        System.setProperty(
            "javafx.cachedir",
            Path.of("target", "javafx-cache").toAbsolutePath().toString()
        );
        if (System.getProperty("syncshare.historyFile") == null
                && System.getProperty("syncshare.dataDir") == null) {
            try {
                isolatedDataDir = Files.createTempDirectory("syncshare-ui-test-");
                System.setProperty("syncshare.dataDir", isolatedDataDir.toString());
            } catch (IOException e) {
                throw new IllegalStateException("Failed to create isolated test data directory", e);
            }
        }

        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            Platform.runLater(started::countDown);
        }

        try {
            assertTrue(started.await(15, TimeUnit.SECONDS), "JavaFX toolkit failed to start");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while starting JavaFX toolkit", e);
        }
        Platform.setImplicitExit(false);
    }

    @Override
    public void close() {
        Platform.exit();
        if (isolatedDataDir != null && Files.exists(isolatedDataDir)) {
            try (Stream<Path> walk = Files.walk(isolatedDataDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                    }
                });
            } catch (IOException ignored) {
            }
        }
    }

    @FunctionalInterface
    public interface FxAction {
        void run() throws Exception;
    }

    public static void runOnFxThread(FxAction action) throws Throwable {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                latch.countDown();
            }
        });

        assertTrue(latch.await(15, TimeUnit.SECONDS), "Timed out waiting for JavaFX task");
        if (failure.get() != null) {
            throw failure.get();
        }
    }
}
