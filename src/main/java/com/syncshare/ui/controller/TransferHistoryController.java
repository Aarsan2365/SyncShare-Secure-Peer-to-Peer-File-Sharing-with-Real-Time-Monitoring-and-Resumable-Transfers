package com.syncshare.ui.controller;

import com.syncshare.history.TransferRecord;
import com.syncshare.model.NavItemId;
import com.syncshare.service.HistoryService;
import com.syncshare.service.SyncShareServices;
import com.syncshare.ui.design.DesignSystem;
import com.syncshare.ui.design.EmptyStatePane;
import com.syncshare.ui.design.FeedbackBanner;
import com.syncshare.ui.design.SemanticTone;
import com.syncshare.ui.design.StatusBadge;
import com.syncshare.ui.design.UiCard;
import com.syncshare.ui.design.UiIcons;
import com.syncshare.ui.navigation.NavigationAware;
import com.syncshare.ui.navigation.ViewNavigator;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * Controller for the persistent Transfer History view.
 * Renders saved terminal transfers from {@link HistoryService} without executing
 * disk persistence or business logic on the JavaFX Application Thread.
 */
public class TransferHistoryController implements NavigationAware {

    @FXML
    private VBox transferHistoryRoot;

    private SyncShareServices services;
    private ViewNavigator navigator;

    private FeedbackBanner statusBanner;
    private TextField searchField;
    private StatusBadge historyCountBadge;
    private TableView<TransferRecord> historyTable;
    private FilteredList<TransferRecord> filteredHistory;
    private SortedList<TransferRecord> sortedHistory;

    private ListChangeListener<TransferRecord> historyListListener;
    private ChangeListener<String> searchListener;
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
        sortedHistory = new SortedList<>(filteredHistory, HistoryService.comparatorFor(HistoryService.SortOrder.NEWEST_FIRST));
        historyTable.setItems(sortedHistory);

        historyListListener = change -> updateHeaderState();
        historyService.getRecords().addListener(historyListListener);

        errorListener = (obs, oldVal, newVal) -> updateFeedbackBanner();
        warningListener = (obs, oldVal, newVal) -> updateFeedbackBanner();
        historyService.lastErrorProperty().addListener(errorListener);
        historyService.warningMessageProperty().addListener(warningListener);

        applyPresentationFilter();
        updateHeaderState();
        updateFeedbackBanner();
    }

    @Override
    public void onNavigatedTo() {
        if (initializedContext && services != null) {
            applyPresentationFilter();
            updateHeaderState();
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
            if (errorListener != null) {
                historyService.lastErrorProperty().removeListener(errorListener);
            }
            if (warningListener != null) {
                historyService.warningMessageProperty().removeListener(warningListener);
            }
        }
        historyListListener = null;
        errorListener = null;
        warningListener = null;
        if (searchField != null && searchListener != null) {
            searchField.textProperty().removeListener(searchListener);
            searchListener = null;
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

        HBox trailing = new HBox(DesignSystem.SPACE_XS, historyCountBadge);
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
        searchListener = (obs, oldVal, newVal) -> applyPresentationFilter();
        searchField.textProperty().addListener(searchListener);
        HBox.setHgrow(searchField, Priority.ALWAYS);

        historyTable = createTable();
        card.setBodyContent(searchField, historyTable);

        transferHistoryRoot.getChildren().addAll(statusBanner, card);
    }

    private TableView<TransferRecord> createTable() {
        TableView<TransferRecord> table = new TableView<>();
        table.setId("historyTable");
        table.getStyleClass().add("ss-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPrefHeight(360);
        table.setMinHeight(220);

        EmptyStatePane placeholder = new EmptyStatePane(
            UiIcons.IconType.CLOCK,
            SemanticTone.INFO,
            "No transfer history recorded",
            "Completed, failed, and cancelled transfers will persist here with timestamps, duration, and status."
        ).setCompact(true)
         .setPrimaryAction("Back to Dashboard", UiIcons.IconType.DASHBOARD, () -> {
             if (navigator != null) {
                 navigator.navigateTo(NavItemId.DASHBOARD);
             }
         });
        table.setPlaceholder(placeholder);

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

    private void applyPresentationFilter() {
        if (filteredHistory == null) {
            return;
        }
        String query = searchField != null ? searchField.getText() : "";
        filteredHistory.setPredicate(record -> HistoryService.matchesFilter(record, query, null));
    }

    private void updateHeaderState() {
        if (services == null || historyCountBadge == null) {
            return;
        }
        int totalCount = services.historyService().getRecords().size();
        historyCountBadge.setText(totalCount + (totalCount == 1 ? " Item" : " Items"));
        historyCountBadge.setTone(totalCount > 0 ? SemanticTone.INFO : SemanticTone.NEUTRAL);
    }

    private void updateFeedbackBanner() {
        if (services == null || statusBanner == null) {
            return;
        }
        HistoryService historyService = services.historyService();
        String error = historyService.getLastError();
        if (error != null && !error.isBlank()) {
            statusBanner.setTone(SemanticTone.ERROR)
                .setTitle("History Storage Error")
                .setMessage(error)
                .show();
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

    public TableView<TransferRecord> getHistoryTable() {
        return historyTable;
    }

    public TextField getSearchField() {
        return searchField;
    }

    public FeedbackBanner getStatusBanner() {
        return statusBanner;
    }

    public StatusBadge getHistoryCountBadge() {
        return historyCountBadge;
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
