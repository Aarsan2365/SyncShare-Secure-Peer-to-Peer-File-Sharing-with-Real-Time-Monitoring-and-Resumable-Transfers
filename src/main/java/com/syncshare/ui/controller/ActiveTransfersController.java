package com.syncshare.ui.controller;

import com.syncshare.model.NavItemId;
import com.syncshare.model.TransferItem;
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
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Locale;

/**
 * Controller for the Active Transfers monitoring view.
 */
public class ActiveTransfersController implements NavigationAware {

    @FXML
    private VBox activeTransfersRoot;

    private SyncShareServices services;
    private ViewNavigator navigator;

    private TextField searchField;
    private StatusBadge countBadge;
    private TableView<TransferItem> activeTable;
    private FilteredList<TransferItem> filteredActive;

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

        filteredActive = new FilteredList<>(services.transferService().getActiveTransfers(), item -> true);
        activeTable.setItems(filteredActive);
        services.transferService().getActiveTransfers().addListener(
            (ListChangeListener<TransferItem>) change -> updateHeaderBadge()
        );
        updateHeaderBadge();
    }

    @Override
    public void onNavigatedTo() {
        if (initializedContext) {
            updateHeaderBadge();
        }
    }

    private void buildLayout() {
        if (activeTransfersRoot == null) {
            return;
        }
        activeTransfersRoot.getChildren().clear();

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
            "Live progress and resume checkpoints for transfers currently in flight",
            UiIcons.create(UiIcons.IconType.ACTIVITY, UiIcons.Size.LG, SemanticTone.TEAL),
            trailing
        );

        searchField = new TextField();
        searchField.setPromptText("Filter active transfers by file or peer...");
        searchField.setAccessibleText("Filter active transfers");
        searchField.getStyleClass().addAll("input-field", "search-field");
        searchField.textProperty().addListener((obs, oldVal, newVal) -> applyFilter());

        activeTable = createTable();
        card.setBodyContent(searchField, activeTable);

        activeTransfersRoot.getChildren().add(card);
    }

    private TableView<TransferItem> createTable() {
        TableView<TransferItem> table = new TableView<>();
        table.getStyleClass().add("ss-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPrefHeight(340);
        table.setMinHeight(220);

        EmptyStatePane placeholder = new EmptyStatePane(
            UiIcons.IconType.ACTIVITY,
            SemanticTone.TEAL,
            "No active transfers",
            "Transfers currently streaming or awaiting peer approval will appear here."
        ).setCompact(true)
         .setPrimaryAction("Send Files", UiIcons.IconType.SEND, () -> {
             if (navigator != null) {
                 navigator.navigateTo(NavItemId.SEND_FILES);
             }
         });
        table.setPlaceholder(placeholder);

        TableColumn<TransferItem, String> fileCol = new TableColumn<>("FILE");
        fileCol.setMinWidth(100);
        fileCol.setCellValueFactory(data -> new ReadOnlyStringWrapper(data.getValue().fileName()));

        TableColumn<TransferItem, String> peerCol = new TableColumn<>("PEER");
        peerCol.setMinWidth(84);
        peerCol.setCellValueFactory(data -> new ReadOnlyStringWrapper(
            data.getValue().direction().label() + " • " + data.getValue().peerAddress()
        ));

        TableColumn<TransferItem, String> sizeCol = new TableColumn<>("SIZE");
        sizeCol.setMinWidth(64);
        sizeCol.setCellValueFactory(data -> new ReadOnlyStringWrapper(data.getValue().formattedSize()));

        TableColumn<TransferItem, TransferItem> progressCol = new TableColumn<>("PROGRESS");
        progressCol.setMinWidth(98);
        progressCol.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue()));
        progressCol.setCellFactory(col -> new TableCell<>() {
            private final ProgressBar bar = new ProgressBar();
            private final Label pct = new Label();
            private final HBox box = new HBox(DesignSystem.SPACE_XS, bar, pct);

            {
                box.setAlignment(Pos.CENTER_LEFT);
                bar.getStyleClass().addAll("ss-progress", "progress-sm");
                bar.setMinWidth(40);
                bar.setPrefWidth(64);
                HBox.setHgrow(bar, Priority.ALWAYS);
                pct.getStyleClass().add("text-mono-sm");
                pct.setMinWidth(Region.USE_PREF_SIZE);
            }

            @Override
            protected void updateItem(TransferItem row, boolean empty) {
                super.updateItem(row, empty);
                if (empty || row == null) {
                    setGraphic(null);
                } else {
                    bar.setProgress(row.progress());
                    String progressToneClass = row.status().tone() == SemanticTone.TEAL
                        ? null
                        : row.status().tone().progressStyleClass();
                    DesignSystem.swapExclusiveClass(bar, SemanticTone.allProgressClasses(), progressToneClass);
                    pct.setText(row.progress() < 0 ? "..." : Math.round(row.progress() * 100) + "%");
                    setGraphic(box);
                }
            }
        });

        TableColumn<TransferItem, TransferItem> actionCol = new TableColumn<>("STATUS");
        actionCol.setMinWidth(86);
        actionCol.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue()));
        actionCol.setCellFactory(col -> new TableCell<>() {
            private final StatusBadge badge = new StatusBadge();

            {
                badge.setCompact(true);
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

        table.getColumns().addAll(List.of(fileCol, peerCol, sizeCol, progressCol, actionCol));
        return table;
    }

    private void applyFilter() {
        if (filteredActive == null) {
            return;
        }
        String query = searchField != null && searchField.getText() != null
            ? searchField.getText().trim().toLowerCase(Locale.ROOT)
            : "";
        filteredActive.setPredicate(item ->
            query.isEmpty()
                || item.fileName().toLowerCase(Locale.ROOT).contains(query)
                || item.peerAddress().toLowerCase(Locale.ROOT).contains(query)
                || item.status().displayName().toLowerCase(Locale.ROOT).contains(query)
        );
    }

    private void updateHeaderBadge() {
        if (services == null || countBadge == null) {
            return;
        }
        int count = services.transferService().getActiveTransfers().size();
        countBadge.setText(count + " Active");
        countBadge.setTone(count > 0 ? SemanticTone.TEAL : SemanticTone.NEUTRAL);
    }

    public TableView<TransferItem> getActiveTable() {
        return activeTable;
    }

    public TextField getSearchField() {
        return searchField;
    }
}
