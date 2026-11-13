package com.syncshare.ui.controller;

import com.syncshare.model.ConnectionState;
import com.syncshare.model.ConnectionStatus;
import com.syncshare.model.LocalDeviceInfo;
import com.syncshare.model.NavItemId;
import com.syncshare.model.TransferItem;
import com.syncshare.monitoring.MonitoredTransfer;
import com.syncshare.monitoring.TransferMonitorListener;
import com.syncshare.monitoring.TransferMonitorState;
import com.syncshare.service.ConnectionService;
import com.syncshare.service.DeviceInfoService;
import com.syncshare.service.SyncShareServices;
import com.syncshare.service.TransferMonitoringService;
import com.syncshare.ui.design.DesignSystem;
import com.syncshare.ui.design.EmptyStatePane;
import com.syncshare.ui.design.FeedbackBanner;
import com.syncshare.ui.design.LabeledProgressBar;
import com.syncshare.ui.design.SemanticTone;
import com.syncshare.ui.design.StatusBadge;
import com.syncshare.ui.design.UiButtons;
import com.syncshare.ui.design.UiCard;
import com.syncshare.ui.design.UiIcons;
import com.syncshare.ui.navigation.NavigationAware;
import com.syncshare.ui.navigation.ViewNavigator;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * Controller for the primary SyncShare Dashboard screen.
 * Separates presentation from service logic and renders connection status,
 * local device information, quick actions, active transfer summary, and recent transfer summary.
 */
public class DashboardController implements NavigationAware {

    @FXML
    private VBox dashboardRoot;

    private SyncShareServices services;
    private ViewNavigator navigator;

    private GridPane topGrid;
    private GridPane metricsGrid;
    private GridPane deviceInfoGrid;
    private GridPane summariesGrid;

    private FeedbackBanner feedbackBanner;

    // Connection status card controls
    private StatusBadge connectionCardBadge;
    private Label connectionHeadlineLabel;
    private Label connectionDetailLabel;
    private Button receiverToggleButton;
    private Button openReceiveViewButton;

    // Quick actions
    private Button quickSendBtn;
    private Button quickReceiveBtn;
    private Button quickActiveBtn;
    private Button quickHistoryBtn;
    private Button quickSettingsBtn;

    // KPI metric labels
    private Label activeMetricValueLabel;
    private Label activeMetricDetailLabel;
    private StatusBadge activeMetricBadge;

    private Label historyMetricValueLabel;
    private Label historyMetricDetailLabel;
    private StatusBadge historyMetricBadge;

    private Label securityMetricValueLabel;
    private Label securityMetricDetailLabel;
    private StatusBadge securityMetricBadge;

    // Local device information labels
    private Label deviceNameValueLabel;
    private Label deviceIpValueLabel;
    private Label devicePortValueLabel;
    private Label deviceOsValueLabel;
    private Label deviceFolderValueLabel;
    private Label deviceTlsValueLabel;
    private StatusBadge deviceTlsBadge;
    private Button copyIpButton;

    // Active & recent transfer summary bodies
    private StatusBadge activeSummaryBadge;
    private VBox activeSummaryContent;
    private StatusBadge recentSummaryBadge;
    private VBox recentSummaryContent;

    private AutoCloseable monitorSubscription;
    private TransferMonitorListener monitorListener;

    private boolean initializedContext;

    @FXML
    public void initialize() {
        buildLayout();
        configureResponsiveReflow();
    }

    @Override
    public void initializeContext(SyncShareServices services, ViewNavigator navigator) {
        if (this.initializedContext) {
            return;
        }
        this.services = services;
        this.navigator = navigator;
        this.initializedContext = true;

        bindToServices();
        refreshAll();
    }

    @Override
    public void onNavigatedTo() {
        if (initializedContext) {
            services.deviceInfoService().refresh();
            services.connectionService().refreshStatus();
            refreshAll();
        }
    }

    @Override
    public void dispose() {
        if (monitorSubscription != null) {
            try {
                monitorSubscription.close();
            } catch (Exception ignored) {
            }
            monitorSubscription = null;
        }
        if (services != null && monitorListener != null) {
            services.transferMonitoringService().removeListener(monitorListener);
        }
        monitorListener = null;
        initializedContext = false;
    }

