package com.syncshare.ui;

import com.syncshare.model.NavItemId;
import com.syncshare.model.TransferDirection;
import com.syncshare.model.TransferItem;
import com.syncshare.model.TransferStatus;
import com.syncshare.service.ConnectionService;
import com.syncshare.service.DeviceInfoService;
import com.syncshare.service.SettingsService;
import com.syncshare.service.SyncShareServices;
import com.syncshare.service.TransferService;
import com.syncshare.ui.design.EmptyStatePane;
import com.syncshare.ui.design.ValidatedTextField;
import com.syncshare.ui.navigation.NavigationResult;
import com.syncshare.ui.navigation.SidebarNavItem;
import com.syncshare.ui.navigation.SidebarNavigation;
import com.syncshare.ui.navigation.ViewNavigator;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(JavaFxToolkitExtension.class)
class DashboardNavigationTest {

    @Test
    void dashboardDisplaysRequiredWidgetsAndMeaningfulEmptyStatesOnStartup() throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            SyncShareServices services = createStubbedServices(true);
            Stage stage = new Stage();
            SyncShareApplication.LoadedView loaded = SyncShareApplication.loadMainView(services);
            try {
                configureTestStage(stage, loaded);
                MainController controller = loaded.controller();
                Parent root = loaded.scene().getRoot();
                root.applyCss();
                root.layout();

                // Header & Connection Status Indicator
                assertEquals("Dashboard", controller.getPageTitle());
                assertNotNull(controller.getConnectionStatusPill());
                assertFalse(controller.getConnectionStatusPill().getStatusHeadline().isBlank());

                // Sidebar navigation items & initial active state
                SidebarNavigation sidebar = controller.getSidebarNavigation();
                List<Button> buttons = sidebar.getNavigationButtons();
                assertEquals(6, buttons.size());
                assertEquals(
                    List.of("Dashboard", "Send Files", "Receive Files", "Active Transfers", "Transfer History", "Settings"),
                    buttons.stream().map(Button::getText).toList()
                );
                assertTrue(sidebar.getNavItem(NavItemId.DASHBOARD).isActive());
                assertTrue(sidebar.getNavItem(NavItemId.DASHBOARD).getStyleClass().contains("nav-item-active"));

                // Local device info fields populated from DeviceInfoService
                Label nameLabel = (Label) root.lookup("#deviceNameValueLabel");
                Label ipLabel = (Label) root.lookup("#deviceIpValueLabel");
                Label portLabel = (Label) root.lookup("#devicePortValueLabel");
                Label osLabel = (Label) root.lookup("#deviceOsValueLabel");
                Label folderLabel = (Label) root.lookup("#deviceFolderValueLabel");
                assertNotNull(nameLabel);
                assertNotNull(ipLabel);
                assertNotNull(portLabel);
                assertNotNull(osLabel);
                assertNotNull(folderLabel);
                assertFalse(nameLabel.getText().isBlank());
                assertFalse(ipLabel.getText().isBlank());
                assertTrue(portLabel.getText().contains("5050"));

                // Quick actions present and visible
                assertNotNull(root.lookup("#dashboardQuickSendButton"));
                assertNotNull(root.lookup("#dashboardQuickReceiveButton"));
                assertNotNull(root.lookup("#dashboardQuickActiveButton"));
                assertNotNull(root.lookup("#dashboardQuickHistoryButton"));
                assertNotNull(root.lookup("#dashboardQuickSettingsButton"));

                // Meaningful empty states shown for active & recent transfer summaries
                VBox activeSummary = (VBox) root.lookup("#activeSummaryContent");
                VBox recentSummary = (VBox) root.lookup("#recentSummaryContent");
                assertNotNull(activeSummary);
                assertNotNull(recentSummary);
                assertTrue(activeSummary.getChildren().get(0) instanceof EmptyStatePane);
                assertTrue(recentSummary.getChildren().get(0) instanceof EmptyStatePane);
                assertEquals(
                    "No active transfers",
                    ((EmptyStatePane) activeSummary.getChildren().get(0)).getTitle()
                );
                assertEquals(
                    "No recent transfers yet",
                    ((EmptyStatePane) recentSummary.getChildren().get(0)).getTitle()
                );
            } finally {
                services.shutdown();
                stage.close();
            }
        });
    }

    @Test
    void everyNavigationItemWorksPreservesActiveStateAndNeverRecreatesServices() throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            SyncShareServices services = createStubbedServices(true);
            Stage stage = new Stage();
            SyncShareApplication.LoadedView loaded = SyncShareApplication.loadMainView(services);
            try {
                configureTestStage(stage, loaded);
                MainController controller = loaded.controller();
                SidebarNavigation sidebar = controller.getSidebarNavigation();
                ViewNavigator navigator = controller.getViewNavigator();

                SettingsService settingsRef = services.settingsService();
                DeviceInfoService deviceRef = services.deviceInfoService();
                ConnectionService connectionRef = services.connectionService();
                TransferService transferRef = services.transferService();

                Map<NavItemId, ViewNavigator.LoadedScreen> firstVisitScreens = new EnumMap<>(NavItemId.class);

                for (Button button : sidebar.getNavigationButtons()) {
                    SidebarNavItem navItem = (SidebarNavItem) button;
                    NavItemId id = navItem.getDestinationId();

                    button.fire();
                    loaded.scene().getRoot().applyCss();
                    loaded.scene().getRoot().layout();

                    assertEquals(id, controller.getActiveDestination().id());
                    assertEquals(navItem.getDestination().headerTitle(), controller.getPageTitle());
                    assertEquals("Status: Ready", controller.getStatusText());

                    for (NavItemId candidate : NavItemId.values()) {
                        SidebarNavItem item = sidebar.getNavItem(candidate);
                        boolean expectedActive = candidate == id;
                        assertEquals(expectedActive, item.isActive());
                        assertEquals(expectedActive, item.getStyleClass().contains("nav-item-active"));
                    }

                    firstVisitScreens.put(id, navigator.getCachedScreen(id));
                    assertNotNull(firstVisitScreens.get(id));
                }

                // Second pass verifies screens stay cached and services are never recreated
                for (NavItemId id : NavItemId.values()) {
                    assertTrue(controller.navigateTo(id).success());
                    assertSame(firstVisitScreens.get(id), navigator.getCachedScreen(id));
                    assertSame(services, controller.getServices());
                    assertSame(settingsRef, controller.getServices().settingsService());
                    assertSame(deviceRef, controller.getServices().deviceInfoService());
                    assertSame(connectionRef, controller.getServices().connectionService());
                    assertSame(transferRef, controller.getServices().transferService());
                }
            } finally {
                services.shutdown();
                stage.close();
            }
        });
    }

    @Test
    void quickActionButtonsNavigateToTargetScreens() throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            SyncShareServices services = createStubbedServices(true);
            Stage stage = new Stage();
            SyncShareApplication.LoadedView loaded = SyncShareApplication.loadMainView(services);
            try {
                configureTestStage(stage, loaded);
                MainController controller = loaded.controller();
                Parent root = loaded.scene().getRoot();
                root.applyCss();
                root.layout();

                Map<String, NavItemId> quickButtons = Map.of(
                    "#dashboardQuickSendButton", NavItemId.SEND_FILES,
                    "#dashboardQuickReceiveButton", NavItemId.RECEIVE_FILES,
                    "#dashboardQuickActiveButton", NavItemId.ACTIVE_TRANSFERS,
                    "#dashboardQuickHistoryButton", NavItemId.TRANSFER_HISTORY,
                    "#dashboardQuickSettingsButton", NavItemId.SETTINGS
                );

                for (Map.Entry<String, NavItemId> entry : quickButtons.entrySet()) {
                    assertTrue(controller.navigateTo(NavItemId.DASHBOARD).success());
                    Button actionBtn = (Button) root.lookup(entry.getKey());
                    assertNotNull(actionBtn, "Missing quick action button " + entry.getKey());
                    actionBtn.fire();
                    assertEquals(entry.getValue(), controller.getActiveDestination().id());
                }

                Button headerQuickSend = (Button) root.lookup("#quickSendHeaderButton");
                assertNotNull(headerQuickSend);
                headerQuickSend.fire();
                assertEquals(NavItemId.SEND_FILES, controller.getActiveDestination().id());
            } finally {
                services.shutdown();
                stage.close();
            }
        });
    }

    @Test
    void connectionStatusAndTransferSummariesUpdateDynamically() throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            SyncShareServices services = createStubbedServices(true);
            Stage stage = new Stage();
            SyncShareApplication.LoadedView loaded = SyncShareApplication.loadMainView(services);
            try {
                configureTestStage(stage, loaded);
                MainController controller = loaded.controller();
                Parent root = loaded.scene().getRoot();
                root.applyCss();
                root.layout();

                assertTrue(controller.getConnectionStatusPill().getStatusHeadline().contains("Standby"));
                assertTrue(services.connectionService().startReceiver());
                assertTrue(controller.getConnectionStatusPill().getStatusHeadline().contains("Listening"));

                services.connectionService().submitPendingRequestForPreview("bundle.zip", 4096L, "192.168.1.50");
                assertEquals(1, services.connectionService().getPendingRequests().size());
                services.connectionService().rejectAllPending();
                assertEquals(0, services.connectionService().getPendingRequests().size());

                TransferItem active = new TransferItem(
                    "tx-live-1",
                    "firmware-update.bin",
                    1_000_000L,
                    500_000L,
                    TransferDirection.OUTGOING,
                    TransferStatus.TRANSFERRING,
                    "192.168.1.42:5050",
                    "SHA-256 active",
                    Instant.now(),
                    "Streaming chunks"
                );
                services.transferService().upsertActiveTransfer(active);

                VBox activeSummary = (VBox) root.lookup("#activeSummaryContent");
                assertFalse(activeSummary.getChildren().get(0) instanceof EmptyStatePane);

                TransferItem completed = new TransferItem(
                    "tx-live-1",
                    "firmware-update.bin",
                    1_000_000L,
                    1_000_000L,
                    TransferDirection.OUTGOING,
                    TransferStatus.COMPLETED,
                    "192.168.1.42:5050",
                    "SHA-256 verified",
                    Instant.now(),
                    "Verified"
                );
                services.transferService().moveToHistory(completed);

                assertTrue(activeSummary.getChildren().get(0) instanceof EmptyStatePane);
                VBox recentSummary = (VBox) root.lookup("#recentSummaryContent");
                assertFalse(recentSummary.getChildren().get(0) instanceof EmptyStatePane);

                services.connectionService().stopReceiver();
                assertTrue(controller.getConnectionStatusPill().getStatusHeadline().contains("Standby"));
            } finally {
                services.shutdown();
                stage.close();
            }
        });
    }

    @Test
    void navigationErrorsAreHandledGracefullyAndAllowRecovery() throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            SyncShareServices services = createStubbedServices(true);
            Stage stage = new Stage();
            SyncShareApplication.LoadedView loaded = SyncShareApplication.loadMainView(services);
            try {
                configureTestStage(stage, loaded);
                MainController controller = loaded.controller();
                ViewNavigator navigator = controller.getViewNavigator();

                AtomicBoolean shouldFail = new AtomicBoolean(true);
                navigator.setScreenLoader((dest, svc, nav) -> {
                    if (shouldFail.get() && dest.id() == NavItemId.SETTINGS) {
                        throw new IOException("Simulated FXML error");
                    }
                    return new ViewNavigator.LoadedScreen(new VBox(new Label("Loaded " + dest.label())), new Object());
                });

                NavigationResult failed = controller.navigateTo(NavItemId.SETTINGS);
                assertFalse(failed.success());
                assertEquals(NavItemId.SETTINGS, failed.destination());
                assertTrue(failed.errorMessage().contains("Simulated FXML error"));
                assertTrue(navigator.isShowingErrorFallback());
                assertEquals("Navigation Error", controller.getPageTitle());
                assertEquals("Status: Navigation Error", controller.getStatusText());

                // Recovery back to cached Dashboard succeeds without touching services
                NavigationResult recovered = controller.navigateTo(NavItemId.DASHBOARD);
                assertTrue(recovered.success());
                assertFalse(navigator.isShowingErrorFallback());
                assertEquals("Dashboard", controller.getPageTitle());
                assertEquals("Status: Ready", controller.getStatusText());

                // Clear simulated error and retry target screen
                shouldFail.set(false);
                assertTrue(controller.navigateTo(NavItemId.SETTINGS).success());
                assertFalse(navigator.isShowingErrorFallback());
            } finally {
                services.shutdown();
                stage.close();
            }
        });
    }

    @Test
    void responsiveDesktopLayoutNeverOverlapsControlsAcrossAllBreakpoints() throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            SyncShareServices services = createStubbedServices(true);
            Stage stage = new Stage();
            SyncShareApplication.LoadedView loaded = SyncShareApplication.loadMainView(services);
            try {
                configureTestStage(stage, loaded);
                MainController controller = loaded.controller();
                Scene scene = loaded.scene();
                Parent root = scene.getRoot();

                double[][] breakpoints = {
                    {SyncShareApplication.MIN_WIDTH, SyncShareApplication.MIN_HEIGHT},
                    {SyncShareApplication.INITIAL_WIDTH, SyncShareApplication.INITIAL_HEIGHT},
                    {1440, 900}
                };

                for (double[] size : breakpoints) {
                    stage.setWidth(size[0]);
                    stage.setHeight(size[1]);

                    for (NavItemId id : NavItemId.values()) {
                        assertTrue(controller.navigateTo(id).success());
                        root.applyCss();
                        root.layout();

                        double sceneWidth = scene.getWidth();
                        double sceneHeight = scene.getHeight();
                        assertTrue(sceneWidth > 0 && sceneHeight > 0);

                        // 1. Sidebar navigation buttons do not overlap and stay within scene height
                        List<Button> navButtons = controller.getNavigationButtons();
                        for (int i = 0; i < navButtons.size(); i++) {
                            Bounds current = navButtons.get(i).localToScene(navButtons.get(i).getBoundsInLocal());
                            assertTrue(current.getMaxY() <= sceneHeight + 1.0);
                            if (i > 0) {
                                Bounds prev = navButtons.get(i - 1).localToScene(navButtons.get(i - 1).getBoundsInLocal());
                                assertTrue(
                                    prev.getMaxY() <= current.getMinY() + 1.0,
                                    "Sidebar buttons overlap at " + size[0] + "x" + size[1]
                                );
                            }
                        }

                        // 2. Topbar title and right slot do not overlap or overflow horizontally
                        Node pageHeader = root.lookup(".page-header");
                        Node rightSlot = root.lookup("#topbarRightSlot");
                        assertNotNull(pageHeader);
                        assertNotNull(rightSlot);
                        Bounds headerBounds = pageHeader.localToScene(pageHeader.getBoundsInLocal());
                        Bounds rightBounds = rightSlot.localToScene(rightSlot.getBoundsInLocal());
                        assertTrue(
                            headerBounds.getMaxX() <= rightBounds.getMinX() + 1.0,
                            "Topbar title and controls overlap on " + id + " at " + size[0] + "x" + size[1]
                        );
                        assertTrue(
                            rightBounds.getMaxX() <= sceneWidth + 2.0,
                            "Topbar right controls overflow scene on " + id + " at " + size[0] + "x" + size[1]
                        );

                        // 3. On Dashboard, verify GridPane child cards never overlap pairwise
                        if (id == NavItemId.DASHBOARD) {
                            for (Node node : root.lookupAll(".grid-pane")) {
                                if (node instanceof GridPane grid) {
                                    assertGridChildrenDoNotOverlap(grid, size[0], size[1]);
                                }
                            }
                        }
                    }
                }
            } finally {
                services.shutdown();
                stage.close();
            }
        });
    }

    @Test
    void settingsUpdatesPropagateToDashboardThroughSharedServices() throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            SyncShareServices services = createStubbedServices(true);
            Stage stage = new Stage();
            SyncShareApplication.LoadedView loaded = SyncShareApplication.loadMainView(services);
            try {
                configureTestStage(stage, loaded);
                MainController controller = loaded.controller();
                Parent root = loaded.scene().getRoot();

                assertTrue(controller.navigateTo(NavItemId.SETTINGS).success());
                root.applyCss();
                root.layout();

                ValidatedTextField aliasField = (ValidatedTextField) root.lookup("#settingsAliasField");
                ValidatedTextField portField = (ValidatedTextField) root.lookup("#settingsPortField");
                Button saveBtn = (Button) root.lookup("#settingsSaveButton");
                assertNotNull(aliasField);
                assertNotNull(portField);
                assertNotNull(saveBtn);

                aliasField.setText("Member3-Studio");
                portField.setText("5454");
                saveBtn.fire();

                assertTrue(controller.navigateTo(NavItemId.DASHBOARD).success());
                root.applyCss();
                root.layout();

                Label deviceName = (Label) root.lookup("#deviceNameValueLabel");
                Label devicePort = (Label) root.lookup("#devicePortValueLabel");
                assertEquals("Member3-Studio", deviceName.getText());
                assertTrue(devicePort.getText().contains("5454"));
                assertTrue(controller.getConnectionStatusPill().getStatusHeadline().contains("5454"));

                // Also verify Send Files preflight validation operates cleanly without network IO
                assertTrue(controller.navigateTo(NavItemId.SEND_FILES).success());
                Button validateBtn = (Button) root.lookup("#sendValidateButton");
                Button clearBtn = (Button) root.lookup("#sendClearButton");
                assertNotNull(validateBtn);
                assertNotNull(clearBtn);
                validateBtn.fire();
                clearBtn.fire();
            } finally {
                services.shutdown();
                stage.close();
            }
        });
    }

    @Test
    void uiControllersContainZeroTcpNetworkingImports() throws IOException {
        Path uiSourceRoot = Path.of("src/main/java/com/syncshare/ui");
        assertTrue(Files.isDirectory(uiSourceRoot), "Expected UI source root at " + uiSourceRoot);

        List<String> forbiddenTokens = List.of(
            "java.net.Socket",
            "java.net.ServerSocket",
            "javax.net.ssl",
            "com.syncshare.network."
        );

        try (Stream<Path> paths = Files.walk(uiSourceRoot)) {
            List<Path> javaFiles = paths.filter(p -> p.toString().endsWith(".java")).toList();
            assertFalse(javaFiles.isEmpty());
            for (Path file : javaFiles) {
                String content = Files.readString(file);
                for (String forbidden : forbiddenTokens) {
                    assertFalse(
                        content.contains(forbidden),
                        file + " must not depend on networking API " + forbidden
                    );
                }
            }
        }
    }

    private static void assertGridChildrenDoNotOverlap(GridPane grid, double w, double h) {
        List<Node> children = grid.getChildren();
        for (int i = 0; i < children.size(); i++) {
            Bounds a = children.get(i).localToScene(children.get(i).getBoundsInLocal());
            for (int j = i + 1; j < children.size(); j++) {
                Bounds b = children.get(j).localToScene(children.get(j).getBoundsInLocal());
                boolean separatedX = a.getMaxX() <= b.getMinX() + 1.0 || b.getMaxX() <= a.getMinX() + 1.0;
                boolean separatedY = a.getMaxY() <= b.getMinY() + 1.0 || b.getMaxY() <= a.getMinY() + 1.0;
                assertTrue(
                    separatedX || separatedY,
                    "Grid children overlap in Dashboard at " + w + "x" + h
                );
            }
        }
    }

    private static void configureTestStage(Stage stage, SyncShareApplication.LoadedView loaded) {
        Platform.setImplicitExit(false);
        stage.setTitle(SyncShareApplication.APP_TITLE);
        stage.setMinWidth(SyncShareApplication.MIN_WIDTH);
        stage.setMinHeight(SyncShareApplication.MIN_HEIGHT);
        stage.setScene(loaded.scene());
        stage.show();
    }

    private static SyncShareServices createStubbedServices(boolean tlsConfigured) {
        SettingsService settings = new SettingsService();
        DeviceInfoService deviceInfo = new DeviceInfoService(settings) {
            @Override
            public boolean isTlsConfigured() {
                return tlsConfigured;
            }
        };
        ConnectionService connection = new ConnectionService(
            settings,
            deviceInfo,
            (port, handler) -> () -> {}
        );
        TransferService transfer = new TransferService((host, port, file, listener) -> {
            listener.onStarted(file.getFileName().toString(), 100);
            listener.onProgress(file.getFileName().toString(), 100, 100);
            listener.onCompleted(file.getFileName().toString());
        });
        return new SyncShareServices(settings, deviceInfo, connection, transfer);
    }
}
