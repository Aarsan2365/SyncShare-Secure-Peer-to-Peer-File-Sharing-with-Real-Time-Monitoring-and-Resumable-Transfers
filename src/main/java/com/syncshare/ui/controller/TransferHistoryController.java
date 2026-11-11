package com.syncshare.ui.controller;

import com.syncshare.model.NavItemId;
import com.syncshare.model.TransferItem;
import com.syncshare.model.TransferStatus;
import com.syncshare.service.SyncShareServices;
import com.syncshare.ui.design.DesignSystem;
import com.syncshare.ui.design.EmptyStatePane;
import com.syncshare.ui.design.SemanticTone;
import com.syncshare.ui.design.StatusBadge;
import com.syncshare.ui.design.UiButtons;
import com.syncshare.ui.design.UiCard;
import com.syncshare.ui.design.UiIcons;
import com.syncshare.ui.navigation.NavigationAware;
import com.syncshare.ui.navigation.ViewNavigator;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.ListChangeListener;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Locale;

/**
 * Controller for the Transfer History view.
 */
public class TransferHistoryController implements NavigationAware {

    @FXML
    private VBox transferHistoryRoot;

    private SyncShareServices services;
    private ViewNavigator navigator;

    private TextField searchField;
    private StatusBadge historyCountBadge;
    private Button clearHistoryButton;
    private TableView<TransferItem> historyTable;
    private FilteredList<TransferItem> filteredHistory;
    private String activeStatusFilter = "All";

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