    private void buildLayout() {
        if (dashboardRoot == null) {
            return;
        }
        dashboardRoot.getChildren().clear();

        feedbackBanner = new FeedbackBanner(
            SemanticTone.INFO,
            "Dashboard Notice",
            "",
            true
        );
        feedbackBanner.setVisible(false);
        feedbackBanner.setManaged(false);

        topGrid = new GridPane();
        topGrid.setHgap(DesignSystem.SPACE_MD);
        topGrid.setVgap(DesignSystem.SPACE_MD);
        topGrid.getChildren().addAll(buildConnectionStatusCard(), buildQuickActionsCard());

        metricsGrid = new GridPane();
        metricsGrid.setHgap(DesignSystem.SPACE_MD);
        metricsGrid.setVgap(DesignSystem.SPACE_MD);
        metricsGrid.getChildren().addAll(
            buildActiveMetricCard(),
            buildHistoryMetricCard(),
            buildSecurityMetricCard()
        );

        UiCard deviceCard = buildLocalDeviceCard();

        summariesGrid = new GridPane();
        summariesGrid.setHgap(DesignSystem.SPACE_MD);
        summariesGrid.setVgap(DesignSystem.SPACE_MD);
        summariesGrid.getChildren().addAll(
            buildActiveTransferSummaryCard(),
            buildRecentTransferSummaryCard()
        );

        dashboardRoot.getChildren().addAll(
            feedbackBanner,
            topGrid,
            metricsGrid,
            deviceCard,
            summariesGrid
        );

        applyResponsiveGridColumns(dashboardRoot.getWidth());
    }

    private UiCard buildConnectionStatusCard() {
        UiCard card = new UiCard(UiCard.Variant.DEFAULT);
        card.setMinWidth(0);

        connectionCardBadge = new StatusBadge("Standby", SemanticTone.INFO);
        card.setHeader(
            "Connection Status",
            "Peer listener & Mutual TLS 1.3 readiness",
            UiIcons.create(UiIcons.IconType.WIFI, UiIcons.Size.LG, SemanticTone.TEAL),
            connectionCardBadge
        );

        connectionHeadlineLabel = new Label("Standby • Port 5050");
        connectionHeadlineLabel.getStyleClass().add("text-section-title");
        connectionHeadlineLabel.setWrapText(true);

        connectionDetailLabel = new Label("Initializing listener configuration...");
        connectionDetailLabel.getStyleClass().add("text-secondary");
        connectionDetailLabel.setWrapText(true);

        receiverToggleButton = UiButtons.create(
            "Start Receiver",
            UiIcons.create(UiIcons.IconType.RECEIVE, UiIcons.Size.SM),
            UiButtons.Variant.PRIMARY,
            UiButtons.Size.SM
        );
        receiverToggleButton.setOnAction(event -> handleToggleReceiver());

        openReceiveViewButton = UiButtons.create(
            "Receive Queue",
            UiButtons.Variant.SECONDARY,
            UiButtons.Size.SM
        );
        openReceiveViewButton.setOnAction(event -> navigateTo(NavItemId.RECEIVE_FILES));

        FlowPane footerActions = new FlowPane(DesignSystem.SPACE_XS, DesignSystem.SPACE_XS);
        footerActions.setAlignment(Pos.CENTER_RIGHT);
        footerActions.getChildren().addAll(openReceiveViewButton, receiverToggleButton);

        card.setBodyContent(connectionHeadlineLabel, connectionDetailLabel);
        card.setFooterContent(footerActions);
        return card;
    }

