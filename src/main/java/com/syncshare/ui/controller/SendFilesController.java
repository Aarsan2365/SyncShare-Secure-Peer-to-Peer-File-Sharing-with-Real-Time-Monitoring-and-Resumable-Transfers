package com.syncshare.ui.controller;

import com.syncshare.model.NavItemId;
import com.syncshare.model.TransferDirection;
import com.syncshare.model.TransferItem;
import com.syncshare.service.SyncShareServices;
import com.syncshare.service.TransferService;
import com.syncshare.ui.design.DesignSystem;
import com.syncshare.ui.design.EmptyStatePane;
import com.syncshare.ui.design.FeedbackBanner;
import com.syncshare.ui.design.LabeledProgressBar;
import com.syncshare.ui.design.SemanticTone;
import com.syncshare.ui.design.StatusBadge;
import com.syncshare.ui.design.UiButtons;
import com.syncshare.ui.design.UiCard;
import com.syncshare.ui.design.UiIcons;
import com.syncshare.ui.design.ValidatedTextField;
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
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.util.List;

/**
 * Controller for the Send Files screen.
 * Delegates all transfer validation and background TCP/mTLS sending to TransferService.
 */
public class SendFilesController implements NavigationAware {

    @FXML
    private VBox sendFilesRoot;

    private SyncShareServices services;
    private ViewNavigator navigator;

    private ValidatedTextField peerHostField;
    private ValidatedTextField peerPortField;
    private ValidatedTextField filePathField;
    private FeedbackBanner feedbackBanner;
    private VBox outgoingListBox;
    private StatusBadge outgoingCountBadge;

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

        if (peerPortField != null && (peerPortField.getText() == null || peerPortField.getText().isBlank())) {
            peerPortField.setText(Integer.toString(services.settingsService().getSettings().listeningPort()));
        }

