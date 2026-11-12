package com.syncshare.ui.controller;

import com.syncshare.history.TransferHistoryStatus;
import com.syncshare.history.TransferRecord;
import com.syncshare.model.NavItemId;
import com.syncshare.service.HistoryService;
import com.syncshare.service.SyncShareServices;
import com.syncshare.ui.design.DesignSystem;
import com.syncshare.ui.design.EmptyStatePane;
import com.syncshare.ui.design.FeedbackBanner;
import com.syncshare.ui.design.SemanticTone;
import com.syncshare.ui.design.StatusBadge;
import com.syncshare.ui.design.UiButtons;
import com.syncshare.ui.design.UiCard;
import com.syncshare.ui.design.UiDialogs;
import com.syncshare.ui.design.UiIcons;
import com.syncshare.ui.navigation.NavigationAware;
import com.syncshare.ui.navigation.ViewNavigator;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Controller for the persistent Transfer History view.
 * Delegates persistence, querying, filtering, sorting, and clearing strictly
 * to {@link HistoryService} while keeping all I/O off the JavaFX Application Thread.
 */
public class TransferHistoryController implements NavigationAware {

    @FXML
    private VBox transferHistoryRoot;

    private SyncShareServices services;
    private ViewNavigator navigator;

    private FeedbackBanner statusBanner;
    private TextField searchField;
    private HBox statusFilterBar;
    private ComboBox<HistoryService.SortOrder> sortOrderComboBox;
    private Button refreshButton;
    private Button clearHistoryButton;
    private StatusBadge historyCountBadge;
    private TableView<TransferRecord> historyTable;
    private EmptyStatePane emptyStatePane;

    private FilteredList<TransferRecord> filteredHistory;
    private SortedList<TransferRecord> sortedHistory;
    private TransferHistoryStatus activeStatusFilter;
    private BooleanSupplier clearConfirmationOverride;

    private ListChangeListener<TransferRecord> historyListListener;
    private ChangeListener<String> searchListener;
    private ChangeListener<HistoryService.SortOrder> sortListener;
    private ChangeListener<Boolean> loadingListener;
    private ChangeListener<String> errorListener;
    private ChangeListener<String> warningListener;

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

        HistoryService historyService = services.historyService();
        filteredHistory = new FilteredList<>(historyService.getRecords(), record -> true);
        sortedHistory = new SortedList<>(
            filteredHistory,
            HistoryService.comparatorFor(getSelectedSortOrder())
        );
        historyTable.setItems(sortedHistory);

        historyListListener = change -> updatePresentationState();
        historyService.getRecords().addListener(historyListListener);

        loadingListener = (obs, oldVal, isLoading) -> updateLoadingState(Boolean.TRUE.equals(isLoading));
        errorListener = (obs, oldVal, newVal) -> updateFeedbackBanner();
        warningListener = (obs, oldVal, newVal) -> updateFeedbackBanner();
        historyService.loadingProperty().addListener(loadingListener);
        historyService.lastErrorProperty().addListener(errorListener);
        historyService.warningMessageProperty().addListener(warningListener);