    private UiCard buildQuickActionsCard() {
        UiCard card = new UiCard(UiCard.Variant.DEFAULT);
        card.setMinWidth(0);
        card.setHeader(
            "Quick Actions",
            "Direct shortcuts to core SyncShare workflows",
            UiIcons.create(UiIcons.IconType.SHIELD_CHECK, UiIcons.Size.LG, SemanticTone.TEAL),
            null
        );

        quickSendBtn = UiButtons.create(
            "Send Files",
            UiIcons.create(UiIcons.IconType.SEND, UiIcons.Size.SM),
            UiButtons.Variant.PRIMARY,
            UiButtons.Size.MD
        );
        quickSendBtn.setId("dashboardQuickSendButton");
        quickSendBtn.setOnAction(event -> navigateTo(NavItemId.SEND_FILES));

        quickReceiveBtn = UiButtons.create(
            "Receive Files",
            UiIcons.create(UiIcons.IconType.RECEIVE, UiIcons.Size.SM),
            UiButtons.Variant.OUTLINE_TEAL,
            UiButtons.Size.MD
        );
        quickReceiveBtn.setId("dashboardQuickReceiveButton");
        quickReceiveBtn.setOnAction(event -> navigateTo(NavItemId.RECEIVE_FILES));

        quickActiveBtn = UiButtons.create(
            "Active Transfers",
            UiIcons.create(UiIcons.IconType.ACTIVITY, UiIcons.Size.SM),
            UiButtons.Variant.SECONDARY,
            UiButtons.Size.MD
        );
        quickActiveBtn.setId("dashboardQuickActiveButton");
        quickActiveBtn.setOnAction(event -> navigateTo(NavItemId.ACTIVE_TRANSFERS));

        quickHistoryBtn = UiButtons.create(
            "Transfer History",
            UiIcons.create(UiIcons.IconType.CLOCK, UiIcons.Size.SM),
            UiButtons.Variant.SECONDARY,
            UiButtons.Size.MD
        );
        quickHistoryBtn.setId("dashboardQuickHistoryButton");
        quickHistoryBtn.setOnAction(event -> navigateTo(NavItemId.TRANSFER_HISTORY));

        quickSettingsBtn = UiButtons.create(
            "Settings",
            UiIcons.create(UiIcons.IconType.SETTINGS, UiIcons.Size.SM),
            UiButtons.Variant.SECONDARY,
            UiButtons.Size.MD
        );
        quickSettingsBtn.setId("dashboardQuickSettingsButton");
        quickSettingsBtn.setOnAction(event -> navigateTo(NavItemId.SETTINGS));

        FlowPane quickActionsFlow = new FlowPane(DesignSystem.SPACE_XS, DesignSystem.SPACE_XS);
        quickActionsFlow.getStyleClass().add("quick-actions-flow");
        quickActionsFlow.getChildren().addAll(
            quickSendBtn,
            quickReceiveBtn,
            quickActiveBtn,
            quickHistoryBtn,
            quickSettingsBtn
        );

        Label hint = new Label("All screens share live transfer, device, and connection services.");
        hint.getStyleClass().add("text-caption");
        hint.setWrapText(true);

        card.setBodyContent(quickActionsFlow, hint);
        return card;
    }

    private UiCard buildActiveMetricCard() {
        activeMetricBadge = new StatusBadge("Idle", SemanticTone.NEUTRAL);
        activeMetricBadge.setCompact(true);
        activeMetricValueLabel = new Label("0 Active");
        activeMetricValueLabel.getStyleClass().add("text-metric");
        activeMetricDetailLabel = new Label("No transfers currently streaming");
        activeMetricDetailLabel.getStyleClass().add("text-caption");
        activeMetricDetailLabel.setWrapText(true);
        return buildMetricShell(
            "ACTIVE TRANSFERS",
            UiIcons.IconType.ACTIVITY,
            SemanticTone.TEAL,
            activeMetricBadge,
            activeMetricValueLabel,
            activeMetricDetailLabel
        );
    }

    private UiCard buildHistoryMetricCard() {
        historyMetricBadge = new StatusBadge("Empty", SemanticTone.NEUTRAL);
        historyMetricBadge.setCompact(true);
        historyMetricValueLabel = new Label("0 Completed");
        historyMetricValueLabel.getStyleClass().add("text-metric");
        historyMetricDetailLabel = new Label("0 B verified over SHA-256");
        historyMetricDetailLabel.getStyleClass().add("text-caption");
        historyMetricDetailLabel.setWrapText(true);
        return buildMetricShell(
            "RECENT HISTORY",
            UiIcons.IconType.CHECK_CIRCLE,
            SemanticTone.SUCCESS,
            historyMetricBadge,
            historyMetricValueLabel,
            historyMetricDetailLabel
        );
    }

    private UiCard buildSecurityMetricCard() {
        securityMetricBadge = new StatusBadge("mTLS 1.3", SemanticTone.INFO);
        securityMetricBadge.setCompact(true);
        securityMetricValueLabel = new Label("TCP Port 5050");
        securityMetricValueLabel.getStyleClass().add("text-metric");
        securityMetricDetailLabel = new Label("SHA-256 chunk integrity enabled");
        securityMetricDetailLabel.getStyleClass().add("text-caption");
        securityMetricDetailLabel.setWrapText(true);
        return buildMetricShell(
            "SECURITY & PORT",
            UiIcons.IconType.LOCK,
            SemanticTone.INFO,
            securityMetricBadge,
            securityMetricValueLabel,
            securityMetricDetailLabel
        );
    }

