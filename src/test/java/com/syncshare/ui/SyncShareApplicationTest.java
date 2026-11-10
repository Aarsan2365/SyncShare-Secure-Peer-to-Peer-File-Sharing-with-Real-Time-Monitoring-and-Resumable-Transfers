package com.syncshare.ui;

import javafx.application.Platform;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.reflect.Method;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(JavaFxToolkitExtension.class)
class SyncShareApplicationTest {

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
        JavaFxToolkitExtension.runOnFxThread(() -> {
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
}