        services.transferService().getActiveTransfers().addListener(
            (ListChangeListener<TransferItem>) change -> renderOutgoingQueue()
        );
        renderOutgoingQueue();
    }

    @Override
    public void onNavigatedTo() {
        if (initializedContext) {
            renderOutgoingQueue();
        }
    }

    private void buildLayout() {
        if (sendFilesRoot == null) {
            return;
        }
        sendFilesRoot.getChildren().clear();

        feedbackBanner = new FeedbackBanner(
            SemanticTone.INFO,
            "Encrypted File Transfer",
            "Select a local file and target peer endpoint. All transfers enforce Mutual TLS 1.3 and SHA-256 verification.",
            true
        );

        UiCard formCard = new UiCard(UiCard.Variant.DEFAULT);
        formCard.setMinWidth(0);
        formCard.setHeader(
            "Prepare Outgoing Transfer",
            "Specify target peer host, listening port, and local file",
            UiIcons.create(UiIcons.IconType.SEND, UiIcons.Size.LG, SemanticTone.TEAL),
            new StatusBadge("mTLS 1.3", SemanticTone.TEAL)
        );

        peerHostField = new ValidatedTextField(
            "Peer IPv4 Address or Hostname",
            "e.g. 192.168.1.42",
            "Enter the recipient peer's LAN IPv4 address or hostname"
        );

        peerPortField = new ValidatedTextField(
            "Target Port",
            "5050",
            "Default SyncShare receiver port is 5050"
        );
        peerPortField.setText("5050");

        filePathField = new ValidatedTextField(
            "Local File Path",
            "Choose a file to send...",
            "Files are streamed in 64 KB chunks with checkpoint resume support"
        );
        filePathField.setMinWidth(0);
        HBox.setHgrow(filePathField, Priority.ALWAYS);

        Button browseBtn = UiButtons.create(
            "Browse...",
            UiIcons.create(UiIcons.IconType.FOLDER, UiIcons.Size.SM),
            UiButtons.Variant.SECONDARY,
            UiButtons.Size.MD
        );
        browseBtn.setOnAction(event -> browseForFile());

        HBox filePickerRow = new HBox(DesignSystem.SPACE_XS, filePathField, browseBtn);
        filePickerRow.getStyleClass().add("form-row");
        filePickerRow.setAlignment(Pos.BOTTOM_LEFT);

        Button validateBtn = UiButtons.create(
            "Validate",
            UiIcons.create(UiIcons.IconType.CHECK_CIRCLE, UiIcons.Size.SM),
            UiButtons.Variant.OUTLINE_TEAL,
            UiButtons.Size.SM
        );
        validateBtn.setId("sendValidateButton");
        validateBtn.setOnAction(event -> validateInputs());

        Button clearBtn = UiButtons.create("Clear", UiButtons.Variant.SECONDARY, UiButtons.Size.SM);
        clearBtn.setId("sendClearButton");
        clearBtn.setOnAction(event -> clearForm());

        Button sendBtn = UiButtons.create(
            "Send File",
            UiIcons.create(UiIcons.IconType.SEND, UiIcons.Size.SM),
            UiButtons.Variant.PRIMARY,
            UiButtons.Size.SM
        );
        sendBtn.setId("sendSubmitButton");
        sendBtn.setOnAction(event -> handleSendFile());

        FlowPane footerActions = new FlowPane(DesignSystem.SPACE_XS, DesignSystem.SPACE_XS);
        footerActions.setAlignment(Pos.CENTER_RIGHT);
        footerActions.getChildren().addAll(clearBtn, validateBtn, sendBtn);

        formCard.setBodyContent(peerHostField, peerPortField, filePickerRow);
        formCard.setFooterContent(footerActions);

        UiCard queueCard = new UiCard(UiCard.Variant.DEFAULT);
        queueCard.setMinWidth(0);

        outgoingCountBadge = new StatusBadge("0 Outgoing", SemanticTone.NEUTRAL);
        outgoingCountBadge.setCompact(true);

        Button activeViewBtn = UiButtons.create("Active Transfers", UiButtons.Variant.GHOST, UiButtons.Size.SM);
        activeViewBtn.setOnAction(event -> {
            if (navigator != null) {
                navigator.navigateTo(NavItemId.ACTIVE_TRANSFERS);
            }
        });

        HBox queueTrailing = new HBox(DesignSystem.SPACE_XS, outgoingCountBadge, activeViewBtn);
        queueTrailing.setAlignment(Pos.CENTER_RIGHT);

        queueCard.setHeader(
            "Outgoing Queue",
            "Live status of transfers started from this device",
            UiIcons.create(UiIcons.IconType.ACTIVITY, UiIcons.Size.LG, SemanticTone.TEAL),
            queueTrailing
        );

        outgoingListBox = new VBox(DesignSystem.SPACE_XS);
        outgoingListBox.setMinWidth(0);
        queueCard.setBodyContent(outgoingListBox);

        sendFilesRoot.getChildren().addAll(feedbackBanner, formCard, queueCard);
    }

    private void browseForFile() {
        if (Boolean.getBoolean("syncshare.ui.suppressDialogs")) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select File to Send via SyncShare");
        Window owner = sendFilesRoot.getScene() != null ? sendFilesRoot.getScene().getWindow() : null;
        File chosen = chooser.showOpenDialog(owner);
        if (chosen != null) {
            filePathField.setText(chosen.getAbsolutePath());
            filePathField.setValidation(SemanticTone.SUCCESS, "Selected: " + chosen.getName());
        }
    }

    private TransferService.ValidationResult validateInputs() {
        if (services == null) {
            return null;
        }
        TransferService.ValidationResult result = services.transferService().validateSendInput(
            peerHostField.getText(),
            peerPortField.getText(),
            filePathField.getText()
        );

        if (result.peerMessage() != null) {
            peerHostField.setValidation(SemanticTone.ERROR, result.peerMessage());
        } else {
            peerHostField.setValidation(SemanticTone.SUCCESS, "Peer endpoint syntax valid.");
        }

        if (result.portMessage() != null) {
            peerPortField.setValidation(SemanticTone.ERROR, result.portMessage());
        } else {
            peerPortField.setValidation(SemanticTone.SUCCESS, "TCP port " + result.port() + " valid.");
        }

        if (result.fileMessage() != null) {
            filePathField.setValidation(SemanticTone.ERROR, result.fileMessage());
        } else {
            filePathField.setValidation(SemanticTone.SUCCESS, "File ready for transfer.");
        }

        if (result.valid()) {
            feedbackBanner.setTone(SemanticTone.SUCCESS)
                .setTitle("Preflight Validated")
                .setMessage("Peer and file inputs are valid and ready to transmit.");
            feedbackBanner.show();
        } else {
            feedbackBanner.setTone(SemanticTone.ERROR)
                .setTitle("Validation Required")
                .setMessage("Correct the highlighted fields before starting a transfer.");
            feedbackBanner.show();
        }
        return result;
    }

    private void handleSendFile() {
        TransferService.ValidationResult check = validateInputs();
        if (check == null || !check.valid()) {
            return;
        }
        if (!services.deviceInfoService().isTlsConfigured()) {
            feedbackBanner.setTone(SemanticTone.WARNING)
                .setTitle("TLS Credentials Needed")
                .setMessage("Configure SYNCSHARE_TLS_PASSWORD and peer certificates (certs/identity.p12) before sending.");
            feedbackBanner.show();
            return;
        }

        String host = peerHostField.getText().trim();
        services.transferService().sendFile(host, check.port(), check.file())
            .thenAccept(result -> javafx.application.Platform.runLater(() -> {
                if (result.status() == com.syncshare.model.TransferStatus.COMPLETED) {
                    feedbackBanner.setTone(SemanticTone.SUCCESS)
                        .setTitle("Transfer Verified")
                        .setMessage(result.fileName() + " sent and verified with SHA-256.");
                } else {
                    feedbackBanner.setTone(SemanticTone.ERROR)
                        .setTitle("Transfer Failed")
                        .setMessage(result.note());
                }
                feedbackBanner.show();
            }));

        feedbackBanner.setTone(SemanticTone.INFO)
            .setTitle("Transfer Queued")
            .setMessage("Queued " + check.file().getFileName() + " to " + host + ":" + check.port() + ".");
        feedbackBanner.show();
    }

    private void clearForm() {
        peerHostField.setText("");
        peerHostField.clearValidation();
        if (services != null) {
            peerPortField.setText(Integer.toString(services.settingsService().getSettings().listeningPort()));
        } else {
            peerPortField.setText("5050");
        }
        peerPortField.clearValidation();
        filePathField.setText("");
        filePathField.clearValidation();
        feedbackBanner.setTone(SemanticTone.INFO)
            .setTitle("Encrypted File Transfer")
            .setMessage("Form cleared. Select a target peer and local file to begin.");
        feedbackBanner.show();
    }

    private void renderOutgoingQueue() {
        if (services == null || outgoingListBox == null) {
            return;
        }
        List<TransferItem> outgoing = services.transferService().getActiveTransfers().stream()
            .filter(item -> item.direction() == TransferDirection.OUTGOING)
            .toList();

        outgoingCountBadge.setText(outgoing.size() + " Outgoing");
        outgoingCountBadge.setTone(outgoing.isEmpty() ? SemanticTone.NEUTRAL : SemanticTone.TEAL);

        if (outgoing.isEmpty()) {
            EmptyStatePane empty = new EmptyStatePane(
                UiIcons.IconType.SEND,
                SemanticTone.TEAL,
                "No outgoing transfers queued",
                "Enter a peer address and file above to send an encrypted file over Mutual TLS 1.3."
            ).setCompact(true);
            outgoingListBox.getChildren().setAll(empty);
            return;
        }

        outgoingListBox.getChildren().clear();
        for (TransferItem item : outgoing) {
            VBox row = new VBox(6);
            row.getStyleClass().add("transfer-summary-row");
            HBox top = new HBox(DesignSystem.SPACE_XS);
            top.setAlignment(Pos.CENTER_LEFT);
            Label title = new Label(item.fileName() + " → " + item.peerAddress());
            title.getStyleClass().add("transfer-summary-title");
            title.setWrapText(true);
            title.setMinWidth(0);
            HBox.setHgrow(title, Priority.ALWAYS);
            StatusBadge badge = new StatusBadge(item.status().displayName(), item.status().tone());
            badge.setCompact(true);
            top.getChildren().addAll(title, badge);

            LabeledProgressBar bar = new LabeledProgressBar(
                item.note(),
                item.progress(),
                item.status().tone()
            ).setSize(LabeledProgressBar.Size.SM)
             .setDetail(item.formattedProgressDetail());

            row.getChildren().addAll(top, bar);
            outgoingListBox.getChildren().add(row);
        }
    }
}