    private UiCard buildMetricShell(
        String eyebrowText,
        UiIcons.IconType iconType,
        SemanticTone iconTone,
        StatusBadge badge,
        Label valueLabel,
        Label detailLabel
    ) {
        UiCard card = new UiCard(UiCard.Variant.DEFAULT).setCompact(true);
        card.setMinWidth(0);
        card.setSpacing(DesignSystem.SPACE_XS);

        HBox topRow = new HBox(DesignSystem.SPACE_XS);
        topRow.setAlignment(Pos.CENTER_LEFT);
        topRow.getChildren().add(UiIcons.create(iconType, UiIcons.Size.SM, iconTone));

        Label eyebrow = new Label(eyebrowText);
        eyebrow.getStyleClass().add("text-eyebrow");
        eyebrow.setMinWidth(0);
        HBox.setHgrow(eyebrow, Priority.ALWAYS);
        topRow.getChildren().addAll(eyebrow, badge);

        card.getChildren().setAll(topRow, valueLabel, detailLabel);
        return card;
    }

    private UiCard buildLocalDeviceCard() {
        UiCard card = new UiCard(UiCard.Variant.DEFAULT);
        card.setMinWidth(0);

        deviceTlsBadge = new StatusBadge("Checking TLS", SemanticTone.NEUTRAL);
        Button refreshDeviceBtn = UiButtons.create(
            "Refresh",
            UiIcons.create(UiIcons.IconType.REFRESH, UiIcons.Size.SM),
            UiButtons.Variant.GHOST,
            UiButtons.Size.SM
        );
        refreshDeviceBtn.setOnAction(event -> {
            if (services != null) {
                services.deviceInfoService().refresh();
                showBanner(SemanticTone.INFO, "Device Info Refreshed", "Local network interface and TLS metadata updated.");
            }
        });

        HBox headerTrailing = new HBox(DesignSystem.SPACE_XS, deviceTlsBadge, refreshDeviceBtn);
        headerTrailing.setAlignment(Pos.CENTER_RIGHT);

        card.setHeader(
            "Local Device Information",
            "Host identity, LAN address, listening port, and receive storage",
            UiIcons.create(UiIcons.IconType.DEVICE, UiIcons.Size.LG, SemanticTone.TEAL),
            headerTrailing
        );

        deviceInfoGrid = new GridPane();
        deviceInfoGrid.setHgap(DesignSystem.SPACE_SM);
        deviceInfoGrid.setVgap(DesignSystem.SPACE_SM);

        deviceNameValueLabel = new Label("SyncShare-Workstation");
        deviceNameValueLabel.setId("deviceNameValueLabel");
        deviceIpValueLabel = new Label("127.0.0.1");
        deviceIpValueLabel.setId("deviceIpValueLabel");
        devicePortValueLabel = new Label("5050 (TCP / mTLS)");
        devicePortValueLabel.setId("devicePortValueLabel");
        deviceOsValueLabel = new Label("Desktop OS");
        deviceOsValueLabel.setId("deviceOsValueLabel");
        deviceFolderValueLabel = new Label("received_files");
        deviceFolderValueLabel.setId("deviceFolderValueLabel");
        deviceTlsValueLabel = new Label("Checking...");
        deviceTlsValueLabel.setId("deviceTlsValueLabel");

        copyIpButton = UiButtons.create(
            "Copy",
            UiIcons.create(UiIcons.IconType.COPY, UiIcons.Size.SM),
            UiButtons.Variant.OUTLINE_TEAL,
            UiButtons.Size.SM
        );
        copyIpButton.setAccessibleText("Copy local IP address");
        copyIpButton.setTooltip(new Tooltip("Copy local IP address"));
        copyIpButton.setOnAction(event -> copyLocalIpToClipboard());

        VBox nameCell = createDeviceInfoCell("DEVICE NAME", deviceNameValueLabel, false, null);
        VBox ipCell = createDeviceInfoCell("LOCAL IPv4 ADDRESS", deviceIpValueLabel, true, copyIpButton);
        VBox portCell = createDeviceInfoCell("LISTENING PORT", devicePortValueLabel, true, null);
        VBox folderCell = createDeviceInfoCell("RECEIVE DIRECTORY", deviceFolderValueLabel, true, null);
        VBox osCell = createDeviceInfoCell("OPERATING SYSTEM", deviceOsValueLabel, false, null);
        VBox tlsCell = createDeviceInfoCell("SECURITY PROFILE", deviceTlsValueLabel, false, null);

        deviceInfoGrid.getChildren().addAll(nameCell, ipCell, portCell, folderCell, osCell, tlsCell);
        card.setBodyContent(deviceInfoGrid);
        return card;
    }

