package com.syncshare.ui.controller;

import com.syncshare.model.ConnectionStatus;
import com.syncshare.model.IncomingRequestItem;
import com.syncshare.model.NavItemId;
import com.syncshare.service.ConnectionService;
import com.syncshare.service.SyncShareServices;
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
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * Controller for the Receive Files screen.
 * Manages background listener controls and incoming transfer approval requests via ConnectionService.
 */
public class ReceiveFilesController implements NavigationAware {

    @FXML
    private VBox receiveFilesRoot;

    private SyncShareServices services;
    private ViewNavigator navigator;

    private FeedbackBanner feedbackBanner;
    private StatusBadge listenerBadge;
    private Label listenerHeadlineLabel;
    private Label listenerDetailLabel;
    private Button toggleListenerButton;
    private StatusBadge queueCountBadge;
    private VBox requestsListBox;

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

        services.connectionService().statusProperty().addListener((obs, oldVal, newVal) -> updateListenerCard(newVal));
        services.connectionService().getPendingRequests().addListener(
            (ListChangeListener<IncomingRequestItem>) change -> renderPendingRequests()
        );

        updateListenerCard(services.connectionService().getStatus());
        renderPendingRequests();
    }

    @Override
    public void onNavigatedTo() {
        if (initializedContext) {
            services.connectionService().refreshStatus();
            updateListenerCard(services.connectionService().getStatus());
            renderPendingRequests();
        }
    }

    private void buildLayout() {
        if (receiveFilesRoot == null) {
            return;
        }
        receiveFilesRoot.getChildren().clear();

        feedbackBanner = new FeedbackBanner(
            SemanticTone.INFO,
            "Incoming Transfer Controls",
            "Incoming peer offers require explicit user approval when manual approval is enabled in Settings.",
            true
        );

        UiCard listenerCard = new UiCard(UiCard.Variant.DEFAULT);
        listenerCard.setMinWidth(0);

        listenerBadge = new StatusBadge("Standby", SemanticTone.INFO);
        listenerCard.setHeader(
            "Background TCP / mTLS Receiver",
            "Accept authenticated incoming file offers",
            UiIcons.create(UiIcons.IconType.RECEIVE, UiIcons.Size.LG, SemanticTone.TEAL),
            listenerBadge
        );

        listenerHeadlineLabel = new Label("Standby • Port 5050");
        listenerHeadlineLabel.getStyleClass().add("text-section-title");
        listenerHeadlineLabel.setWrapText(true);

        listenerDetailLabel = new Label("Ready to listen when TLS credentials are available.");
        listenerDetailLabel.getStyleClass().add("text-secondary");
        listenerDetailLabel.setWrapText(true);

        Button openSettingsBtn = UiButtons.create(
            "Configure Port & Folder",
            UiIcons.create(UiIcons.IconType.SETTINGS, UiIcons.Size.SM),
            UiButtons.Variant.SECONDARY,
            UiButtons.Size.SM
        );
        openSettingsBtn.setOnAction(event -> {
            if (navigator != null) {
                navigator.navigateTo(NavItemId.SETTINGS);
            }
        });

        toggleListenerButton = UiButtons.create(
            "Start Receiver",
            UiIcons.create(UiIcons.IconType.RECEIVE, UiIcons.Size.SM),
            UiButtons.Variant.PRIMARY,
            UiButtons.Size.SM
        );
        toggleListenerButton.setOnAction(event -> handleToggleListener());

        FlowPane listenerFooter = new FlowPane(DesignSystem.SPACE_XS, DesignSystem.SPACE_XS);
        listenerFooter.setAlignment(Pos.CENTER_RIGHT);
        listenerFooter.getChildren().addAll(openSettingsBtn, toggleListenerButton);

        listenerCard.setBodyContent(listenerHeadlineLabel, listenerDetailLabel);
        listenerCard.setFooterContent(listenerFooter);

        UiCard requestsCard = new UiCard(UiCard.Variant.DEFAULT);
        requestsCard.setMinWidth(0);

        queueCountBadge = new StatusBadge("0 Pending", SemanticTone.NEUTRAL);
        queueCountBadge.setCompact(true);
        requestsCard.setHeader(
            "Pending Approval Queue",
            "Inspect incoming file metadata before accepting bytes",
            UiIcons.create(UiIcons.IconType.SHIELD_CHECK, UiIcons.Size.LG, SemanticTone.TEAL),
            queueCountBadge
        );

        requestsListBox = new VBox(DesignSystem.SPACE_XS);
        requestsListBox.setMinWidth(0);
        requestsCard.setBodyContent(requestsListBox);

        receiveFilesRoot.getChildren().addAll(feedbackBanner, listenerCard, requestsCard);
    }

    private void handleToggleListener() {
        if (services == null) {
            return;
        }
        ConnectionService connection = services.connectionService();
        if (connection.isListening()) {
            connection.stopReceiver();
            feedbackBanner.setTone(SemanticTone.INFO)
                .setTitle("Receiver Stopped")
                .setMessage("Incoming peer connections are paused.");
            feedbackBanner.show();
        } else {
            boolean ok = connection.startReceiver();
            ConnectionStatus status = connection.getStatus();
            feedbackBanner.setTone(ok ? SemanticTone.SUCCESS : status.state().tone())
                .setTitle(status.headline())
                .setMessage(status.detail());
            feedbackBanner.show();
        }
    }

    private void updateListenerCard(ConnectionStatus status) {
        if (status == null) {
            return;
        }
        listenerBadge.setText(status.state().displayName());
        listenerBadge.setTone(status.state().tone());
        listenerHeadlineLabel.setText(status.headline());
        listenerDetailLabel.setText(status.detail());

        boolean listening = services != null && services.connectionService().isListening();
        toggleListenerButton.setText(listening ? "Stop Receiver" : "Start Receiver");
        UiButtons.apply(
            toggleListenerButton,
            listening ? UiButtons.Variant.DANGER_OUTLINE : UiButtons.Variant.PRIMARY,
            UiButtons.Size.SM
        );
    }

    private void renderPendingRequests() {
        if (services == null || requestsListBox == null) {
            return;
        }
        List<IncomingRequestItem> requests = services.connectionService().getPendingRequests();
        queueCountBadge.setText(requests.size() + " Pending");
        queueCountBadge.setTone(requests.isEmpty() ? SemanticTone.NEUTRAL : SemanticTone.WARNING);

        if (requests.isEmpty()) {
            EmptyStatePane empty = new EmptyStatePane(
                UiIcons.IconType.RECEIVE,
                SemanticTone.INFO,
                "No pending incoming requests",
                "When an authenticated peer offers a file, its details and Accept/Reject controls appear here."
            );
            requestsListBox.getChildren().setAll(empty);
            return;
        }

        requestsListBox.getChildren().clear();
        for (IncomingRequestItem req : requests) {
            HBox row = new HBox(DesignSystem.SPACE_SM);
            row.getStyleClass().add("transfer-summary-row");
            row.setAlignment(Pos.CENTER_LEFT);

            VBox info = new VBox(2);
            info.setMinWidth(0);
            HBox.setHgrow(info, Priority.ALWAYS);

            Label title = new Label("#" + req.requestId() + " • " + req.fileName());
            title.getStyleClass().add("transfer-summary-title");
            title.setWrapText(true);

            Label meta = new Label(
                "From " + req.senderAddress() + " • " + req.formattedSize() + " • " + req.formattedTime()
            );
            meta.getStyleClass().add("transfer-summary-meta");
            meta.setWrapText(true);
            info.getChildren().addAll(title, meta);

            Button rejectBtn = UiButtons.create("Reject", UiButtons.Variant.DANGER_OUTLINE, UiButtons.Size.SM);
            rejectBtn.setOnAction(e -> services.connectionService().approveRequest(req.requestId(), false));

            Button acceptBtn = UiButtons.create("Accept", UiButtons.Variant.SUCCESS, UiButtons.Size.SM);
            acceptBtn.setOnAction(e -> services.connectionService().approveRequest(req.requestId(), true));

            HBox actions = new HBox(DesignSystem.SPACE_XS, rejectBtn, acceptBtn);
            actions.setAlignment(Pos.CENTER_RIGHT);

            row.getChildren().addAll(info, actions);
            requestsListBox.getChildren().add(row);
        }
    }
}