        filteredHistory = new FilteredList<>(services.transferService().getTransferHistory(), item -> true);
        historyTable.setItems(filteredHistory);
        services.transferService().getTransferHistory().addListener(
            (ListChangeListener<TransferItem>) change -> updateHeaderState()
        );
        updateHeaderState();
    }

    @Override
    public void onNavigatedTo() {
        if (initializedContext) {
            updateHeaderState();
        }
    }

    private void buildLayout() {
        if (transferHistoryRoot == null) {
            return;
        }
        transferHistoryRoot.getChildren().clear();

        UiCard card = new UiCard(UiCard.Variant.DEFAULT);
        card.setMinWidth(0);

        historyCountBadge = new StatusBadge("0 Items", SemanticTone.NEUTRAL);
        historyCountBadge.setCompact(true);

        clearHistoryButton = UiButtons.create("Clear History", UiButtons.Variant.GHOST, UiButtons.Size.SM);
        clearHistoryButton.setDisable(true);
        clearHistoryButton.setOnAction(event -> {
            if (services != null) {
                services.transferService().clearHistory();
            }
        });

        HBox trailing = new HBox(DesignSystem.SPACE_XS, historyCountBadge, clearHistoryButton);
        trailing.setAlignment(Pos.CENTER_RIGHT);

        card.setHeader(
            "Transfer History",
            "Verified, rejected, and interrupted transfers recorded in this session",
            UiIcons.create(UiIcons.IconType.CLOCK, UiIcons.Size.LG, SemanticTone.TEAL),
            trailing
        );

        searchField = new TextField();
        searchField.setPromptText("Search history by file name or peer...");
        searchField.setAccessibleText("Search transfer history");
        searchField.getStyleClass().addAll("input-field", "search-field");
        searchField.setPrefWidth(200);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.textProperty().addListener((obs, oldVal, newVal) -> applyFilter());

        HBox segmented = new HBox();
        segmented.getStyleClass().add("segmented-bar");
        segmented.setMinWidth(Region.USE_PREF_SIZE);
        for (String filter : List.of("All", "Verified", "Failed")) {
            Button segBtn = new Button(filter);
            segBtn.setMnemonicParsing(false);
            segBtn.setMinWidth(Region.USE_PREF_SIZE);
            segBtn.getStyleClass().add("segmented-item");
            if ("All".equals(filter)) {
                segBtn.getStyleClass().add("segmented-item-active");
            }
            segBtn.setOnAction(event -> {
                activeStatusFilter = filter;
                for (Node sibling : segmented.getChildren()) {
                    sibling.getStyleClass().remove("segmented-item-active");
                }
                segBtn.getStyleClass().add("segmented-item-active");
                applyFilter();
            });
            segmented.getChildren().add(segBtn);
        }

        FlowPane filterBar = new FlowPane(DesignSystem.SPACE_SM, DesignSystem.SPACE_XS);
        filterBar.setAlignment(Pos.CENTER_LEFT);
        filterBar.getChildren().addAll(searchField, segmented);

        historyTable = createTable();
        card.setBodyContent(filterBar, historyTable);

        transferHistoryRoot.getChildren().add(card);
    }

    private TableView<TransferItem> createTable() {
        TableView<TransferItem> table = new TableView<>();
        table.getStyleClass().add("ss-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPrefHeight(340);
        table.setMinHeight(220);

        EmptyStatePane placeholder = new EmptyStatePane(
            UiIcons.IconType.CLOCK,
            SemanticTone.INFO,
            "No transfer history recorded",
            "Completed and failed transfers will appear here with timestamps and SHA-256 verification status."
        ).setCompact(true)
         .setPrimaryAction("Back to Dashboard", UiIcons.IconType.DASHBOARD, () -> {
             if (navigator != null) {
                 navigator.navigateTo(NavItemId.DASHBOARD);
             }
         });
        table.setPlaceholder(placeholder);

        TableColumn<TransferItem, String> fileCol = new TableColumn<>("FILE");
        fileCol.setMinWidth(105);
        fileCol.setCellValueFactory(data -> new ReadOnlyStringWrapper(data.getValue().fileName()));

        TableColumn<TransferItem, String> peerCol = new TableColumn<>("PEER");
        peerCol.setMinWidth(88);
        peerCol.setCellValueFactory(data -> new ReadOnlyStringWrapper(
            data.getValue().direction().label() + " • " + data.getValue().peerAddress()
        ));

        TableColumn<TransferItem, String> sizeCol = new TableColumn<>("SIZE");
        sizeCol.setMinWidth(64);
        sizeCol.setCellValueFactory(data -> new ReadOnlyStringWrapper(data.getValue().formattedSize()));

        TableColumn<TransferItem, String> timeCol = new TableColumn<>("TIME");
        timeCol.setMinWidth(68);
        timeCol.setCellValueFactory(data -> new ReadOnlyStringWrapper(data.getValue().formattedTimestamp()));

        TableColumn<TransferItem, TransferItem> statusCol = new TableColumn<>("STATUS");
        statusCol.setMinWidth(88);
        statusCol.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue()));
        statusCol.setCellFactory(col -> new TableCell<>() {
            private final StatusBadge badge = new StatusBadge();

            {
                badge.setCompact(true);
                badge.setIndicatorMode(StatusBadge.IndicatorMode.ICON);
            }

            @Override
            protected void updateItem(TransferItem row, boolean empty) {
                super.updateItem(row, empty);
                if (empty || row == null) {
                    setGraphic(null);
                } else {
                    badge.setText(row.status().displayName());
                    badge.setTone(row.status().tone());
                    setGraphic(badge);
                }
            }
        });

        table.getColumns().addAll(List.of(fileCol, peerCol, sizeCol, timeCol, statusCol));
        return table;
    }

    private void applyFilter() {
        if (filteredHistory == null) {
            return;
        }
        String query = searchField != null && searchField.getText() != null
            ? searchField.getText().trim().toLowerCase(Locale.ROOT)
            : "";

        filteredHistory.setPredicate(item -> {
            boolean matchesQuery = query.isEmpty()
                || item.fileName().toLowerCase(Locale.ROOT).contains(query)
                || item.peerAddress().toLowerCase(Locale.ROOT).contains(query)
                || item.status().displayName().toLowerCase(Locale.ROOT).contains(query);

            boolean matchesTab = switch (activeStatusFilter) {
                case "Verified" -> item.status() == TransferStatus.COMPLETED;
                case "Failed" -> item.status() == TransferStatus.FAILED || item.status() == TransferStatus.REJECTED;
                default -> true;
            };
            return matchesQuery && matchesTab;
        });
    }

    private void updateHeaderState() {
        if (services == null) {
            return;
        }
        int count = services.transferService().getTransferHistory().size();
        historyCountBadge.setText(count + (count == 1 ? " Item" : " Items"));
        historyCountBadge.setTone(count > 0 ? SemanticTone.INFO : SemanticTone.NEUTRAL);
        clearHistoryButton.setDisable(count == 0);
    }

    public TableView<TransferItem> getHistoryTable() {
        return historyTable;
    }

    public TextField getSearchField() {
        return searchField;
    }
}