    private VBox createDeviceInfoCell(String key, Label valueLabel, boolean mono, Node trailingAction) {
        VBox cell = new VBox(4);
        cell.getStyleClass().add("device-info-item");
        cell.setMinWidth(0);

        Label keyLabel = new Label(key);
        keyLabel.getStyleClass().add("device-info-key");

        valueLabel.getStyleClass().add(mono ? "device-info-mono" : "device-info-value");
        valueLabel.setWrapText(true);
        valueLabel.setMinWidth(0);

        if (trailingAction != null) {
            HBox row = new HBox(DesignSystem.SPACE_XS, valueLabel, trailingAction);
            row.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(valueLabel, Priority.ALWAYS);
            cell.getChildren().addAll(keyLabel, row);
        } else {
            cell.getChildren().addAll(keyLabel, valueLabel);
        }
        return cell;
    }

    private UiCard buildActiveTransferSummaryCard() {
        UiCard card = new UiCard(UiCard.Variant.DEFAULT);
        card.setMinWidth(0);

        activeSummaryBadge = new StatusBadge("0 Active", SemanticTone.NEUTRAL);
        activeSummaryBadge.setCompact(true);

        Button viewAllBtn = UiButtons.create("View All", UiButtons.Variant.GHOST, UiButtons.Size.SM);
        viewAllBtn.setOnAction(event -> navigateTo(NavItemId.ACTIVE_TRANSFERS));

        HBox trailing = new HBox(DesignSystem.SPACE_XS, activeSummaryBadge, viewAllBtn);
        trailing.setAlignment(Pos.CENTER_RIGHT);

        card.setHeader(
            "Active Transfer Summary",
            "Real-time chunk progress for live transfers",
            UiIcons.create(UiIcons.IconType.ACTIVITY, UiIcons.Size.LG, SemanticTone.TEAL),
            trailing
        );

        activeSummaryContent = new VBox(DesignSystem.SPACE_XS);
        activeSummaryContent.setId("activeSummaryContent");
        activeSummaryContent.setMinWidth(0);
        card.setBodyContent(activeSummaryContent);
        return card;
    }

    private UiCard buildRecentTransferSummaryCard() {
        UiCard card = new UiCard(UiCard.Variant.DEFAULT);
        card.setMinWidth(0);

        recentSummaryBadge = new StatusBadge("0 Items", SemanticTone.NEUTRAL);
        recentSummaryBadge.setCompact(true);

        Button viewHistoryBtn = UiButtons.create("View History", UiButtons.Variant.GHOST, UiButtons.Size.SM);
        viewHistoryBtn.setOnAction(event -> navigateTo(NavItemId.TRANSFER_HISTORY));

        HBox trailing = new HBox(DesignSystem.SPACE_XS, recentSummaryBadge, viewHistoryBtn);
        trailing.setAlignment(Pos.CENTER_RIGHT);

        card.setHeader(
            "Recent Transfer Summary",
            "Latest completed, verified, and interrupted items",
            UiIcons.create(UiIcons.IconType.CLOCK, UiIcons.Size.LG, SemanticTone.TEAL),
            trailing
        );

        recentSummaryContent = new VBox(DesignSystem.SPACE_XS);
        recentSummaryContent.setId("recentSummaryContent");
        recentSummaryContent.setMinWidth(0);
        card.setBodyContent(recentSummaryContent);
        return card;
    }

    private void bindToServices() {
        DeviceInfoService deviceService = services.deviceInfoService();
        ConnectionService connectionService = services.connectionService();
        TransferMonitoringService monitoringService = services.transferMonitoringService();

        deviceService.deviceInfoProperty().addListener((obs, oldVal, newVal) -> updateDeviceInfo(newVal));
        connectionService.statusProperty().addListener((obs, oldVal, newVal) -> updateConnectionStatus(newVal));
        if (services.discoveryManager() != null) {
            services.discoveryManager().getDiscoveredPeers().addListener(
                (javafx.collections.ListChangeListener<com.syncshare.discovery.PeerInfo>) change ->
                    updateConnectionStatus(connectionService.getStatus())
            );
        }
        monitorListener = (active, recent) -> updateTransferSummaries(active, recent);
        monitorSubscription = monitoringService.subscribe(monitorListener);
    }

