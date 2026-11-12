package com.syncshare.ui.controller;

import com.syncshare.model.ConnectionStatus;
import com.syncshare.model.NavItemId;
import com.syncshare.model.TransferDirection;
import com.syncshare.model.TransferItem;
import com.syncshare.model.TransferStatus;
import com.syncshare.service.OutgoingTransferGateway;
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
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/**
 * Controller for the Send Files screen.
 * Coordinates file selection, input validation, connection readiness display,
 * and background outgoing transfers via {@link OutgoingTransferGateway} and
 * {@link SendFileTransferService}.
 */
public class SendFilesController implements NavigationAware {

    @FXML
    private VBox sendFilesRoot;

    private final SendFileTransferService sendTransferService = new SendFileTransferService();

    private SyncShareServices services;
    private ViewNavigator navigator;
    private OutgoingTransferGateway transferGateway;

    private FeedbackBanner feedbackBanner;
    private GridPane endpointGrid;
    private ValidatedTextField peerHostField;
    private ValidatedTextField peerPortField;
    private ValidatedTextField filePathField;
    private Button browseButton;

    private HBox selectedFileSummaryBox;
    private StackPane selectedFileIconHolder;
    private Label selectedFileNameLabel;
    private Label selectedFileMetaLabel;
    private Label selectedFileSizeLabel;
    private StatusBadge selectedFileStatusBadge;

    private Button clearButton;
    private Button validateButton;
    private Button cancelButton;
    private Button sendButton;

    private StatusBadge sendConnectionStatusBadge;
    private Label sendConnectionStatusLabel;
    private Label sendTargetEndpointLabel;
    private Label sendStageDetailLabel;
    private LabeledProgressBar sendProgressBar;

    private VBox outgoingListBox;
    private StatusBadge outgoingCountBadge;

    private Function<Window, File> fileChooserHandler;
    private boolean initializedContext;
    private boolean sendSubmissionActive;
    private boolean showingTransferOutcome;
    private boolean programmaticPortUpdate;
    private boolean portEditedByUser;
    private String activeTargetHost = "";
    private int activeTargetPort;

    @FXML
    public void initialize() {
        buildLayout();
        configureResponsiveReflow();
        configureTransferServiceBindings();
    }

    @Override
    public void initializeContext(SyncShareServices services, ViewNavigator navigator) {
        if (initializedContext) {
            return;
        }
        this.services = Objects.requireNonNull(services, "services");
        this.navigator = navigator;
        this.transferGateway = services.transferService();
        this.initializedContext = true;

        if (!portEditedByUser && peerPortField != null) {
            setPortProgrammatically(Integer.toString(services.settingsService().getSettings().listeningPort()));
        }

        services.transferService().getActiveTransfers().addListener(
            (ListChangeListener<TransferItem>) change -> renderOutgoingQueue()
        );
        services.connectionService().statusProperty().addListener((obs, oldVal, newVal) -> {
            if (!isSubmissionRunning() && !showingTransferOutcome) {
                refreshIdleConnectionStatus();
            }
        });
        services.deviceInfoService().deviceInfoProperty().addListener((obs, oldVal, newVal) -> {
            if (!isSubmissionRunning() && !showingTransferOutcome) {
                refreshIdleConnectionStatus();
            }
        });

        updateSelectedFileSummary(filePathField.getText());
        refreshIdleConnectionStatus();
        renderOutgoingQueue();
    }

    @Override
    public void onNavigatedTo() {
        if (!initializedContext) {
            return;
        }
        services.deviceInfoService().refresh();
        if (!isSubmissionRunning()) {
            updateSelectedFileSummary(filePathField.getText());
            if (!showingTransferOutcome) {
                refreshIdleConnectionStatus();
            }
        }
        renderOutgoingQueue();
    }

    /**
     * Installs an optional custom file chooser strategy (used for deterministic testing).
     */
    public void setFileChooserHandler(Function<Window, File> fileChooserHandler) {
        this.fileChooserHandler = fileChooserHandler;
    }

