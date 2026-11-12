package com.syncshare.ui.controller;

import com.syncshare.model.NavItemId;
import com.syncshare.monitoring.MonitoredTransfer;
import com.syncshare.monitoring.TransferMetricsCalculator;
import com.syncshare.monitoring.TransferMonitorListener;
import com.syncshare.monitoring.TransferMonitorState;
import com.syncshare.service.SyncShareServices;
import com.syncshare.service.TransferMonitoringService;
import com.syncshare.ui.design.DesignSystem;
import com.syncshare.ui.design.EmptyStatePane;
import com.syncshare.ui.design.FeedbackBanner;
import com.syncshare.ui.design.SemanticTone;
import com.syncshare.ui.design.StatusBadge;
import com.syncshare.ui.design.UiButtons;
import com.syncshare.ui.design.UiCard;
import com.syncshare.ui.design.UiIcons;
import com.syncshare.ui.navigation.NavigationAware;
import com.syncshare.ui.navigation.ViewNavigator;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.value.ChangeListener;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Controller for the real-time Active Transfers monitoring view.
 * Subscribes only to {@link TransferMonitoringService} via the service layer
 * and never performs socket communication on the JavaFX Application Thread.
 */
public class ActiveTransfersController implements NavigationAware {

    @FXML
    private VBox activeTransfersRoot;

    private SyncShareServices services;
    private ViewNavigator navigator;

    private FeedbackBanner errorBanner;
    private TextField searchField;
    private StatusBadge countBadge;
    private StatusBadge recentSummaryBadge;
    private Button clearHistoryButton;
    private TableView<MonitoredTransfer> activeTable;
    private TableView<MonitoredTransfer> recentTable;
    private FilteredList<MonitoredTransfer> filteredActive;
    private FilteredList<MonitoredTransfer> filteredRecent;

    private TransferMonitorListener monitorListener;
    private AutoCloseable monitorSubscription;
    private ChangeListener<String> searchListener;
    private final Set<String> acknowledgedFailureIds = new HashSet<>();
    private final Set<String> activeFailureBannerIds = new HashSet<>();

    private boolean initializedContext;

    @FXML
    public void initialize() {
        buildLayout();
    }

    @Override
    public void initializeContext(SyncShareServices services, ViewNavigator navigator) {
        if (initializedContext) {
            return;
        }
        this.services = services;
        this.navigator = navigator;
        this.initializedContext = true;

        TransferMonitoringService monitoring = services.transferMonitoringService();
        filteredActive = new FilteredList<>(monitoring.getActiveTransfers(), item -> true);
        filteredRecent = new FilteredList<>(monitoring.getRecentTransfers(), item -> true);
        activeTable.setItems(filteredActive);
        recentTable.setItems(filteredRecent);

        monitorListener = this::handleTransfersUpdated;
        monitorSubscription = monitoring.subscribe(monitorListener);

        applyFilter();
        handleTransfersUpdated(monitoring.getActiveTransfers(), monitoring.getRecentTransfers());
    }

    @Override
    public void onNavigatedTo() {
        if (initializedContext && services != null) {
            TransferMonitoringService monitoring = services.transferMonitoringService();
            handleTransfersUpdated(monitoring.getActiveTransfers(), monitoring.getRecentTransfers());
        }
    }