    private void refreshAll() {
        if (services == null) {
            return;
        }
        updateDeviceInfo(services.deviceInfoService().getDeviceInfo());
        updateConnectionStatus(services.connectionService().getStatus());
        TransferMonitoringService monitoringService = services.transferMonitoringService();
        updateTransferSummaries(monitoringService.getActiveTransfers(), monitoringService.getRecentTransfers());
    }

    private void updateConnectionStatus(ConnectionStatus status) {
        if (status == null) {
            return;
        }
        ConnectionState state = status.state();
        connectionCardBadge.setText(state.displayName());
        connectionCardBadge.setTone(state.tone());
        connectionHeadlineLabel.setText(status.headline());
        connectionDetailLabel.setText(status.detail());

        boolean listening = services != null && services.connectionService().isListening();
        receiverToggleButton.setText(listening ? "Stop Receiver" : "Start Receiver");
        UiButtons.apply(
            receiverToggleButton,
            listening ? UiButtons.Variant.DANGER_OUTLINE : UiButtons.Variant.PRIMARY,
            UiButtons.Size.SM
        );

        int peerCount = (services != null && services.discoveryManager() != null)
            ? services.discoveryManager().getDiscoveredPeers().size()
            : 0;
        String peerSummary = peerCount > 0 ? " • " + peerCount + " peer(s) discovered (UDP)" : "";
        securityMetricValueLabel.setText("TCP Port " + status.listenPort());
        securityMetricBadge.setText(status.tlsConfigured() ? "mTLS Ready" : "TLS Setup");
        securityMetricBadge.setTone(status.tlsConfigured() ? SemanticTone.SUCCESS : SemanticTone.WARNING);
        securityMetricDetailLabel.setText(
            status.tlsConfigured()
                ? "Mutual TLS 1.3 certificates verified" + peerSummary
                : "Set SYNCSHARE_TLS_PASSWORD & peer keystores"
        );
    }

    private void updateDeviceInfo(LocalDeviceInfo info) {
        if (info == null) {
            return;
        }
        deviceNameValueLabel.setText(info.deviceName());
        deviceIpValueLabel.setText(info.ipAddress());
        devicePortValueLabel.setText(info.listeningPort() + " (TCP / mTLS 1.3)");
        deviceOsValueLabel.setText(info.operatingSystem());
        deviceFolderValueLabel.setText(info.receiveDirectory());
        deviceTlsValueLabel.setText(info.tlsSummary());
        deviceTlsBadge.setText(info.tlsConfigured() ? "mTLS Ready" : "Setup Needed");
        deviceTlsBadge.setTone(info.tlsConfigured() ? SemanticTone.SUCCESS : SemanticTone.WARNING);
    }

    private void updateTransferSummaries(List<MonitoredTransfer> active, List<MonitoredTransfer> recent) {
        if (services == null) {
            return;
        }
        TransferMonitoringService monitoringService = services.transferMonitoringService();
        List<MonitoredTransfer> safeActive = active != null ? active : monitoringService.getActiveTransfers();
        List<MonitoredTransfer> safeRecent = recent != null ? recent : monitoringService.getRecentTransfers();

        int activeCount = safeActive.size();
        activeMetricValueLabel.setText(activeCount + " Active");
        activeMetricBadge.setText(activeCount > 0 ? "Streaming" : "Idle");
        activeMetricBadge.setTone(activeCount > 0 ? SemanticTone.TEAL : SemanticTone.NEUTRAL);
        if (activeCount > 0) {
            double sum = 0.0;
            int counted = 0;
            for (MonitoredTransfer item : safeActive) {
                if (item.progress() >= 0.0) {
                    sum += item.progress();
                    counted++;
                }
            }
            int pct = counted > 0 ? (int) Math.round((sum / counted) * 100.0) : 0;
            activeMetricDetailLabel.setText("Average progress " + pct + "% across active queue");
        } else {
            activeMetricDetailLabel.setText("No transfers currently streaming");
        }

        int completedCount = monitoringService.completedCountProperty().get();
        int failedCount = monitoringService.failedCountProperty().get();
        long totalBytes = monitoringService.totalTransferredBytesProperty().get();
        historyMetricValueLabel.setText(completedCount + " Verified");
        historyMetricBadge.setText(safeRecent.isEmpty() ? "Empty" : safeRecent.size() + " Total");
        historyMetricBadge.setTone(
            failedCount > 0 ? SemanticTone.WARNING : (completedCount > 0 ? SemanticTone.SUCCESS : SemanticTone.NEUTRAL)
        );
        historyMetricDetailLabel.setText(
            TransferItem.formatByteCount(totalBytes) + " processed • " + failedCount + " failed"
        );

        renderActiveSummaryContent(safeActive);
        renderRecentSummaryContent(safeRecent);
    }

