package com.syncshare.ui;

import javafx.application.Platform;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Starts the JavaFX toolkit once per test JVM and shuts it down cleanly after
 * all test classes complete.
 */
public final class JavaFxToolkitExtension implements BeforeAllCallback, ExtensionContext.Store.CloseableResource {

    private static final String STORE_KEY = JavaFxToolkitExtension.class.getName();

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        ExtensionContext.Store rootStore = context.getRoot().getStore(ExtensionContext.Namespace.GLOBAL);
        rootStore.getOrComputeIfAbsent(STORE_KEY, key -> {
            startToolkit();
            return this;
        }, JavaFxToolkitExtension.class);
    }

    private static void startToolkit() {
        System.setProperty("syncshare.ui.suppressDialogs", "true");
        System.setProperty(
            "javafx.cachedir",
            Path.of("target", "javafx-cache").toAbsolutePath().toString()
        );

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