    /**
     * Creates the configured single-file JavaFX {@link FileChooser}.
     * Protocol v2 transmits a single file per peer connection, so multi-file
     * selection is intentionally disallowed.
     */
    public FileChooser buildFileChooser() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select File to Send via SyncShare");
        chooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("All Files (*.*)", "*.*")
        );

        String currentPath = filePathField != null ? filePathField.getText() : "";
        if (currentPath != null && !currentPath.isBlank()) {
            try {
                Path candidate = Path.of(currentPath.trim());
                Path initialDir = Files.isDirectory(candidate) ? candidate : candidate.getParent();
                if (initialDir != null && Files.isDirectory(initialDir)) {
                    chooser.setInitialDirectory(initialDir.toFile());
                }
            } catch (Exception ignored) {
            }
        }
        return chooser;
    }

    public SendFileTransferService getSendTransferService() {
        return sendTransferService;
    }

    public boolean isTransferInProgress() {
        return isSubmissionRunning();
    }

    private void buildLayout() {
        if (sendFilesRoot == null) {
            return;
        }
        sendFilesRoot.getChildren().clear();

        feedbackBanner = new FeedbackBanner(
            SemanticTone.INFO,
            "Encrypted File Transfer",
            "Select a local file and destination peer endpoint. SyncShare transfers one file per connection over Mutual TLS 1.3 with SHA-256 verification.",
            true
        );
        feedbackBanner.setId("sendFeedbackBanner");

        UiCard formCard = buildFormCard();
        UiCard statusCard = buildStatusCard();
        UiCard queueCard = buildOutgoingQueueCard();

        sendFilesRoot.getChildren().addAll(feedbackBanner, formCard, statusCard, queueCard);
        applyResponsiveEndpointGrid(sendFilesRoot.getWidth());
    }

    private UiCard buildFormCard() {
        UiCard formCard = new UiCard(UiCard.Variant.DEFAULT);
        formCard.setMinWidth(0);

        StatusBadge protocolBadge = new StatusBadge("Single File • mTLS 1.3", SemanticTone.TEAL);
        protocolBadge.setCompact(true);
        formCard.setHeader(
            "Prepare Outgoing Transfer",
            "Specify the recipient IPv4 address or hostname, TCP port, and local file",
            UiIcons.create(UiIcons.IconType.SEND, UiIcons.Size.LG, SemanticTone.TEAL),
            protocolBadge
        );

        peerHostField = new ValidatedTextField(
            "Destination IPv4 Address or Hostname",
            "e.g. 192.168.1.42",
            "Enter the recipient peer's IPv4 address or hostname"
        );
        peerHostField.setId("sendPeerHostField");
        peerHostField.setMinWidth(0);
        peerHostField.getTextField().textProperty().addListener((obs, oldVal, newVal) -> {
            if (!isSubmissionRunning()) {
                if (peerHostField.getValidationTone() != SemanticTone.NEUTRAL) {
                    peerHostField.clearValidation();
                }
                if (!showingTransferOutcome) {
                    refreshIdleConnectionStatus();
                }
            }
        });

        peerPortField = new ValidatedTextField(
            "Destination Port",
            "5050",
            "TCP port (1 - 65535; default SyncShare port is 5050)"
        );
        peerPortField.setId("sendPeerPortField");
        peerPortField.setMinWidth(0);
        setPortProgrammatically("5050");
        peerPortField.getTextField().textProperty().addListener((obs, oldVal, newVal) -> {
            if (!programmaticPortUpdate) {
                portEditedByUser = true;
            }
            if (!isSubmissionRunning()) {
                if (peerPortField.getValidationTone() != SemanticTone.NEUTRAL) {
                    peerPortField.clearValidation();
                }
                if (!showingTransferOutcome) {
                    refreshIdleConnectionStatus();
                }
            }
        });

        endpointGrid = new GridPane();
        endpointGrid.setHgap(DesignSystem.SPACE_SM);
        endpointGrid.setVgap(DesignSystem.SPACE_SM);
        endpointGrid.getChildren().addAll(peerHostField, peerPortField);

        filePathField = new ValidatedTextField(
            "Local File Path",
            "Select a file to send...",
            "Single file selection only (64 KB chunks, checkpoint resume, max 1.00 GB)"
        );
        filePathField.setId("sendFilePathField");
        filePathField.setMinWidth(0);
        HBox.setHgrow(filePathField, Priority.ALWAYS);
        filePathField.getTextField().textProperty().addListener((obs, oldVal, newVal) -> {
            if (!isSubmissionRunning()) {
                if (filePathField.getValidationTone() != SemanticTone.NEUTRAL) {
                    filePathField.clearValidation();
                }
                updateSelectedFileSummary(newVal);
            }
        });

        browseButton = UiButtons.create(
            "Browse...",
            UiIcons.create(UiIcons.IconType.FOLDER, UiIcons.Size.SM),
            UiButtons.Variant.SECONDARY,
            UiButtons.Size.MD
        );
        browseButton.setId("sendBrowseButton");
        browseButton.setMinWidth(Region.USE_PREF_SIZE);
        browseButton.setOnAction(event -> browseForFile());

        HBox filePickerRow = new HBox(DesignSystem.SPACE_XS, filePathField, browseButton);
        filePickerRow.getStyleClass().add("form-row");
        filePickerRow.setAlignment(Pos.BOTTOM_LEFT);

        selectedFileSummaryBox = buildSelectedFileSummaryBox();

        clearButton = UiButtons.create("Clear", UiButtons.Variant.SECONDARY, UiButtons.Size.SM);
        clearButton.setId("sendClearButton");
        clearButton.setOnAction(event -> clearForm());

        validateButton = UiButtons.create(
            "Validate",
            UiIcons.create(UiIcons.IconType.CHECK_CIRCLE, UiIcons.Size.SM),
            UiButtons.Variant.OUTLINE_TEAL,
            UiButtons.Size.SM
        );
        validateButton.setId("sendValidateButton");
        validateButton.setOnAction(event -> validateInputs());

        cancelButton = UiButtons.create(
            "Cancel",
            UiIcons.create(UiIcons.IconType.CLOSE, UiIcons.Size.SM),
            UiButtons.Variant.DANGER_OUTLINE,
            UiButtons.Size.SM
        );
        cancelButton.setId("sendCancelButton");
        cancelButton.setDisable(true);
        cancelButton.setOnAction(event -> handleCancelTransfer());

        sendButton = UiButtons.create(
            "Send File",
            UiIcons.create(UiIcons.IconType.SEND, UiIcons.Size.SM),
            UiButtons.Variant.PRIMARY,
            UiButtons.Size.SM
        );
        sendButton.setId("sendSubmitButton");
        sendButton.setOnAction(event -> handleSendFile());

        FlowPane footerActions = new FlowPane(DesignSystem.SPACE_XS, DesignSystem.SPACE_XS);
        footerActions.setAlignment(Pos.CENTER_RIGHT);
        footerActions.getChildren().addAll(clearButton, validateButton, cancelButton, sendButton);

        formCard.setBodyContent(endpointGrid, filePickerRow, selectedFileSummaryBox);
        formCard.setFooterContent(footerActions);
        return formCard;
    }

    private HBox buildSelectedFileSummaryBox() {
        HBox box = new HBox(DesignSystem.SPACE_SM);
        box.setId("selectedFileSummaryBox");
        box.getStyleClass().add("send-file-summary");
        box.setAlignment(Pos.CENTER_LEFT);
        box.setMinWidth(0);

        selectedFileIconHolder = new StackPane(
            UiIcons.create(UiIcons.IconType.FILE, UiIcons.Size.LG, SemanticTone.NEUTRAL)
        );

        selectedFileNameLabel = new Label("No file selected");
        selectedFileNameLabel.setId("selectedFileNameLabel");
        selectedFileNameLabel.getStyleClass().add("transfer-summary-title");
        selectedFileNameLabel.setWrapText(true);
        selectedFileNameLabel.setMinWidth(0);

        selectedFileMetaLabel = new Label("Select a local file using Browse... or enter a file path.");
        selectedFileMetaLabel.setId("selectedFileMetaLabel");
        selectedFileMetaLabel.getStyleClass().add("transfer-summary-meta");
        selectedFileMetaLabel.setWrapText(true);
        selectedFileMetaLabel.setMinWidth(0);

        VBox infoCol = new VBox(2, selectedFileNameLabel, selectedFileMetaLabel);
        infoCol.setMinWidth(0);
        HBox.setHgrow(infoCol, Priority.ALWAYS);

        selectedFileSizeLabel = new Label("--");
        selectedFileSizeLabel.setId("selectedFileSizeLabel");
        selectedFileSizeLabel.getStyleClass().add("send-file-size");
        selectedFileSizeLabel.setMinWidth(Region.USE_PREF_SIZE);

        selectedFileStatusBadge = new StatusBadge("No File", SemanticTone.NEUTRAL);
        selectedFileStatusBadge.setId("selectedFileStatusBadge");
        selectedFileStatusBadge.setCompact(true);

        HBox trailingCol = new HBox(DesignSystem.SPACE_XS, selectedFileSizeLabel, selectedFileStatusBadge);
        trailingCol.setAlignment(Pos.CENTER_RIGHT);
        trailingCol.setMinWidth(Region.USE_PREF_SIZE);

        box.getChildren().addAll(selectedFileIconHolder, infoCol, trailingCol);
        return box;
    }

    private UiCard buildStatusCard() {
        UiCard statusCard = new UiCard(UiCard.Variant.DEFAULT);
        statusCard.setMinWidth(0);

        sendConnectionStatusBadge = new StatusBadge("Standby", SemanticTone.INFO);
        sendConnectionStatusBadge.setId("sendConnectionStatusBadge");
        statusCard.setHeader(
            "Connection & Transfer Status",
            "Real-time connection stage and peer-acknowledged transfer progress",
            UiIcons.create(UiIcons.IconType.WIFI, UiIcons.Size.LG, SemanticTone.TEAL),
            sendConnectionStatusBadge
        );

        sendConnectionStatusLabel = new Label("Ready to Connect (Standby)");
        sendConnectionStatusLabel.setId("sendConnectionStatusLabel");
        sendConnectionStatusLabel.getStyleClass().add("text-section-title");
        sendConnectionStatusLabel.setWrapText(true);
        sendConnectionStatusLabel.setMinWidth(0);

        sendTargetEndpointLabel = new Label("Target: -- • Protocol v2 (Single File • mTLS 1.3)");
        sendTargetEndpointLabel.setId("sendTargetEndpointLabel");
        sendTargetEndpointLabel.getStyleClass().add("text-mono-sm");
        sendTargetEndpointLabel.setWrapText(true);
        sendTargetEndpointLabel.setMinWidth(0);

        sendStageDetailLabel = new Label("Enter a destination peer and local file, then click Send File.");
        sendStageDetailLabel.setId("sendStageDetailLabel");
        sendStageDetailLabel.getStyleClass().add("text-secondary");
        sendStageDetailLabel.setWrapText(true);
        sendStageDetailLabel.setMinWidth(0);

        sendProgressBar = new LabeledProgressBar("Transfer Progress", 0.0, SemanticTone.INFO)
            .setSize(LabeledProgressBar.Size.MD)
            .setStatusText("Idle");
        sendProgressBar.setId("sendProgressBar");

        VBox panel = new VBox(DesignSystem.SPACE_XS,
            sendConnectionStatusLabel,
            sendTargetEndpointLabel,
            sendStageDetailLabel,
            sendProgressBar
        );
        panel.getStyleClass().add("send-status-panel");
        panel.setMinWidth(0);

        statusCard.setBodyContent(panel);
        return statusCard;
    }

    private UiCard buildOutgoingQueueCard() {
        UiCard queueCard = new UiCard(UiCard.Variant.DEFAULT);
        queueCard.setMinWidth(0);

        outgoingCountBadge = new StatusBadge("0 Outgoing", SemanticTone.NEUTRAL);
        outgoingCountBadge.setId("outgoingCountBadge");
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
            "Live status of outgoing transfers active on this device",
            UiIcons.create(UiIcons.IconType.ACTIVITY, UiIcons.Size.LG, SemanticTone.TEAL),
            queueTrailing
        );

        outgoingListBox = new VBox(DesignSystem.SPACE_XS);
        outgoingListBox.setId("outgoingListBox");
        outgoingListBox.setMinWidth(0);
        queueCard.setBodyContent(outgoingListBox);
        return queueCard;
    }

    private void configureTransferServiceBindings() {
        sendTransferService.stageStatusProperty().addListener((obs, oldVal, newVal) -> {
            if (sendTransferService.isRunning()) {
                syncLiveStageUi();
            }
        });
        sendTransferService.stageHeadlineProperty().addListener((obs, oldVal, newVal) -> {
            if (sendTransferService.isRunning()) {
                syncLiveStageUi();
            }
        });
        sendTransferService.stageDetailProperty().addListener((obs, oldVal, newVal) -> {
            if (sendTransferService.isRunning()) {
                syncLiveStageUi();
            }
        });
        sendTransferService.transferredBytesProperty().addListener((obs, oldVal, newVal) -> {
            if (sendTransferService.isRunning()) {
                syncLiveProgressUi();
            }
        });
        sendTransferService.totalBytesProperty().addListener((obs, oldVal, newVal) -> {
            if (sendTransferService.isRunning()) {
                syncLiveProgressUi();
            }
        });

        sendTransferService.setOnSucceeded(event -> {
            TransferItem result = sendTransferService.getValue();
            finishSubmission();
            handleTransferSucceeded(result);
        });

        sendTransferService.setOnFailed(event -> {
            Throwable error = sendTransferService.getException();
            finishSubmission();
            handleTransferFailed(error);
        });

        sendTransferService.setOnCancelled(event -> {
            finishSubmission();
            handleTransferCancelled();
        });
    }

    private void syncLiveStageUi() {
        TransferStatus stage = sendTransferService.stageStatusProperty().get();
        String headline = sendTransferService.stageHeadlineProperty().get();
        String detail = sendTransferService.stageDetailProperty().get();

        if (stage == TransferStatus.QUEUED || stage == TransferStatus.CONNECTING) {
            sendConnectionStatusBadge.setText("Connecting");
            sendConnectionStatusBadge.setTone(SemanticTone.INFO);
            sendProgressBar.setTone(SemanticTone.INFO);
        } else if (stage == TransferStatus.TRANSFERRING) {
            boolean verifying = headline != null && headline.startsWith("Verifying");
            sendConnectionStatusBadge.setText(verifying ? "Verifying" : "Transferring");
            sendConnectionStatusBadge.setTone(SemanticTone.TEAL);
            sendProgressBar.setTone(SemanticTone.TEAL);
        }

        if (headline != null && !headline.isBlank()) {
            sendConnectionStatusLabel.setText(headline);
        }
        if (detail != null && !detail.isBlank()) {
            sendStageDetailLabel.setText(detail);
        }
        syncLiveProgressUi();
    }

    private void syncLiveProgressUi() {
        TransferStatus stage = sendTransferService.stageStatusProperty().get();
        String headline = sendTransferService.stageHeadlineProperty().get();
        long transferred = sendTransferService.transferredBytesProperty().get();
        long total = sendTransferService.totalBytesProperty().get();

        if (stage == TransferStatus.QUEUED || stage == TransferStatus.CONNECTING) {
            sendProgressBar.setTitle("Establishing Mutual TLS 1.3 session & peer approval");
            sendProgressBar.setProgress(-1.0);
            sendProgressBar.setStatusText("Waiting...");
            sendProgressBar.setDetail("Progress percentage starts once the peer accepts and requests resume offset.");
            return;
        }

        if (stage == TransferStatus.TRANSFERRING) {
            boolean verifying = headline != null && headline.startsWith("Verifying");
            if (verifying) {
                sendProgressBar.setTitle("Verifying SHA-256 Integrity");
                sendProgressBar.setProgress(total > 0 ? 1.0 : -1.0);
                sendProgressBar.setStatusText("Verifying...");
                sendProgressBar.setDetail(
                    TransferItem.formatByteCount(transferred) + " of " + TransferItem.formatByteCount(total)
                        + " acknowledged • Waiting for peer SHA-256 verification"
                );
            } else if (total > 0) {
                double ratio = Math.max(0.0, Math.min(1.0, (double) transferred / (double) total));
                int pct = (int) Math.round(ratio * 100.0);
                sendProgressBar.setTitle("Streaming Encrypted Chunks");
                sendProgressBar.setProgress(ratio);
                sendProgressBar.setStatusText(pct + "%");
                sendProgressBar.setDetail(
                    TransferItem.formatByteCount(transferred) + " of " + TransferItem.formatByteCount(total)
                        + " acknowledged"
                );
            } else {
                sendProgressBar.setTitle("Transferring");
                sendProgressBar.setProgress(-1.0);
                sendProgressBar.setStatusText("Working...");
                sendProgressBar.setDetail("Awaiting peer confirmation...");
            }
        }
    }

    private void handleTransferSucceeded(TransferItem result) {
        showingTransferOutcome = true;
        if (result != null && result.status() == TransferStatus.COMPLETED) {
            long size = result.totalBytes();
            sendConnectionStatusBadge.setText("Verified");
            sendConnectionStatusBadge.setTone(SemanticTone.SUCCESS);
            sendConnectionStatusLabel.setText("Verified & Completed");
            sendStageDetailLabel.setText(
                result.fileName() + " was transferred to " + result.peerAddress()
                    + " and verified with SHA-256."
            );
            sendProgressBar.setTone(SemanticTone.SUCCESS)
                .setTitle("Transfer Verified")
                .setProgress(1.0)
                .setStatusText("100%")
                .setDetail(
                    (size > 0 ? TransferItem.formatByteCount(size) + " verified • " : "")
                        + "Peer confirmed SHA-256 integrity."
                );

            showBanner(
                SemanticTone.SUCCESS,
                "Transfer Verified",
                result.fileName() + " sent to " + result.peerAddress() + " and verified with SHA-256."
            );
            return;
        }

        if (result != null && result.status() == TransferStatus.REJECTED) {
            sendConnectionStatusBadge.setText("Rejected");
            sendConnectionStatusBadge.setTone(SemanticTone.ERROR);
            sendConnectionStatusLabel.setText("Rejected by Peer");
            sendStageDetailLabel.setText(result.note());
            sendProgressBar.setTone(SemanticTone.ERROR)
                .setTitle("Offer Declined")
                .setProgress(0.0)
                .setStatusText("Rejected")
                .setDetail("The receiving peer declined the incoming file offer.");

            showBanner(
                SemanticTone.ERROR,
                "Offer Rejected",
                result.note()
            );
            return;
        }

        if (result != null && (result.status() == TransferStatus.CANCELLED
                || (result.status() == TransferStatus.FAILED && "Cancelled by user".equals(result.note())))) {
            handleTransferCancelled();
            return;
        }

        String msg = result != null && !result.note().isBlank()
            ? result.note()
            : "Outgoing transfer did not receive verification confirmation from peer.";
        sendConnectionStatusBadge.setText("Failed");
        sendConnectionStatusBadge.setTone(SemanticTone.ERROR);
        sendConnectionStatusLabel.setText("Transfer Failed");
        sendStageDetailLabel.setText(msg);
        sendProgressBar.setTone(SemanticTone.ERROR)
            .setTitle("Transfer Incomplete")
            .setStatusText("Failed")
            .setDetail(msg);
        showBanner(SemanticTone.ERROR, "Transfer Failed", msg);
    }

    private void handleTransferFailed(Throwable error) {
        showingTransferOutcome = true;
        String message = TransferService.describeNetworkError(error, activeTargetHost, activeTargetPort);

        sendConnectionStatusBadge.setText("Failed");
        sendConnectionStatusBadge.setTone(SemanticTone.ERROR);
        sendConnectionStatusLabel.setText("Connection / Transfer Failed");
        sendStageDetailLabel.setText(message);
        sendProgressBar.setTone(SemanticTone.ERROR)
            .setTitle("Transfer Failed")
            .setStatusText("Failed")
            .setDetail(message);

        if (sendProgressBar.getProgressBar().getProgress() < 0) {
            sendProgressBar.setProgress(0.0);
            sendProgressBar.setStatusText("Failed");
        }

        showBanner(SemanticTone.ERROR, "Transfer Failed", message);
    }

    private void handleTransferCancelled() {
        showingTransferOutcome = true;
        sendConnectionStatusBadge.setText("Cancelled");
        sendConnectionStatusBadge.setTone(SemanticTone.WARNING);
        sendConnectionStatusLabel.setText("Transfer Cancelled");
        sendStageDetailLabel.setText(
            "Outgoing transfer to " + activeTargetHost + ":" + activeTargetPort + " was cancelled and cleaned up."
        );
        sendProgressBar.setTone(SemanticTone.WARNING)
            .setTitle("Transfer Cancelled")
            .setStatusText("Cancelled")
            .setDetail("Transfer cancelled before peer verification completed.");

        if (sendProgressBar.getProgressBar().getProgress() < 0) {
            sendProgressBar.setProgress(0.0);
            sendProgressBar.setStatusText("Cancelled");
        }

        showBanner(
            SemanticTone.WARNING,
            "Transfer Cancelled",
            "Outgoing transfer was cancelled and local transfer state was cleaned up."
        );
    }

    private void browseForFile() {
        if (isSubmissionRunning()) {
            return;
        }
        Window owner = sendFilesRoot != null && sendFilesRoot.getScene() != null
            ? sendFilesRoot.getScene().getWindow()
            : null;

        File chosen;
        if (fileChooserHandler != null) {
            chosen = fileChooserHandler.apply(owner);
        } else {
            if (Boolean.getBoolean("syncshare.ui.suppressDialogs")) {
                return;
            }
            chosen = buildFileChooser().showOpenDialog(owner);
        }

        if (chosen == null) {
            return;
        }

        showingTransferOutcome = false;
        filePathField.setText(chosen.getAbsolutePath());
        TransferService.FileSummary summary = updateSelectedFileSummary(chosen.getAbsolutePath());
        if (summary.valid()) {
            filePathField.setValidation(
                SemanticTone.SUCCESS,
                "Selected: " + summary.fileName() + " (" + summary.formattedSize() + ")"
            );
        } else {
            filePathField.setValidation(SemanticTone.ERROR, summary.detailMessage());
        }
    }

    private TransferService.FileSummary updateSelectedFileSummary(String pathText) {
        TransferService.FileSummary summary = transferGateway != null
            ? transferGateway.inspectSelectedFile(pathText)
            : (pathText == null || pathText.isBlank()
                ? TransferService.FileSummary.empty()
                : new TransferService().inspectSelectedFile(pathText));

        if (selectedFileNameLabel == null) {
            return summary;
        }

        selectedFileNameLabel.setText(summary.fileName());
        selectedFileSizeLabel.setText(summary.formattedSize());
        selectedFileStatusBadge.setText(summary.statusLabel());

        String safeInput = pathText != null ? pathText.trim() : "";
        if (safeInput.isEmpty()) {
            selectedFileMetaLabel.setText(summary.detailMessage());
            selectedFileStatusBadge.setTone(SemanticTone.NEUTRAL);
            selectedFileIconHolder.getChildren().setAll(
                UiIcons.create(UiIcons.IconType.FILE, UiIcons.Size.LG, SemanticTone.NEUTRAL)
            );
            selectedFileSummaryBox.getStyleClass().removeAll("send-file-summary-ready", "send-file-summary-error");
        } else if (summary.valid()) {
            String meta = summary.sizeBytes() >= 1024L
                ? String.format(Locale.US, "%,d bytes • %s", summary.sizeBytes(), summary.detailMessage())
                : summary.detailMessage();
            selectedFileMetaLabel.setText(meta);
            selectedFileStatusBadge.setTone(SemanticTone.SUCCESS);
            selectedFileIconHolder.getChildren().setAll(
                UiIcons.create(UiIcons.IconType.FILE, UiIcons.Size.LG, SemanticTone.SUCCESS)
            );
            DesignSystem.swapExclusiveClass(
                selectedFileSummaryBox,
                List.of("send-file-summary-ready", "send-file-summary-error"),
                "send-file-summary-ready"
            );
        } else {
            selectedFileMetaLabel.setText(summary.detailMessage());
            selectedFileStatusBadge.setTone(SemanticTone.ERROR);
            selectedFileIconHolder.getChildren().setAll(
                UiIcons.create(UiIcons.IconType.ALERT_CIRCLE, UiIcons.Size.LG, SemanticTone.ERROR)
            );
            DesignSystem.swapExclusiveClass(
                selectedFileSummaryBox,
                List.of("send-file-summary-ready", "send-file-summary-error"),
                "send-file-summary-error"
            );
        }
        return summary;
    }

    private TransferService.ValidationResult validateInputs() {
        if (services == null || transferGateway == null) {
            return null;
        }
        showingTransferOutcome = false;
        TransferService.ValidationResult result = transferGateway.validateSendInput(
            peerHostField.getText(),
            peerPortField.getText(),
            filePathField.getText()
        );
        updateSelectedFileSummary(filePathField.getText());

        if (result.peerMessage() != null) {
            peerHostField.setValidation(SemanticTone.ERROR, result.peerMessage());
        } else {
            peerHostField.setValidation(SemanticTone.SUCCESS, "Peer destination syntax valid.");
        }

        if (result.portMessage() != null) {
            peerPortField.setValidation(SemanticTone.ERROR, result.portMessage());
        } else {
            peerPortField.setValidation(SemanticTone.SUCCESS, "TCP port " + result.port() + " valid.");
        }

        if (result.fileMessage() != null) {
            filePathField.setValidation(SemanticTone.ERROR, result.fileMessage());
        } else {
            filePathField.setValidation(SemanticTone.SUCCESS, "Selected file ready for single-file mTLS transfer.");
        }

        if (result.valid()) {
            showBanner(
                SemanticTone.SUCCESS,
                "Preflight Validated",
                "Destination endpoint and selected file are valid and ready to transmit."
            );
        } else {
            showBanner(
                SemanticTone.ERROR,
                "Validation Required",
                buildValidationErrorSummary(result)
            );
        }
        refreshIdleConnectionStatus();
        return result;
    }

    private static String buildValidationErrorSummary(TransferService.ValidationResult result) {
        if (result.peerMessage() != null) {
            return result.peerMessage();
        }
        if (result.portMessage() != null) {
            return result.portMessage();
        }
        if (result.fileMessage() != null) {
            return result.fileMessage();
        }
        return "Correct the highlighted fields before starting a transfer.";
    }

    private void handleSendFile() {
        if (services == null || transferGateway == null || isSubmissionRunning()) {
            return;
        }

        TransferService.ValidationResult check = validateInputs();
        if (check == null || !check.valid()) {
            return;
        }

        if (!services.deviceInfoService().isTlsConfigured()) {
            sendConnectionStatusBadge.setText("TLS Setup");
            sendConnectionStatusBadge.setTone(SemanticTone.WARNING);
            sendConnectionStatusLabel.setText("TLS Credentials Required");
            sendStageDetailLabel.setText(
                "Configure SYNCSHARE_TLS_PASSWORD and Mutual TLS keystores before initiating a transfer."
            );
            showBanner(
                SemanticTone.WARNING,
                "TLS Credentials Needed",
                "Configure SYNCSHARE_TLS_PASSWORD and peer certificates (certs/identity.p12 & certs/trusted-peers.p12) before sending."
            );
            return;
        }

        String host = peerHostField.getText().trim();
        int port = check.port();
        Path file = check.file();

        if (transferGateway.hasActiveOutgoingTransfer(host, port, file)) {
            showBanner(
                SemanticTone.WARNING,
                "Transfer Already In Progress",
                file.getFileName() + " is already being sent to " + host + ":" + port + "."
            );
            return;
        }

        sendSubmissionActive = true;
        showingTransferOutcome = false;
        activeTargetHost = host;
        activeTargetPort = port;
        setTransferControlsDisabled(true);

        sendTransferService.configure(transferGateway, host, port, file);
        sendTargetEndpointLabel.setText(
            "Target: " + host + ":" + port + " • File: " + file.getFileName() + " • mTLS 1.3"
        );
        syncLiveStageUi();

        showBanner(
            SemanticTone.INFO,
            "Sending File",
            "Connecting to " + host + ":" + port + " to transmit " + file.getFileName() + "."
        );

        sendTransferService.restart();
    }

    private void handleCancelTransfer() {
        if (!isSubmissionRunning()) {
            return;
        }
        sendStageDetailLabel.setText("Cancelling outgoing transfer and cleaning up local state...");
        sendTransferService.cancelCurrentTransfer();
    }

    private void cancelTransferById(String transferId) {
        if (transferGateway == null || transferId == null) {
            return;
        }
        if (transferId.equals(sendTransferService.getCurrentTransferId()) && isSubmissionRunning()) {
            handleCancelTransfer();
        } else {
            transferGateway.cancelActiveTransfer(transferId);
        }
    }

    private void clearForm() {
        if (isSubmissionRunning()) {
            return;
        }
        showingTransferOutcome = false;
        peerHostField.setText("");
        peerHostField.clearValidation();

        String defaultPort = services != null
            ? Integer.toString(services.settingsService().getSettings().listeningPort())
            : "5050";
        portEditedByUser = false;
        setPortProgrammatically(defaultPort);
        peerPortField.clearValidation();

        filePathField.setText("");
        filePathField.clearValidation();
        updateSelectedFileSummary("");
        refreshIdleConnectionStatus();

        showBanner(
            SemanticTone.INFO,
            "Encrypted File Transfer",
            "Form cleared. Select a target peer and local file to begin."
        );
    }

    private void finishSubmission() {
        sendSubmissionActive = false;
        setTransferControlsDisabled(false);
    }

    private boolean isSubmissionRunning() {
        return sendSubmissionActive || sendTransferService.isRunning();
    }

    private void setTransferControlsDisabled(boolean active) {
        peerHostField.getTextField().setDisable(active);
        peerPortField.getTextField().setDisable(active);
        filePathField.getTextField().setDisable(active);
        browseButton.setDisable(active);
        validateButton.setDisable(active);
        clearButton.setDisable(active);
        sendButton.setDisable(active);
        cancelButton.setDisable(!active);
    }

    private void refreshIdleConnectionStatus() {
        if (sendConnectionStatusBadge == null || isSubmissionRunning()) {
            return;
        }

        String host = peerHostField != null && peerHostField.getText() != null
            ? peerHostField.getText().trim()
            : "";
        String portText = peerPortField != null && peerPortField.getText() != null
            ? peerPortField.getText().trim()
            : "";
        String endpointPreview = !host.isEmpty()
            ? host + (!portText.isEmpty() ? ":" + portText : "")
            : "--";

        sendTargetEndpointLabel.setText("Target: " + endpointPreview + " • Protocol v2 (Single File • mTLS 1.3)");

        if (services == null) {
            return;
        }

        boolean tlsReady = services.deviceInfoService().isTlsConfigured();
        ConnectionStatus receiverStatus = services.connectionService().getStatus();
        if (!tlsReady) {
            sendConnectionStatusBadge.setText("TLS Setup");
            sendConnectionStatusBadge.setTone(SemanticTone.WARNING);
            sendConnectionStatusLabel.setText("TLS Credentials Missing");
            sendStageDetailLabel.setText(
                "Set SYNCSHARE_TLS_PASSWORD and configure keystores before sending over Mutual TLS 1.3."
            );
            sendProgressBar.setTone(SemanticTone.WARNING)
                .setTitle("Transfer Progress")
                .setProgress(0.0)
                .setStatusText("Blocked")
                .setDetail("mTLS credentials required before connecting.");
        } else {
            sendConnectionStatusBadge.setText("Ready");
            sendConnectionStatusBadge.setTone(SemanticTone.INFO);
            String listenerState = receiverStatus != null ? receiverStatus.state().displayName() : "Standby";
            sendConnectionStatusLabel.setText("Ready to Connect (mTLS 1.3 • Local Receiver " + listenerState + ")");
            sendStageDetailLabel.setText(
                "No outgoing transfer active. Enter a destination endpoint and select a local file to send."
            );
            sendProgressBar.setTone(SemanticTone.INFO)
                .setTitle("Transfer Progress")
                .setProgress(0.0)
                .setStatusText("Idle")
                .setDetail("");
        }
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

            Label title = new Label(item.fileName() + " \u2192 " + item.peerAddress());
            title.getStyleClass().add("transfer-summary-title");
            title.setWrapText(true);
            title.setMinWidth(0);
            HBox.setHgrow(title, Priority.ALWAYS);

            StatusBadge badge = new StatusBadge(item.status().displayName(), item.status().tone());
            badge.setCompact(true);

            Button rowCancelBtn = UiButtons.create("Cancel", UiButtons.Variant.DANGER_OUTLINE, UiButtons.Size.SM);
            rowCancelBtn.setOnAction(event -> cancelTransferById(item.id()));

            top.getChildren().addAll(title, badge, rowCancelBtn);

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

    private void setPortProgrammatically(String port) {
        programmaticPortUpdate = true;
        try {
            peerPortField.setText(port);
        } finally {
            programmaticPortUpdate = false;
        }
    }

    private void showBanner(SemanticTone tone, String title, String message) {
        feedbackBanner.setTone(tone).setTitle(title).setMessage(message);
        feedbackBanner.show();
    }

    private void configureResponsiveReflow() {
        if (sendFilesRoot != null) {
            sendFilesRoot.widthProperty().addListener((obs, oldWidth, newWidth) ->
                applyResponsiveEndpointGrid(newWidth.doubleValue())
            );
        }
    }

    private void applyResponsiveEndpointGrid(double availableWidth) {
        if (endpointGrid == null) {
            return;
        }
        double width = availableWidth > 0 ? availableWidth : 780;
        boolean twoColumns = width >= 520;

        endpointGrid.getColumnConstraints().clear();
        if (twoColumns) {
            ColumnConstraints hostCol = new ColumnConstraints();
            hostCol.setPercentWidth(68.0);
            hostCol.setHgrow(Priority.ALWAYS);
            ColumnConstraints portCol = new ColumnConstraints();
            portCol.setPercentWidth(32.0);
            portCol.setHgrow(Priority.ALWAYS);
            endpointGrid.getColumnConstraints().addAll(hostCol, portCol);

            GridPane.setColumnIndex(peerHostField, 0);
            GridPane.setRowIndex(peerHostField, 0);
            GridPane.setHgrow(peerHostField, Priority.ALWAYS);

            GridPane.setColumnIndex(peerPortField, 1);
            GridPane.setRowIndex(peerPortField, 0);
            GridPane.setHgrow(peerPortField, Priority.ALWAYS);
        } else {
            ColumnConstraints singleCol = new ColumnConstraints();
            singleCol.setPercentWidth(100.0);
            singleCol.setHgrow(Priority.ALWAYS);
            endpointGrid.getColumnConstraints().add(singleCol);

            GridPane.setColumnIndex(peerHostField, 0);
            GridPane.setRowIndex(peerHostField, 0);
            GridPane.setHgrow(peerHostField, Priority.ALWAYS);

            GridPane.setColumnIndex(peerPortField, 0);
            GridPane.setRowIndex(peerPortField, 1);
            GridPane.setHgrow(peerPortField, Priority.ALWAYS);
        }
        for (Node child : endpointGrid.getChildren()) {
            GridPane.setColumnSpan(child, 1);
        }
    }
}