    private void renderActiveSummaryContent(List<MonitoredTransfer> active) {
        activeSummaryBadge.setText(active.size() + " Active");
        activeSummaryBadge.setTone(active.isEmpty() ? SemanticTone.NEUTRAL : SemanticTone.TEAL);

        if (active.isEmpty()) {
            EmptyStatePane empty = new EmptyStatePane(
                UiIcons.IconType.ACTIVITY,
                SemanticTone.TEAL,
                "No active transfers",
                "Send a file to a peer or start the receiver to monitor live chunk progress here."
            ).setCompact(true)
             .setPrimaryAction("Send Files", UiIcons.IconType.SEND, () -> navigateTo(NavItemId.SEND_FILES));
            activeSummaryContent.getChildren().setAll(empty);
            return;
        }

        activeSummaryContent.getChildren().clear();
        int limit = Math.min(4, active.size());
        for (int i = 0; i < limit; i++) {
            MonitoredTransfer item = active.get(i);
            activeSummaryContent.getChildren().add(createActiveSummaryRow(item));
        }
    }

    private void renderRecentSummaryContent(List<MonitoredTransfer> history) {
        recentSummaryBadge.setText(history.size() + (history.size() == 1 ? " Item" : " Items"));
        recentSummaryBadge.setTone(history.isEmpty() ? SemanticTone.NEUTRAL : SemanticTone.INFO);

        if (history.isEmpty()) {
            EmptyStatePane empty = new EmptyStatePane(
                UiIcons.IconType.CLOCK,
                SemanticTone.INFO,
                "No recent transfers yet",
                "Completed, verified, and interrupted transfers will appear here with SHA-256 status."
            ).setCompact(true)
             .setPrimaryAction("Open History", UiIcons.IconType.CLOCK, () -> navigateTo(NavItemId.TRANSFER_HISTORY));
            recentSummaryContent.getChildren().setAll(empty);
            return;
        }

        recentSummaryContent.getChildren().clear();
        int limit = Math.min(5, history.size());
        for (int i = 0; i < limit; i++) {
            MonitoredTransfer item = history.get(i);
            recentSummaryContent.getChildren().add(createRecentSummaryRow(item));
        }
    }

    private VBox createActiveSummaryRow(MonitoredTransfer item) {
        VBox row = new VBox(6);
        row.getStyleClass().add("transfer-summary-row");

        HBox top = new HBox(DesignSystem.SPACE_XS);
        top.setAlignment(Pos.CENTER_LEFT);

        Label title = new Label(item.fileName());
        title.getStyleClass().add("transfer-summary-title");
        title.setWrapText(true);
        title.setMinWidth(0);
        HBox.setHgrow(title, Priority.ALWAYS);

        StatusBadge badge = new StatusBadge(item.state().displayName(), item.state().tone());
        badge.setCompact(true);
        top.getChildren().addAll(title, badge);

        String detail = item.formattedTransferred()
            + " • " + item.formattedSpeed()
            + " • ETA " + item.formattedEta();
        LabeledProgressBar progressBar = new LabeledProgressBar(
            item.direction().label() + " • " + item.peerAddress(),
            item.progress(),
            item.state().tone()
        ).setSize(LabeledProgressBar.Size.SM)
         .setDetail(detail);

        row.getChildren().addAll(top, progressBar);
        return row;
    }

