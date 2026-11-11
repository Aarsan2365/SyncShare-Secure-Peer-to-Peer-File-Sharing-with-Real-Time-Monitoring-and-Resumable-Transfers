package com.syncshare.ui;

import com.syncshare.service.SyncShareServices;
import com.syncshare.ui.design.DesignSystem;
import com.syncshare.ui.design.UiDialogs;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.URL;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public class SyncShareApplication extends Application {

    public static final String APP_TITLE = "SyncShare";
    public static final double INITIAL_WIDTH = 1080;
    public static final double INITIAL_HEIGHT = 720;
    public static final double MIN_WIDTH = 800;
    public static final double MIN_HEIGHT = 560;

    public static final String MAIN_VIEW_RESOURCE = "/com/syncshare/ui/main-view.fxml";
    public static final String STYLESHEET_RESOURCE = DesignSystem.MASTER_STYLESHEET;

    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private MainController mainController;

    public record LoadedView(Parent root, MainController controller, Scene scene) {
    }

    @Override
    public void init() {
        requireResource(MAIN_VIEW_RESOURCE);
        requireResource(STYLESHEET_RESOURCE);
        DesignSystem.verifyStylesheetsAvailable();
    }

    @Override
    public void start(Stage primaryStage) {
        Thread.currentThread().setUncaughtExceptionHandler((thread, error) -> {
            System.err.println(
                "Uncaught JavaFX error on thread " + thread.getName() + ": " + error.getMessage()
            );
            error.printStackTrace(System.err);
        });

        try {
            initializeStage(primaryStage);
        } catch (Throwable error) {
            handleStartupFailure(error);
        }
    }

    public void initializeStage(Stage primaryStage) throws IOException {
        Objects.requireNonNull(primaryStage, "primaryStage");

        Platform.setImplicitExit(true);

        LoadedView loadedView = loadMainView();
        this.mainController = loadedView.controller();

        primaryStage.setTitle(APP_TITLE);
        primaryStage.setMinWidth(MIN_WIDTH);
        primaryStage.setMinHeight(MIN_HEIGHT);
        primaryStage.setScene(loadedView.scene());
        primaryStage.setOnCloseRequest(event -> initiateShutdown());
        primaryStage.centerOnScreen();
        primaryStage.show();
    }

    public static LoadedView loadMainView() throws IOException {
        return loadMainView(SyncShareServices.createDefault());
    }

    public static LoadedView loadMainView(SyncShareServices services) throws IOException {
        Objects.requireNonNull(services, "services");
        URL fxmlUrl = requireResource(MAIN_VIEW_RESOURCE);
        requireResource(STYLESHEET_RESOURCE);
        DesignSystem.verifyStylesheetsAvailable();

        FXMLLoader loader = new FXMLLoader(fxmlUrl);
        loader.setControllerFactory(type -> {
            if (type == MainController.class) {
                return new MainController(services);
            }
            try {
                return type.getDeclaredConstructor().newInstance();
            } catch (Exception error) {
                throw new IllegalStateException("Unable to instantiate controller: " + type.getName(), error);
            }
        });
        Parent root = loader.load();
        MainController controller = loader.getController();

        Scene scene = new Scene(root, INITIAL_WIDTH, INITIAL_HEIGHT);
        DesignSystem.applyTheme(scene);

        return new LoadedView(root, controller, scene);
    }

    public static URL requireResource(String resourcePath) {
        Objects.requireNonNull(resourcePath, "resourcePath");
        URL url = SyncShareApplication.class.getResource(resourcePath);
        if (url == null) {
            throw new IllegalStateException("Required UI resource not found: " + resourcePath);
        }
        return url;
    }

    public MainController getMainController() {
        return mainController;
    }

    protected void initiateShutdown() {
        Platform.exit();
    }

    @Override
    public void stop() {
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }

        if (mainController != null) {
            try {
                mainController.shutdown();
            } catch (RuntimeException error) {
                System.err.println("Error during UI shutdown: " + error.getMessage());
                error.printStackTrace(System.err);
            }
        }
    }

    private void handleStartupFailure(Throwable error) {
        String message = error.getMessage() != null && !error.getMessage().isBlank()
            ? error.getMessage()
            : error.getClass().getSimpleName();

        System.err.println("Failed to start " + APP_TITLE + ": " + message);
        error.printStackTrace(System.err);

        if (!Boolean.getBoolean("syncshare.ui.suppressDialogs")) {
            try {
                Alert alert = UiDialogs.error(
                    null,
                    APP_TITLE + " Startup Error",
                    "Unable to initialize the application window.",
                    message
                );
                alert.showAndWait();
            } catch (Throwable dialogError) {
                System.err.println(
                    "Unable to display startup error dialog: " + dialogError.getMessage()
                );
            }
        }

        Platform.exit();
    }

    public static void main(String[] args) {
        try {
            launch(args);
        } catch (Throwable error) {
            String message = error.getMessage() != null && !error.getMessage().isBlank()
                ? error.getMessage()
                : error.getClass().getSimpleName();
            System.err.println(APP_TITLE + " failed during JavaFX launch: " + message);
            error.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