        applyPresentationFilter();
        applySortOrder();
        updateLoadingState(historyService.loadingProperty().get());
        updateHeaderState();
        updateFeedbackBanner();
    }

    @Override
    public void onNavigatedTo() {
        if (initializedContext && services != null) {
            updatePresentationState();
            updateFeedbackBanner();
        }
    }

    @Override
    public void dispose() {
        if (services != null && services.historyService() != null) {
            HistoryService historyService = services.historyService();
            if (historyListListener != null) {
                historyService.getRecords().removeListener(historyListListener);
            }
            if (loadingListener != null) {
                historyService.loadingProperty().removeListener(loadingListener);
            }
            if (errorListener != null) {
                historyService.lastErrorProperty().removeListener(errorListener);
            }
            if (warningListener != null) {
                historyService.warningMessageProperty().removeListener(warningListener);
            }
        }
        historyListListener = null;
        loadingListener = null;
        errorListener = null;
        warningListener = null;
        if (searchField != null && searchListener != null) {
            searchField.textProperty().removeListener(searchListener);
            searchListener = null;
        }
        if (sortOrderComboBox != null && sortListener != null) {
            sortOrderComboBox.valueProperty().removeListener(sortListener);
            sortListener = null;
        }
        initializedContext = false;
    }

    private void buildLayout() {
        if (transferHistoryRoot == null) {
            return;
        }
        transferHistoryRoot.getChildren().clear();

        statusBanner = new FeedbackBanner(SemanticTone.ERROR, "", "", true);
        statusBanner.setVisible(false);
        statusBanner.setManaged(false);

        UiCard card = new UiCard(UiCard.Variant.DEFAULT);
        card.setMinWidth(0);

        historyCountBadge = new StatusBadge("0 Items", SemanticTone.NEUTRAL);
        historyCountBadge.setCompact(true);

        clearHistoryButton = UiButtons.create(
            "Clear History",
            UiButtons.Variant.DANGER_OUTLINE,
            UiButtons.Size.SM
        );
        clearHistoryButton.setId("clearHistoryButton");
        clearHistoryButton.setAccessibleText("Clear saved transfer history");
        clearHistoryButton.setDisable(true);
        clearHistoryButton.setOnAction(event -> handleClearHistory());

        HBox trailing = new HBox(DesignSystem.SPACE_XS, historyCountBadge, clearHistoryButton);
        trailing.setAlignment(Pos.CENTER_RIGHT);

        card.setHeader(
            "Transfer History",
            "Completed, failed, and cancelled transfers saved across application restarts",
            UiIcons.create(UiIcons.IconType.CLOCK, UiIcons.Size.LG, SemanticTone.TEAL),
            trailing
        );

        searchField = new TextField();
        searchField.setId("historySearchField");
        searchField.setPromptText("Search history by file name...");
        searchField.setAccessibleText("Search transfer history by file name");
        searchField.getStyleClass().addAll("input-field", "search-field");
        searchField.setPrefWidth(190);
        searchListener = (obs, oldVal, newVal) -> updatePresentationState();
        searchField.textProperty().addListener(searchListener);

        statusFilterBar = buildStatusSegmentedBar();

        sortOrderComboBox = new ComboBox<>();
        sortOrderComboBox.setId("historySortComboBox");
        sortOrderComboBox.getStyleClass().add("combo-input");
        sortOrderComboBox.setAccessibleText("Sort transfer history by date");
        sortOrderComboBox.getItems().setAll(
            HistoryService.SortOrder.NEWEST_FIRST,
            HistoryService.SortOrder.OLDEST_FIRST
        );
        sortOrderComboBox.setValue(HistoryService.SortOrder.NEWEST_FIRST);
        sortOrderComboBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(HistoryService.SortOrder order) {
                return order != null ? order.displayName() : "";
            }

            @Override
            public HistoryService.SortOrder fromString(String string) {
                return HistoryService.SortOrder.NEWEST_FIRST;
            }
        });
        sortListener = (obs, oldVal, newVal) -> applySortOrder();
        sortOrderComboBox.valueProperty().addListener(sortListener);

        refreshButton = UiButtons.create(
            "Refresh",
            UiIcons.create(UiIcons.IconType.REFRESH, UiIcons.Size.SM),
            UiButtons.Variant.SECONDARY,
            UiButtons.Size.SM
        );
        refreshButton.setId("refreshHistoryButton");
        refreshButton.setAccessibleText("Refresh transfer history from disk");
        refreshButton.setOnAction(event -> handleRefreshHistory());

        FlowPane toolbar = new FlowPane(DesignSystem.SPACE_XS, DesignSystem.SPACE_XS);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getChildren().addAll(searchField, statusFilterBar, sortOrderComboBox, refreshButton);

        historyTable = createTable();
        card.setBodyContent(toolbar, historyTable);

        transferHistoryRoot.getChildren().addAll(statusBanner, card);
    }

    private HBox buildStatusSegmentedBar() {
        HBox bar = new HBox();
        bar.setId("historyStatusFilterBar");
        bar.getStyleClass().add("segmented-bar");
        bar.setMinWidth(Region.USE_PREF_SIZE);

        addStatusFilterButton(bar, "All", null);
        addStatusFilterButton(bar, "Completed", TransferHistoryStatus.COMPLETED);
        addStatusFilterButton(bar, "Failed", TransferHistoryStatus.FAILED);
        addStatusFilterButton(bar, "Cancelled", TransferHistoryStatus.CANCELLED);
        return bar;
    }

    private void addStatusFilterButton(HBox bar, String label, TransferHistoryStatus status) {
        Button button = new Button(label);
        button.setId("historyFilter" + label + "Button");
        button.setMnemonicParsing(false);
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.getStyleClass().add("segmented-item");
        if (status == null) {
            button.getStyleClass().add("segmented-item-active");
        }
        button.setOnAction(event -> selectStatusFilter(status, button));
        bar.getChildren().add(button);
    }

    private void selectStatusFilter(TransferHistoryStatus status, Button activeButton) {
        this.activeStatusFilter = status;
        if (statusFilterBar != null) {
            for (Node child : statusFilterBar.getChildren()) {
                child.getStyleClass().remove("segmented-item-active");
            }
        }
        if (activeButton != null && !activeButton.getStyleClass().contains("segmented-item-active")) {
            activeButton.getStyleClass().add("segmented-item-active");
        }
        updatePresentationState();
    }

    private TableView<TransferRecord> createTable() {
        TableView<TransferRecord> table = new TableView<>();
        table.setId("historyTable");
        table.getStyleClass().add("ss-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPrefHeight(360);
        table.setMinHeight(220);

        emptyStatePane = new EmptyStatePane(
            UiIcons.IconType.CLOCK,
            SemanticTone.INFO,
            "No transfer history recorded",
            "Completed, failed, and cancelled transfers will persist here with timestamps, duration, and status."
        ).setCompact(true);
        configureEmptyStateForNoHistory();
        table.setPlaceholder(emptyStatePane);

        // Sum of column minWidths <= 410 px so 800x560 breakpoint never overflows horizontally
        TableColumn<TransferRecord, TransferRecord> fileCol = new TableColumn<>("FILE");
        fileCol.setMinWidth(66);
        fileCol.setPrefWidth(140);
        fileCol.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue()));
        fileCol.setCellFactory(col -> new TextTooltipCell(
            TransferRecord::fileName,
            row -> row.summaryNote().isBlank()
                ? row.fileName()
                : row.fileName() + "\n" + row.summaryNote(),
            "text-body-strong"
        ));

        TableColumn<TransferRecord, String> sizeCol = new TableColumn<>("SIZE");
        sizeCol.setMinWidth(38);
        sizeCol.setPrefWidth(72);
        sizeCol.setCellValueFactory(data -> new ReadOnlyStringWrapper(data.getValue().formattedSize()));
        sizeCol.setCellFactory(col -> new StyledTextCell("table-cell-mono"));

        TableColumn<TransferRecord, String> directionCol = new TableColumn<>("DIRECTION");
        directionCol.setMinWidth(38);
        directionCol.setPrefWidth(68);
        directionCol.setCellValueFactory(data -> new ReadOnlyStringWrapper(data.getValue().direction().displayName()));
        directionCol.setCellFactory(col -> new StyledTextCell("text-secondary"));

        TableColumn<TransferRecord, TransferRecord> peerCol = new TableColumn<>("SENDER / RECEIVER");
        peerCol.setMinWidth(54);
        peerCol.setPrefWidth(112);
        peerCol.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue()));
        peerCol.setCellFactory(col -> new TextTooltipCell(
            TransferRecord::peerInfo,
            row -> row.direction().displayName() + " • " + row.peerInfo(),
            "table-cell-mono"
        ));

        TableColumn<TransferRecord, String> dateCol = new TableColumn<>("DATE & TIME");
        dateCol.setMinWidth(56);
        dateCol.setPrefWidth(122);
        dateCol.setCellValueFactory(data -> new ReadOnlyStringWrapper(data.getValue().formattedDateTime()));
        dateCol.setCellFactory(col -> new StyledTextCell("table-cell-mono"));

        TableColumn<TransferRecord, String> durationCol = new TableColumn<>("DURATION");
        durationCol.setMinWidth(36);
        durationCol.setPrefWidth(68);
        durationCol.setCellValueFactory(data -> new ReadOnlyStringWrapper(data.getValue().formattedDuration()));
        durationCol.setCellFactory(col -> new StyledTextCell("table-cell-mono"));

        TableColumn<TransferRecord, TransferRecord> destinationCol = new TableColumn<>("DESTINATION");
        destinationCol.setMinWidth(40);
        destinationCol.setPrefWidth(92);
        destinationCol.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue()));
        destinationCol.setCellFactory(col -> new TextTooltipCell(
            TransferRecord::formattedDestination,
            row -> row.hasDestination() ? "Saved to: " + row.destination() : "Not applicable",
            "text-caption"
        ));

        TableColumn<TransferRecord, TransferRecord> statusCol = new TableColumn<>("STATUS");
        statusCol.setMinWidth(58);
        statusCol.setPrefWidth(94);
        statusCol.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue()));
        statusCol.setCellFactory(col -> new TableCell<>() {
            private final StatusBadge badge = new StatusBadge();

            {
                badge.setCompact(true);
                badge.setIndicatorMode(StatusBadge.IndicatorMode.ICON);
            }

            @Override
            protected void updateItem(TransferRecord row, boolean empty) {
                super.updateItem(row, empty);
                if (empty || row == null) {
                    setGraphic(null);
                    setTooltip(null);
                } else {
                    badge.setText(row.status().displayName());
                    badge.setTone(row.status().tone());
                    setTooltip(row.summaryNote().isBlank() ? null : new Tooltip(row.summaryNote()));
                    setGraphic(badge);
                }
            }
        });

        table.getColumns().addAll(List.of(
            fileCol,
            sizeCol,
            directionCol,
            peerCol,
            dateCol,
            durationCol,
            destinationCol,
            statusCol
        ));
        return table;
    }

    private void handleRefreshHistory() {
        if (services == null) {
            return;
        }
        services.historyService().refreshAsync().whenComplete((result, err) -> runOnFx(() -> {
            updatePresentationState();
            updateFeedbackBanner();
        }));
    }

    private void handleClearHistory() {
        if (services == null) {
            return;
        }
        HistoryService historyService = services.historyService();
        int count = historyService.getRecords().size();
        if (!confirmClearHistory(count)) {
            return;
        }
        historyService.clearHistoryAsync().whenComplete((result, err) -> runOnFx(() -> {
            updatePresentationState();
            if (err != null) {
                showErrorBanner("Failed to clear transfer history", err.getMessage());
            } else if (result != null && !result.success()) {
                showErrorBanner("Clear History Failed", result.message());
            } else {
                updateFeedbackBanner();
            }
        }));
    }

    private boolean confirmClearHistory(int itemCount) {
        if (clearConfirmationOverride != null) {
            return clearConfirmationOverride.getAsBoolean();
        }
        Alert dialog = createClearHistoryConfirmationDialog(itemCount);
        Optional<ButtonType> choice = dialog.showAndWait();
        return choice.isPresent()
            && choice.get().getButtonData() == ButtonBar.ButtonData.OK_DONE;
    }

    public Alert createClearHistoryConfirmationDialog(int itemCount) {
        Window owner = transferHistoryRoot != null && transferHistoryRoot.getScene() != null
            ? transferHistoryRoot.getScene().getWindow()
            : null;
        String message = "Permanently remove " + itemCount + (itemCount == 1 ? " saved transfer record" : " saved transfer records")
            + " from persistent history?\n\n"
            + "This cannot be undone. Active transfers and live monitoring data will remain intact.";
        return UiDialogs.confirmDanger(
            owner,
            "Clear Transfer History",
            "Permanently delete saved transfer history?",
            message,
            "Clear History"
        );
    }

    public void setClearConfirmationOverride(BooleanSupplier handler) {
        this.clearConfirmationOverride = handler;
    }

    private void updatePresentationState() {
        applyPresentationFilter();
        updateHeaderState();
        updateEmptyStateCopy();
    }

    private void applyPresentationFilter() {
        if (filteredHistory == null) {
            return;
        }
        String query = searchField != null ? searchField.getText() : "";
        filteredHistory.setPredicate(record -> HistoryService.matchesFilter(record, query, activeStatusFilter));
    }

    private void applySortOrder() {
        if (sortedHistory == null) {
            return;
        }
        sortedHistory.setComparator(HistoryService.comparatorFor(getSelectedSortOrder()));
    }

    private HistoryService.SortOrder getSelectedSortOrder() {
        if (sortOrderComboBox == null || sortOrderComboBox.getValue() == null) {
            return HistoryService.SortOrder.NEWEST_FIRST;
        }
        return sortOrderComboBox.getValue();
    }

    private void updateHeaderState() {
        if (services == null || historyCountBadge == null) {
            return;
        }
        int totalCount = services.historyService().getRecords().size();
        int visibleCount = sortedHistory != null ? sortedHistory.size() : totalCount;
        if (visibleCount != totalCount) {
            historyCountBadge.setText(visibleCount + " of " + totalCount);
        } else {
            historyCountBadge.setText(totalCount + (totalCount == 1 ? " Item" : " Items"));
        }
        historyCountBadge.setTone(totalCount > 0 ? SemanticTone.INFO : SemanticTone.NEUTRAL);

        if (clearHistoryButton != null) {
            boolean busy = services.historyService().loadingProperty().get();
            clearHistoryButton.setDisable(totalCount == 0 || busy);
        }
    }

    private void updateLoadingState(boolean isLoading) {
        if (refreshButton != null) {
            refreshButton.setDisable(isLoading);
        }
        if (clearHistoryButton != null && services != null) {
            clearHistoryButton.setDisable(isLoading || services.historyService().getRecords().isEmpty());
        }
    }

    private void updateEmptyStateCopy() {
        if (emptyStatePane == null || services == null) {
            return;
        }
        int totalCount = services.historyService().getRecords().size();
        if (totalCount == 0) {
            configureEmptyStateForNoHistory();
        } else {
            emptyStatePane.setTitle("No matching history records");
            emptyStatePane.setDescription("No saved transfers match the active search query or status filter.");
            emptyStatePane.setPrimaryAction("Reset Filters", UiIcons.IconType.REFRESH, this::resetFilters);
        }
    }

    private void configureEmptyStateForNoHistory() {
        if (emptyStatePane == null) {
            return;
        }
        emptyStatePane.setTitle("No transfer history recorded");
        emptyStatePane.setDescription(
            "Completed, failed, and cancelled transfers will persist here with timestamps, duration, and status."
        );
        emptyStatePane.setPrimaryAction("Back to Dashboard", UiIcons.IconType.DASHBOARD, () -> {
            if (navigator != null) {
                navigator.navigateTo(NavItemId.DASHBOARD);
            }
        });
    }

    public void resetFilters() {
        if (searchField != null) {
            searchField.clear();
        }
        Button allBtn = statusFilterBar != null && !statusFilterBar.getChildren().isEmpty()
            && statusFilterBar.getChildren().get(0) instanceof Button b ? b : null;
        selectStatusFilter(null, allBtn);
    }

    private void updateFeedbackBanner() {
        if (services == null || statusBanner == null) {
            return;
        }
        HistoryService historyService = services.historyService();
        String error = historyService.getLastError();
        if (error != null && !error.isBlank()) {
            showErrorBanner("History Storage Error", error);
            return;
        }
        String warning = historyService.getWarningMessage();
        if (warning != null && !warning.isBlank()) {
            statusBanner.setTone(SemanticTone.WARNING)
                .setTitle("Partial History Loaded")
                .setMessage(warning)
                .show();
            return;
        }
        statusBanner.setVisible(false);
        statusBanner.setManaged(false);
    }

    private void showErrorBanner(String title, String message) {
        if (statusBanner != null) {
            statusBanner.setTone(SemanticTone.ERROR)
                .setTitle(title)
                .setMessage(message != null ? message : "Unable to update persistent transfer history.")
                .show();
        }
    }

    private static void runOnFx(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            try {
                Platform.runLater(action);
            } catch (IllegalStateException ignored) {
                action.run();
            }
        }
    }

    public TableView<TransferRecord> getHistoryTable() {
        return historyTable;
    }

    public TextField getSearchField() {
        return searchField;
    }

    public ComboBox<HistoryService.SortOrder> getSortOrderComboBox() {
        return sortOrderComboBox;
    }

    public Button getRefreshButton() {
        return refreshButton;
    }

    public Button getClearHistoryButton() {
        return clearHistoryButton;
    }

    public FeedbackBanner getStatusBanner() {
        return statusBanner;
    }

    public StatusBadge getHistoryCountBadge() {
        return historyCountBadge;
    }

    public TransferHistoryStatus getActiveStatusFilter() {
        return activeStatusFilter;
    }

    private static final class StyledTextCell extends TableCell<TransferRecord, String> {
        private final Label label = new Label();

        StyledTextCell(String styleClass) {
            label.getStyleClass().add(styleClass);
        }

        @Override
        protected void updateItem(String value, boolean empty) {
            super.updateItem(value, empty);
            if (empty || value == null) {
                setGraphic(null);
                setTooltip(null);
            } else {
                label.setText(value);
                setTooltip(value.isBlank() ? null : new Tooltip(value));
                setGraphic(label);
            }
        }
    }

    private static final class TextTooltipCell extends TableCell<TransferRecord, TransferRecord> {
        private final java.util.function.Function<TransferRecord, String> textExtractor;
        private final java.util.function.Function<TransferRecord, String> tooltipExtractor;
        private final Label label = new Label();

        TextTooltipCell(
            java.util.function.Function<TransferRecord, String> textExtractor,
            java.util.function.Function<TransferRecord, String> tooltipExtractor,
            String styleClass
        ) {
            this.textExtractor = textExtractor;
            this.tooltipExtractor = tooltipExtractor;
            label.getStyleClass().add(styleClass);
        }

        @Override
        protected void updateItem(TransferRecord row, boolean empty) {
            super.updateItem(row, empty);
            if (empty || row == null) {
                setGraphic(null);
                setTooltip(null);
            } else {
                String text = textExtractor.apply(row);
                String tip = tooltipExtractor.apply(row);
                label.setText(text != null ? text : "");
                setTooltip(tip != null && !tip.isBlank() ? new Tooltip(tip) : null);
                setGraphic(label);
            }
        }
    }
}