    private HBox createRecentSummaryRow(MonitoredTransfer item) {
        HBox row = new HBox(DesignSystem.SPACE_SM);
        row.getStyleClass().add("transfer-summary-row");
        row.setAlignment(Pos.CENTER_LEFT);

        VBox info = new VBox(2);
        info.setMinWidth(0);
        HBox.setHgrow(info, Priority.ALWAYS);

        Label title = new Label(item.fileName());
        title.getStyleClass().add("transfer-summary-title");
        title.setWrapText(true);

        String timestamp = item.completedAt() != null ? item.formattedCompletedAt() : item.formattedStartedAt();
        String diagnostic = item.state() == TransferMonitorState.FAILED && !item.errorMessage().isBlank()
            ? " • " + item.errorMessage()
            : "";
        Label meta = new Label(
            item.direction().label() + " • " + item.peerAddress()
                + " • " + item.formattedTotalSize() + " • " + timestamp + diagnostic
        );
        meta.getStyleClass().add("transfer-summary-meta");
        meta.setWrapText(true);
        info.getChildren().addAll(title, meta);

        StatusBadge badge = new StatusBadge(item.state().displayName(), item.state().tone(), StatusBadge.IndicatorMode.ICON);
        badge.setCompact(true);

        row.getChildren().addAll(info, badge);
        return row;
    }

    private void handleToggleReceiver() {
        if (services == null) {
            return;
        }
        ConnectionService connection = services.connectionService();
        if (connection.isListening()) {
            connection.stopReceiver();
            showBanner(SemanticTone.INFO, "Receiver Stopped", "Background peer listener returned to standby.");
        } else {
            boolean started = connection.startReceiver();
            if (started) {
                showBanner(
                    SemanticTone.SUCCESS,
                    "Receiver Listening",
                    "Accepting mTLS 1.3 file offers on port " + services.settingsService().getSettings().listeningPort() + "."
                );
            } else {
                ConnectionStatus current = connection.getStatus();
                showBanner(
                    current.state().tone(),
                    current.headline(),
                    current.detail()
                );
            }
        }
    }

    private void copyLocalIpToClipboard() {
        if (services == null || services.deviceInfoService().getDeviceInfo() == null) {
            return;
        }
        String ip = services.deviceInfoService().getDeviceInfo().ipAddress();
        ClipboardContent content = new ClipboardContent();
        content.putString(ip);
        Clipboard.getSystemClipboard().setContent(content);
        showBanner(SemanticTone.SUCCESS, "IP Copied", "Local peer IPv4 address (" + ip + ") copied to clipboard.");
    }

    private void showBanner(SemanticTone tone, String title, String message) {
        feedbackBanner.setTone(tone).setTitle(title).setMessage(message);
        feedbackBanner.show();
    }

    private void navigateTo(NavItemId id) {
        if (navigator != null) {
            navigator.navigateTo(id);
        }
    }

    private void configureResponsiveReflow() {
        if (dashboardRoot != null) {
            dashboardRoot.widthProperty().addListener((obs, oldWidth, newWidth) ->
                applyResponsiveGridColumns(newWidth.doubleValue())
            );
        }
    }

    private void applyResponsiveGridColumns(double availableWidth) {
        double width = availableWidth > 0 ? availableWidth : 780;
        if (topGrid != null) {
            reflowGrid(topGrid, width >= 600 ? 2 : 1);
        }
        if (metricsGrid != null) {
            int metricCols = width >= 660 ? 3 : (width >= 440 ? 2 : 1);
            reflowGrid(metricsGrid, metricCols);
        }
        if (deviceInfoGrid != null) {
            int deviceCols = width >= 740 ? 3 : (width >= 440 ? 2 : 1);
            reflowGrid(deviceInfoGrid, deviceCols);
        }
        if (summariesGrid != null) {
            reflowGrid(summariesGrid, width >= 620 ? 2 : 1);
        }
    }

    private static void reflowGrid(GridPane grid, int columns) {
        grid.getColumnConstraints().clear();
        for (int i = 0; i < columns; i++) {
            ColumnConstraints cc = new ColumnConstraints();
            cc.setPercentWidth(100.0 / columns);
            cc.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().add(cc);
        }
        List<Node> children = List.copyOf(grid.getChildren());
        for (int idx = 0; idx < children.size(); idx++) {
            Node child = children.get(idx);
            GridPane.setColumnIndex(child, idx % columns);
            GridPane.setRowIndex(child, idx / columns);
            GridPane.setHgrow(child, Priority.ALWAYS);
            if (columns == 2 && children.size() == 3 && idx == 2) {
                GridPane.setColumnSpan(child, 2);
            } else {
                GridPane.setColumnSpan(child, 1);
            }
        }
    }
}
