package com.syncshare.ui;

import javafx.application.Platform;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncShareApplicationTest {

    @BeforeAll
    static void startJavaFxToolkit() throws Exception {
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

        assertTrue(started.await(15, TimeUnit.SECONDS), "JavaFX toolkit failed to start");
        Platform.setImplicitExit(false);
    }

    @AfterAll
    static void stopJavaFxToolkit() {
        Platform.exit();
    }

    @Test
    void requiredResourcesResolve() {
        URL fxmlUrl = SyncShareApplication.requireResource(SyncShareApplication.MAIN_VIEW_RESOURCE);
        URL cssUrl = SyncShareApplication.requireResource(SyncShareApplication.STYLESHEET_RESOURCE);

        assertNotNull(fxmlUrl);
        assertNotNull(cssUrl);
    }

    @Test
    void missingResourceThrowsClearError() {
        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> SyncShareApplication.requireResource("/com/syncshare/ui/non-existent.fxml")
        );

        assertTrue(error.getMessage().contains("non-existent.fxml"));
    }

    @Test
    void existingCliEntryPointIsPreserved() throws Exception {
        Class<?> appClass = Class.forName("com.syncshare.App");
        Method mainMethod = appClass.getMethod("main", String[].class);

        assertNotNull(mainMethod);
    }

    @Test
    void applicationInitializesStageAndShutsDownGracefully() throws Throwable {
        runOnFxThread(() -> {
            SyncShareApplication app = new SyncShareApplication();
            app.init();

            Stage stage = new Stage();
            try {
                app.initializeStage(stage);
                Platform.setImplicitExit(false);

                assertTrue(stage.isShowing(), "Primary stage should be showing");
                assertEquals(SyncShareApplication.APP_TITLE, stage.getTitle());
                assertEquals(SyncShareApplication.MIN_WIDTH, stage.getMinWidth());
                assertEquals(SyncShareApplication.MIN_HEIGHT, stage.getMinHeight());

                assertNotNull(stage.getScene(), "Primary scene should be attached");
                assertEquals(SyncShareApplication.INITIAL_WIDTH, stage.getScene().getWidth());
                assertEquals(SyncShareApplication.INITIAL_HEIGHT, stage.getScene().getHeight());
                assertFalse(stage.getScene().getStylesheets().isEmpty(), "Stylesheet should be loaded");

                MainController controller = app.getMainController();
                assertNotNull(controller, "MainController should be initialized");
                assertTrue(controller.isInitialized());
                assertEquals("Status: Ready", controller.getStatusText());

                app.stop();
                assertEquals("Status: Shutting down...", controller.getStatusText());

                // Verify stop() is idempotent when invoked more than once.
                app.stop();
            } finally {
                stage.close();
            }
        });
    }

    @FunctionalInterface
    private interface FxAction {
        void run() throws Exception;
    }

    private static void runOnFxThread(FxAction action) throws Throwable {
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