    /**
     * Releases monitoring subscriptions and UI listeners when the view is torn down.
     */
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
        if (searchField != null && searchListener != null) {
            searchField.textProperty().removeListener(searchListener);
            searchListener = null;
        }
        initializedContext = false;
    }

    private void buildLayout() {
        if (activeTransfersRoot == null) {
            return;
        }
        activeTransfersRoot.getChildren().clear();

        errorBanner = new FeedbackBanner(SemanticTone.ERROR, "", "", true);
        errorBanner.setOnDismiss(() -> acknowledgedFailureIds.addAll(activeFailureBannerIds));
        errorBanner.setVisible(false);
        errorBanner.setManaged(false);

        UiCard activeCard = buildActiveCard();
        UiCard recentCard = buildRecentCard();

        activeTransfersRoot.getChildren().addAll(errorBanner, activeCard, recentCard);
    }

    private UiCard buildActiveCard() {
        UiCard card = new UiCard(UiCard.Variant.DEFAULT);
        card.setMinWidth(0);

        countBadge = new StatusBadge("0 Active", SemanticTone.NEUTRAL);
        countBadge.setCompact(true);

        Button sendBtn = UiButtons.create(
            "Send Files",
            UiIcons.create(UiIcons.IconType.SEND, UiIcons.Size.SM),
            UiButtons.Variant.PRIMARY,
            UiButtons.Size.SM
        );
        sendBtn.setOnAction(event -> {
            if (navigator != null) {
                navigator.navigateTo(NavItemId.SEND_FILES);
            }
        });

        HBox trailing = new HBox(DesignSystem.SPACE_XS, countBadge, sendBtn);
        trailing.setAlignment(Pos.CENTER_RIGHT);

        card.setHeader(
            "Active Transfers",
            "Real-time throughput, ETA, and status from verified encrypted streams",
            UiIcons.create(UiIcons.IconType.ACTIVITY, UiIcons.Size.LG, SemanticTone.TEAL),
            trailing
        );

        searchField = new TextField();
        searchField.setPromptText("Filter transfers by file, peer, direction, or status...");
        searchField.setAccessibleText("Filter transfers");
        searchField.getStyleClass().addAll("input-field", "search-field");
        searchListener = (obs, oldVal, newVal) -> applyFilter();
        searchField.textProperty().addListener(searchListener);

        activeTable = createActiveTable();
        card.setBodyContent(searchField, activeTable);
        return card;
    }

    private UiCard buildRecentCard() {
        UiCard card = new UiCard(UiCard.Variant.MUTED);
        card.setMinWidth(0);

        recentSummaryBadge = new StatusBadge("0 Logged", SemanticTone.NEUTRAL);
        recentSummaryBadge.setCompact(true);

        clearHistoryButton = UiButtons.create(
            "Clear",
            UiButtons.Variant.GHOST,
            UiButtons.Size.SM
        );
        clearHistoryButton.setDisable(true);
        clearHistoryButton.setOnAction(event -> {
            if (services != null) {
                acknowledgedFailureIds.addAll(activeFailureBannerIds);
                activeFailureBannerIds.clear();
                hideErrorBanner();
                services.transferMonitoringService().clearCompletedHistory();
            }
        });

        HBox trailing = new HBox(DesignSystem.SPACE_XS, recentSummaryBadge, clearHistoryButton);
        trailing.setAlignment(Pos.CENTER_RIGHT);

        card.setHeader(
            "Recent Outcomes",
            "Completed, failed, and cancelled transfers with exact timestamps and diagnostics",
            UiIcons.create(UiIcons.IconType.CLOCK, UiIcons.Size.LG, SemanticTone.INFO),
            trailing
        );

        recentTable = createRecentTable();
        card.setBodyContent(recentTable);
        return card;
    }

    private TableView<MonitoredTransfer> createActiveTable() {
        TableView<MonitoredTransfer> table = new TableView<>();
        table.getStyleClass().addAll("ss-table", "ss-table-monitoring");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPrefHeight(280);
        table.setMinHeight(190);

        EmptyStatePane placeholder = new EmptyStatePane(
            UiIcons.IconType.ACTIVITY,
            SemanticTone.TEAL,
            "No active transfers",
            "Outgoing and incoming mTLS transfers will stream real progress, speed, and ETA here."
        ).setCompact(true)
         .setPrimaryAction("Send Files", UiIcons.IconType.SEND, () -> {
             if (navigator != null) {
                 navigator.navigateTo(NavItemId.SEND_FILES);
             }
         });
        table.setPlaceholder(placeholder);

        // Sum of column minWidths <= 410 px so 800x560 breakpoint never overflows horizontally
        TableColumn<MonitoredTransfer, MonitoredTransfer> fileCol = new TableColumn<>("FILE & PEER");
        fileCol.setMinWidth(86);
        fileCol.setPrefWidth(148);
        fileCol.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue()));
        fileCol.setCellFactory(col -> new TwoLineCell(row -> row.fileName(), row ->
            row.direction().label() + " • " + row.peerAddress()
        , false));

        TableColumn<MonitoredTransfer, MonitoredTransfer> sizeCol = new TableColumn<>("BYTES");
        sizeCol.setMinWidth(62);
        sizeCol.setPrefWidth(88);
        sizeCol.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue()));
        sizeCol.setCellFactory(col -> new TwoLineCell(
            row -> TransferMetricsCalculator.formatSize(row.transferredBytes()),
            row -> "of " + row.formattedTotalSize(),
            true
        ));

        TableColumn<MonitoredTransfer, MonitoredTransfer> progressCol = new TableColumn<>("PROGRESS");
        progressCol.setMinWidth(74);
        progressCol.setPrefWidth(104);
        progressCol.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue()));
        progressCol.setCellFactory(col -> new TableCell<>() {
            private final ProgressBar bar = new ProgressBar();
            private final Label pct = new Label();
            private final HBox box = new HBox(DesignSystem.SPACE_XS, bar, pct);

            {
                box.setAlignment(Pos.CENTER_LEFT);
                bar.getStyleClass().addAll("ss-progress", "progress-sm");
                bar.setMinWidth(32);
                bar.setPrefWidth(54);
                HBox.setHgrow(bar, Priority.ALWAYS);
                pct.getStyleClass().add("text-mono-sm");
                pct.setMinWidth(Region.USE_PREF_SIZE);
            }

            @Override
            protected void updateItem(MonitoredTransfer row, boolean empty) {
                super.updateItem(row, empty);
                if (empty || row == null) {
                    setGraphic(null);
                    setTooltip(null);
                } else {
                    bar.setProgress(row.progress());
                    String toneClass = row.state().tone() == SemanticTone.TEAL
                        ? null
                        : row.state().tone().progressStyleClass();
                    DesignSystem.swapExclusiveClass(bar, SemanticTone.allProgressClasses(), toneClass);
                    pct.setText(row.formattedPercentage());
                    setTooltip(new Tooltip(row.formattedTransferred() + " (" + row.formattedPercentage() + ")"));
                    setGraphic(box);
                }
            }
        });

        TableColumn<MonitoredTransfer, MonitoredTransfer> speedEtaCol = new TableColumn<>("SPEED / ETA");
        speedEtaCol.setMinWidth(62);
        speedEtaCol.setPrefWidth(88);
        speedEtaCol.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue()));
        speedEtaCol.setCellFactory(col -> new TwoLineCell(
            MonitoredTransfer::formattedSpeed,
            row -> "ETA " + row.formattedEta(),
            true
        ));

        TableColumn<MonitoredTransfer, MonitoredTransfer> timeCol = new TableColumn<>("TIME");
        timeCol.setMinWidth(58);
        timeCol.setPrefWidth(78);
        timeCol.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue()));
        timeCol.setCellFactory(col -> new TwoLineCell(
            row -> "Start " + row.formattedStartedAt(),
            row -> row.completedAt() != null ? "End " + row.formattedCompletedAt() : "End --",
            true
        ));

        TableColumn<MonitoredTransfer, MonitoredTransfer> statusCol = new TableColumn<>("STATUS");
        statusCol.setMinWidth(64);
        statusCol.setPrefWidth(92);
        statusCol.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue()));
        statusCol.setCellFactory(col -> new TableCell<>() {
            private final StatusBadge badge = new StatusBadge();
            private final Button cancelButton = UiButtons.create("Cancel", UiButtons.Variant.DANGER, UiButtons.Size.SM);
            private final VBox box = new VBox(DesignSystem.SPACE_XXS, badge, cancelButton);
            private String boundTransferId;

            {
                badge.setCompact(true);
                box.setAlignment(Pos.CENTER_LEFT);
                cancelButton.setMnemonicParsing(false);
                cancelButton.setOnAction(event -> {
                    if (boundTransferId != null && services != null) {
                        services.transferService().cancelActiveTransfer(boundTransferId);
                    }
                });
            }

            @Override
            protected void updateItem(MonitoredTransfer row, boolean empty) {
                super.updateItem(row, empty);
                if (empty || row == null) {
                    boundTransferId = null;
                    setGraphic(null);
                    setTooltip(null);
                } else {
                    boundTransferId = row.transferId();
                    badge.setText(row.state().displayName());
                    badge.setTone(row.state().tone());
                    boolean cancellable = row.state().isActive();
                    cancelButton.setVisible(cancellable);
                    cancelButton.setManaged(cancellable);
                    if (!row.statusNote().isBlank()) {
                        setTooltip(new Tooltip(row.statusNote()));
                    } else {
                        setTooltip(null);
                    }
                    setGraphic(box);
                }
            }
        });

        table.getColumns().addAll(List.of(fileCol, sizeCol, progressCol, speedEtaCol, timeCol, statusCol));
        return table;
    }

    private TableView<MonitoredTransfer> createRecentTable() {
        TableView<MonitoredTransfer> table = new TableView<>();
        table.getStyleClass().addAll("ss-table", "ss-table-monitoring");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPrefHeight(220);
        table.setMinHeight(150);

        EmptyStatePane placeholder = new EmptyStatePane(
            UiIcons.IconType.CLOCK,
            SemanticTone.NEUTRAL,
            "No completed or failed transfers yet",
            "Completed, failed, and cancelled transfer summaries will appear here with timestamps."
        ).setCompact(true);
        table.setPlaceholder(placeholder);

        TableColumn<MonitoredTransfer, MonitoredTransfer> fileCol = new TableColumn<>("FILE & PEER");
        fileCol.setMinWidth(96);
        fileCol.setPrefWidth(160);
        fileCol.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue()));
        fileCol.setCellFactory(col -> new TwoLineCell(
            MonitoredTransfer::fileName,
            row -> row.direction().label() + " • " + row.peerAddress(),
            false
        ));

        TableColumn<MonitoredTransfer, MonitoredTransfer> bytesCol = new TableColumn<>("TRANSFERRED");
        bytesCol.setMinWidth(82);
        bytesCol.setPrefWidth(116);
        bytesCol.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue()));
        bytesCol.setCellFactory(col -> new TwoLineCell(
            MonitoredTransfer::formattedTransferred,
            MonitoredTransfer::formattedPercentage,
            true
        ));

        TableColumn<MonitoredTransfer, MonitoredTransfer> timesCol = new TableColumn<>("TIMESTAMPS");
        timesCol.setMinWidth(74);
        timesCol.setPrefWidth(100);
        timesCol.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue()));
        timesCol.setCellFactory(col -> new TwoLineCell(
            row -> "Start " + row.formattedStartedAt(),
            row -> "End " + row.formattedCompletedAt(),
            true
        ));

        TableColumn<MonitoredTransfer, MonitoredTransfer> statusCol = new TableColumn<>("OUTCOME");
        statusCol.setMinWidth(98);
        statusCol.setPrefWidth(148);
        statusCol.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue()));
        statusCol.setCellFactory(col -> new TableCell<>() {
            private final StatusBadge badge = new StatusBadge();
            private final Label detail = new Label();
            private final VBox box = new VBox(DesignSystem.SPACE_XXS, badge, detail);

            {
                badge.setCompact(true);
                box.setAlignment(Pos.CENTER_LEFT);
                detail.getStyleClass().add("text-caption");
            }

            @Override
            protected void updateItem(MonitoredTransfer row, boolean empty) {
                super.updateItem(row, empty);
                if (empty || row == null) {
                    setGraphic(null);
                    setTooltip(null);
                } else {
                    badge.setText(row.state().displayName());
                    badge.setTone(row.state().tone());
                    String detailText = resolveOutcomeDetail(row);
                    detail.setText(detailText);
                    DesignSystem.swapExclusiveClass(
                        detail,
                        List.of("text-caption", "text-error", "text-warning"),
                        row.state() == TransferMonitorState.FAILED
                            ? "text-error"
                            : (row.state() == TransferMonitorState.CANCELLED ? "text-warning" : "text-caption")
                    );
                    setTooltip(detailText.isBlank() ? null : new Tooltip(detailText));
                    setGraphic(box);
                }
            }
        });

        table.getColumns().addAll(List.of(fileCol, bytesCol, timesCol, statusCol));
        return table;
    }

    private void handleTransfersUpdated(List<MonitoredTransfer> active, List<MonitoredTransfer> recent) {
        updateHeaderBadges(active, recent);
        updateFailureBanner(recent);
    }

    private void applyFilter() {
        String query = searchField != null && searchField.getText() != null
            ? searchField.getText().trim().toLowerCase(Locale.ROOT)
            : "";
        if (filteredActive != null) {
            filteredActive.setPredicate(item -> matchesQuery(item, query));
        }
        if (filteredRecent != null) {
            filteredRecent.setPredicate(item -> matchesQuery(item, query));
        }
    }

    private static boolean matchesQuery(MonitoredTransfer item, String query) {
        if (query.isEmpty()) {
            return true;
        }
        return item.fileName().toLowerCase(Locale.ROOT).contains(query)
            || item.peerAddress().toLowerCase(Locale.ROOT).contains(query)
            || item.direction().label().toLowerCase(Locale.ROOT).contains(query)
            || item.state().displayName().toLowerCase(Locale.ROOT).contains(query)
            || item.errorMessage().toLowerCase(Locale.ROOT).contains(query);
    }

    private void updateHeaderBadges(List<MonitoredTransfer> active, List<MonitoredTransfer> recent) {
        if (countBadge != null) {
            int activeCount = active != null ? active.size() : 0;
            countBadge.setText(activeCount + " Active");
            countBadge.setTone(activeCount > 0 ? SemanticTone.TEAL : SemanticTone.NEUTRAL);
        }
        if (recentSummaryBadge != null) {
            int recentCount = recent != null ? recent.size() : 0;
            long failedCount = recent == null ? 0L : recent.stream()
                .filter(t -> t.state() == TransferMonitorState.FAILED)
                .count();
            if (failedCount > 0) {
                recentSummaryBadge.setText(recentCount + " Logged • " + failedCount + " Failed");
                recentSummaryBadge.setTone(SemanticTone.ERROR);
            } else {
                recentSummaryBadge.setText(recentCount + " Logged");
                recentSummaryBadge.setTone(recentCount > 0 ? SemanticTone.SUCCESS : SemanticTone.NEUTRAL);
            }
            if (clearHistoryButton != null) {
                clearHistoryButton.setDisable(recentCount == 0);
            }
        }
    }

    private void updateFailureBanner(List<MonitoredTransfer> recent) {
        if (errorBanner == null || recent == null) {
            return;
        }
        activeFailureBannerIds.clear();
        MonitoredTransfer latestUnacknowledgedFailure = null;
        int unacknowledgedCount = 0;

        for (MonitoredTransfer item : recent) {
            if (item.state() == TransferMonitorState.FAILED) {
                activeFailureBannerIds.add(item.transferId());
                if (!acknowledgedFailureIds.contains(item.transferId())) {
                    if (latestUnacknowledgedFailure == null) {
                        latestUnacknowledgedFailure = item;
                    }
                    unacknowledgedCount++;
                }
            }
        }

        if (latestUnacknowledgedFailure == null) {
            hideErrorBanner();
            return;
        }

        String title = unacknowledgedCount == 1
            ? "Transfer Failed: " + latestUnacknowledgedFailure.fileName()
            : unacknowledgedCount + " Transfers Failed (Latest: " + latestUnacknowledgedFailure.fileName() + ")";
        String reason = !latestUnacknowledgedFailure.errorMessage().isBlank()
            ? latestUnacknowledgedFailure.errorMessage()
            : "Connection interrupted during transfer";
        errorBanner.setTitle(title);
        errorBanner.setMessage(reason + " • Peer: " + latestUnacknowledgedFailure.peerAddress());
        errorBanner.show();
    }

    private void hideErrorBanner() {
        if (errorBanner != null) {
            errorBanner.setVisible(false);
            errorBanner.setManaged(false);
        }
    }

    private static String resolveOutcomeDetail(MonitoredTransfer row) {
        if (!row.errorMessage().isBlank()) {
            return row.errorMessage();
        }
        if (!row.statusNote().isBlank()) {
            return row.statusNote();
        }
        return row.state().displayName();
    }

    public TableView<MonitoredTransfer> getActiveTable() {
        return activeTable;
    }

    public TableView<MonitoredTransfer> getRecentTable() {
        return recentTable;
    }

    public TextField getSearchField() {
        return searchField;
    }

    public FeedbackBanner getErrorBanner() {
        return errorBanner;
    }

    public StatusBadge getCountBadge() {
        return countBadge;
    }

    private static final class TwoLineCell extends TableCell<MonitoredTransfer, MonitoredTransfer> {

        private final java.util.function.Function<MonitoredTransfer, String> primaryExtractor;
        private final java.util.function.Function<MonitoredTransfer, String> secondaryExtractor;
        private final Label primaryLabel = new Label();
        private final Label secondaryLabel = new Label();
        private final VBox container = new VBox(2, primaryLabel, secondaryLabel);

        TwoLineCell(
            java.util.function.Function<MonitoredTransfer, String> primaryExtractor,
            java.util.function.Function<MonitoredTransfer, String> secondaryExtractor,
            boolean monospacePrimary
        ) {
            this.primaryExtractor = primaryExtractor;
            this.secondaryExtractor = secondaryExtractor;
            primaryLabel.getStyleClass().add(monospacePrimary ? "text-mono-sm" : "text-body-strong");
            secondaryLabel.getStyleClass().add("text-caption");
            container.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(MonitoredTransfer row, boolean empty) {
            super.updateItem(row, empty);
            if (empty || row == null) {
                setGraphic(null);
                setTooltip(null);
            } else {
                String primary = primaryExtractor.apply(row);
                String secondary = secondaryExtractor.apply(row);
                primaryLabel.setText(primary != null ? primary : "");
                secondaryLabel.setText(secondary != null ? secondary : "");
                setTooltip(new Tooltip(primaryLabel.getText() + "\n" + secondaryLabel.getText()));
                setGraphic(container);
            }
        }
    }
}
