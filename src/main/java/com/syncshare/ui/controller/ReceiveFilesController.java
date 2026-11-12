package com.syncshare.ui.controller;

import com.syncshare.model.AppSettings;
import com.syncshare.model.ConnectionState;
import com.syncshare.model.ConnectionStatus;
import com.syncshare.model.IncomingRequestItem;
import com.syncshare.model.IncomingTransferSession;
import com.syncshare.model.NavItemId;
import com.syncshare.model.TransferDirection;
import com.syncshare.model.TransferItem;
import com.syncshare.service.IncomingTransferGateway;
import com.syncshare.service.ReceiveDestinationValidator;
import com.syncshare.service.ReceiveListenerService;
import com.syncshare.service.SyncShareServices;
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
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

/**
 * Controller for the Receive Files screen.
 * Delegates destination validation, background TCP/mTLS listener lifecycle,
 * and real transfer event observation to {@link IncomingTransferGateway} and
 * {@link ReceiveListenerService} without performing socket I/O on the FX thread.
 */
public class ReceiveFilesController implements NavigationAware {

    private static final NumberFormat INTEGER_FORMAT = NumberFormat.getIntegerInstance(Locale.US);

    @FXML
    private VBox receiveFilesRoot;

    private SyncShareServices services;
    private IncomingTransferGateway gateway;
    private ViewNavigator navigator;
    private ReceiveListenerService listenerService;

    private FeedbackBanner feedbackBanner;

    // Receiver & Destination Card
    private StatusBadge listenerBadge;
    private Label listenerHeadlineLabel;
    private Label listenerDetailLabel;
    private ValidatedTextField receiveDirField;
    private Button browseDirButton;
    private Button validateDirButton;
    private Button createDirButton;
    private Button openSettingsButton;
    private Button toggleListenerButton;

    // Live Incoming Connection & Transfer Card
    private StatusBadge sessionBadge;
    private EmptyStatePane receiveEmptyState;
    private VBox sessionPane;
    private GridPane metaGrid;
    private Label fileNameValueLabel;
    private Label fileSizeValueLabel;
    private Label senderValueLabel;
    private Label outcomeValueLabel;
    private LabeledProgressBar transferProgressBar;
    private HBox sessionActionRow;
    private Button clearSessionButton;
    private VBox multiActiveBox;

    // Pending Approval Queue Card
    private StatusBadge queueCountBadge;
    private VBox requestsListBox;

    // Recent Incoming History Card
    private StatusBadge recentCountBadge;
    private VBox recentListBox;

    private boolean initializedContext;
    private boolean suppressFieldReset;
    private String lastSyncedSettingDir = "";
    private String lastNotifiedStageKey = "";

    @FXML
    public void initialize() {
        buildLayout();
        configureResponsiveReflow();
    }

    @Override
    public void initializeContext(SyncShareServices services, ViewNavigator navigator) {
        if (initializedContext) {
            return;
        }
        this.services = services;
        this.gateway = services.connectionService();
        this.navigator = navigator;
        this.listenerService = new ReceiveListenerService(this.gateway);
        this.initializedContext = true;

        bindListenerService();
        bindServiceState();

        String initialDir = services.settingsService().getSettings().receiveDirectory();
        this.lastSyncedSettingDir = initialDir != null ? initialDir : "";
        setDestinationFieldProgrammatically(this.lastSyncedSettingDir);
        validateDestinationInput(false);

        refreshAllViews(true);
    }

    @Override
    public void onNavigatedTo() {
        if (initializedContext) {
            services.connectionService().refreshStatus();
            syncDirectoryFromSettingsIfEditable(services.settingsService().getSettings());
            refreshAllViews(false);
        }
    }

    private void buildLayout() {
        if (receiveFilesRoot == null) {
            return;
        }
        receiveFilesRoot.getChildren().clear();

        feedbackBanner = new FeedbackBanner(
            SemanticTone.INFO,
            "Receive Files Ready",
            "Select a verified destination folder and start the Mutual TLS 1.3 receiver to accept incoming files.",
            true
        );
        feedbackBanner.setId("receiveFeedbackBanner");

        UiCard receiverCard = buildReceiverAndDestinationCard();
        UiCard monitorCard = buildIncomingMonitorCard();
        UiCard requestsCard = buildPendingQueueCard();
        UiCard recentCard = buildRecentHistoryCard();

        receiveFilesRoot.getChildren().addAll(
            feedbackBanner,
            receiverCard,
            monitorCard,
            requestsCard,
            recentCard
        );

        applyResponsiveGridColumns(receiveFilesRoot.getWidth());
    }

    private UiCard buildReceiverAndDestinationCard() {
        UiCard card = new UiCard(UiCard.Variant.DEFAULT);
        card.setMinWidth(0);

        listenerBadge = new StatusBadge("Standby", SemanticTone.INFO);
        listenerBadge.setId("receiveListenerBadge");
        card.setHeader(
            "Receiver Controls & Destination",
            "Configure verified local storage and manage the background mTLS 1.3 listener",
            UiIcons.create(UiIcons.IconType.RECEIVE, UiIcons.Size.LG, SemanticTone.TEAL),
            listenerBadge
        );

        listenerHeadlineLabel = new Label("Standby • Port 5050");
        listenerHeadlineLabel.setId("receiveListenerHeadlineLabel");
        listenerHeadlineLabel.getStyleClass().add("text-section-title");
        listenerHeadlineLabel.setWrapText(true);
        listenerHeadlineLabel.setMinWidth(0);

        listenerDetailLabel = new Label("Ready to listen when TLS credentials and destination directory are verified.");
        listenerDetailLabel.setId("receiveListenerDetailLabel");
        listenerDetailLabel.getStyleClass().add("text-secondary");
        listenerDetailLabel.setWrapText(true);
        listenerDetailLabel.setMinWidth(0);

        receiveDirField = new ValidatedTextField(
            "Destination Directory",
            "Select or enter a writable destination directory...",
            "Verified before starting; existing files are preserved without overwriting"
        );
        receiveDirField.setId("receiveDirField");
        receiveDirField.setMinWidth(0);
        HBox.setHgrow(receiveDirField, Priority.ALWAYS);
        receiveDirField.getTextField().textProperty().addListener((obs, oldVal, newVal) -> {
            if (!suppressFieldReset) {
                receiveDirField.clearValidation();
                setCreateDirButtonVisible(false);
            }
        });

        browseDirButton = UiButtons.create(
            "Browse...",
            UiIcons.create(UiIcons.IconType.FOLDER, UiIcons.Size.SM),
            UiButtons.Variant.SECONDARY,
            UiButtons.Size.MD
        );
        browseDirButton.setId("receiveBrowseDirButton");
        browseDirButton.setOnAction(event -> handleBrowseDestination());

        HBox destinationRow = new HBox(DesignSystem.SPACE_XS, receiveDirField, browseDirButton);
        destinationRow.getStyleClass().add("form-row");
        destinationRow.setAlignment(Pos.BOTTOM_LEFT);
        destinationRow.setMinWidth(0);

        openSettingsButton = UiButtons.create(
            "Port & Security",
            UiIcons.create(UiIcons.IconType.SETTINGS, UiIcons.Size.SM),
            UiButtons.Variant.SECONDARY,
            UiButtons.Size.SM
        );
        openSettingsButton.setId("receiveOpenSettingsButton");
        openSettingsButton.setOnAction(event -> {
            if (navigator != null) {
                navigator.navigateTo(NavItemId.SETTINGS);
            }
        });

        validateDirButton = UiButtons.create(
            "Validate Folder",
            UiIcons.create(UiIcons.IconType.CHECK_CIRCLE, UiIcons.Size.SM),
            UiButtons.Variant.OUTLINE_TEAL,
            UiButtons.Size.SM
        );
        validateDirButton.setId("receiveValidateDirButton");
        validateDirButton.setOnAction(event -> validateDestinationInput(true));

        createDirButton = UiButtons.create(
            "Create Folder",
            UiIcons.create(UiIcons.IconType.FOLDER, UiIcons.Size.SM),
            UiButtons.Variant.OUTLINE_TEAL,
            UiButtons.Size.SM
        );
        createDirButton.setId("receiveCreateDirButton");
        createDirButton.setOnAction(event -> handleCreateDestination());
        setCreateDirButtonVisible(false);

        toggleListenerButton = UiButtons.create(
            "Start Receiving",
            UiIcons.create(UiIcons.IconType.RECEIVE, UiIcons.Size.SM),
            UiButtons.Variant.PRIMARY,
            UiButtons.Size.SM
        );
        toggleListenerButton.setId("receiveToggleButton");
        toggleListenerButton.setOnAction(event -> handleToggleListener());

        FlowPane footerActions = new FlowPane(DesignSystem.SPACE_XS, DesignSystem.SPACE_XS);
        footerActions.setAlignment(Pos.CENTER_RIGHT);
        footerActions.getChildren().addAll(
            openSettingsButton,
            validateDirButton,
            createDirButton,
            toggleListenerButton
        );

        card.setBodyContent(listenerHeadlineLabel, listenerDetailLabel, destinationRow);
        card.setFooterContent(footerActions);
        return card;
    }

    private UiCard buildIncomingMonitorCard() {
        UiCard card = new UiCard(UiCard.Variant.DEFAULT);
        card.setMinWidth(0);

        sessionBadge = new StatusBadge("Waiting", SemanticTone.NEUTRAL);
        sessionBadge.setId("receiveSessionBadge");
        card.setHeader(
            "Incoming Connection & Transfer",
            "Live handshake, sender identity, file metadata, and SHA-256 verified progress",
            UiIcons.create(UiIcons.IconType.ACTIVITY, UiIcons.Size.LG, SemanticTone.TEAL),
            sessionBadge
        );

        receiveEmptyState = new EmptyStatePane(
            UiIcons.IconType.RECEIVE,
            SemanticTone.INFO,
            "No incoming connection",
            "Select a verified destination directory and start receiving to accept Mutual TLS 1.3 transfers."
        );
        receiveEmptyState.setId("receiveEmptyState");

        sessionPane = new VBox(DesignSystem.SPACE_SM);
        sessionPane.setId("receiveSessionPane");
        sessionPane.setMinWidth(0);

        metaGrid = new GridPane();
        metaGrid.setId("receiveMetaGrid");
        metaGrid.setHgap(DesignSystem.SPACE_SM);
        metaGrid.setVgap(DesignSystem.SPACE_SM);

        fileNameValueLabel = new Label("Waiting for offer");
        fileNameValueLabel.setId("receiveFileNameLabel");
        fileSizeValueLabel = new Label("Awaiting offer metadata");
        fileSizeValueLabel.setId("receiveFileSizeLabel");
        senderValueLabel = new Label("No active peer");
        senderValueLabel.setId("receiveSenderLabel");
        outcomeValueLabel = new Label("Destination pending");
        outcomeValueLabel.setId("receiveOutcomeLabel");

        metaGrid.getChildren().addAll(
            createMetadataCell("INCOMING FILE", fileNameValueLabel, false),
            createMetadataCell("FILE SIZE", fileSizeValueLabel, true),
            createMetadataCell("SENDER PEER", senderValueLabel, true),
            createMetadataCell("DESTINATION / STATUS", outcomeValueLabel, false)
        );

        transferProgressBar = new LabeledProgressBar("Waiting", 0.0, SemanticTone.TEAL);
        transferProgressBar.setId("receiveProgressBar");
        transferProgressBar.setSize(LabeledProgressBar.Size.MD);
        transferProgressBar.setMinWidth(0);

        clearSessionButton = UiButtons.create("Clear Status", UiButtons.Variant.GHOST, UiButtons.Size.SM);
        clearSessionButton.setId("receiveClearSessionButton");
        clearSessionButton.setOnAction(event -> {
            if (gateway != null) {
                gateway.clearSessionNotice();
            }
        });

        sessionActionRow = new HBox(DesignSystem.SPACE_XS, clearSessionButton);
        sessionActionRow.setAlignment(Pos.CENTER_RIGHT);
        setNodeVisibleAndManaged(sessionActionRow, false);

        sessionPane.getChildren().addAll(metaGrid, transferProgressBar, sessionActionRow);
        setNodeVisibleAndManaged(sessionPane, false);

        multiActiveBox = new VBox(DesignSystem.SPACE_XS);
        multiActiveBox.setId("receiveMultiActiveBox");
        multiActiveBox.setMinWidth(0);
        setNodeVisibleAndManaged(multiActiveBox, false);

        card.setBodyContent(receiveEmptyState, sessionPane, multiActiveBox);
        return card;
    }

    private UiCard buildPendingQueueCard() {
        UiCard card = new UiCard(UiCard.Variant.DEFAULT);
        card.setMinWidth(0);

        queueCountBadge = new StatusBadge("0 Pending", SemanticTone.NEUTRAL);
        queueCountBadge.setId("receiveQueueCountBadge");
        queueCountBadge.setCompact(true);

        card.setHeader(
            "Pending Approval Queue",
            "Inspect incoming file metadata before accepting bytes onto disk",
            UiIcons.create(UiIcons.IconType.SHIELD_CHECK, UiIcons.Size.LG, SemanticTone.TEAL),
            queueCountBadge
        );

        requestsListBox = new VBox(DesignSystem.SPACE_XS);
        requestsListBox.setId("receiveRequestsListBox");
        requestsListBox.setMinWidth(0);
        card.setBodyContent(requestsListBox);
        return card;
    }

    private UiCard buildRecentHistoryCard() {
        UiCard card = new UiCard(UiCard.Variant.DEFAULT);
        card.setMinWidth(0);

        recentCountBadge = new StatusBadge("0 Items", SemanticTone.NEUTRAL);
        recentCountBadge.setId("receiveRecentCountBadge");
        recentCountBadge.setCompact(true);

        Button viewHistoryBtn = UiButtons.create("View History", UiButtons.Variant.GHOST, UiButtons.Size.SM);
        viewHistoryBtn.setId("receiveViewHistoryButton");
        viewHistoryBtn.setOnAction(event -> {
            if (navigator != null) {
                navigator.navigateTo(NavItemId.TRANSFER_HISTORY);
            }
        });

        HBox trailing = new HBox(DesignSystem.SPACE_XS, recentCountBadge, viewHistoryBtn);
        trailing.setAlignment(Pos.CENTER_RIGHT);

        card.setHeader(
            "Recent Incoming Transfers",
            "Latest verified files and failed incoming attempts",
            UiIcons.create(UiIcons.IconType.CLOCK, UiIcons.Size.LG, SemanticTone.TEAL),
            trailing
        );

        recentListBox = new VBox(DesignSystem.SPACE_XS);
        recentListBox.setId("receiveRecentListBox");
        recentListBox.setMinWidth(0);
        card.setBodyContent(recentListBox);
        return card;
    }

    private void bindListenerService() {
        listenerService.runningProperty().addListener((obs, wasRunning, isRunning) -> {
            updateDestinationControlsDisabled();
            updateToggleButtonAppearance();
        });

        listenerService.setOnSucceeded(event -> {
            ReceiveListenerService.ActionResult result = listenerService.getValue();
            if (result != null) {
                ReceiveDestinationValidator.ValidationResult dirCheck = result.destinationValidation();
                if (dirCheck != null) {
                    if (dirCheck.valid() && dirCheck.resolvedDirectory() != null) {
                        if (result.success()) {
                            String resolvedPath = dirCheck.resolvedDirectory().toString();
                            lastSyncedSettingDir = resolvedPath;
                            setDestinationFieldProgrammatically(resolvedPath);
                        }
                        receiveDirField.setValidation(
                            SemanticTone.SUCCESS,
                            "Writable directory verified: " + dirCheck.resolvedDirectory()
                        );
                        setCreateDirButtonVisible(false);
                    } else {
                        receiveDirField.setValidation(SemanticTone.ERROR, dirCheck.message());
                        setCreateDirButtonVisible(dirCheck.missingDirectory() && !gateway.isListening());
                    }
                }
                showBanner(result.tone(), result.title(), result.message());
            }
            updateListenerCard(gateway.getStatus());
            updateIncomingSessionCard(gateway.getIncomingSession(), false);
        });

        listenerService.setOnFailed(event -> {
            Throwable ex = listenerService.getException();
            String msg = ex != null && ex.getMessage() != null && !ex.getMessage().isBlank()
                ? ex.getMessage()
                : "Unexpected background listener failure.";
            showBanner(SemanticTone.ERROR, "Receiver Action Failed", msg);
            updateListenerCard(gateway.getStatus());
        });
    }

    private void bindServiceState() {
        gateway.statusProperty().addListener((obs, oldVal, newVal) -> {
            updateListenerCard(newVal);
            updateIncomingSessionCard(gateway.getIncomingSession(), false);
        });

        gateway.incomingSessionProperty().addListener((obs, oldVal, newVal) ->
            updateIncomingSessionCard(newVal, true)
        );

        gateway.getPendingRequests().addListener(
            (ListChangeListener<IncomingRequestItem>) change -> renderPendingRequests()
        );

        services.settingsService().settingsProperty().addListener((obs, oldVal, newVal) -> {
            syncDirectoryFromSettingsIfEditable(newVal);
            renderPendingRequests();
        });

        services.transferService().getActiveTransfers().addListener(
            (ListChangeListener<TransferItem>) change -> renderIncomingActivityExtras()
        );
        services.transferService().getTransferHistory().addListener(
            (ListChangeListener<TransferItem>) change -> renderRecentIncoming()
        );
    }

    private void refreshAllViews(boolean allowInitialSessionBanner) {
        updateListenerCard(gateway.getStatus());
        updateIncomingSessionCard(gateway.getIncomingSession(), allowInitialSessionBanner);
        renderIncomingActivityExtras();
        renderPendingRequests();
        renderRecentIncoming();
    }

    private void handleBrowseDestination() {
        if (Boolean.getBoolean("syncshare.ui.suppressDialogs")) {
            return;
        }
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select SyncShare Receive Directory");
        configureInitialDirectory(chooser, receiveDirField.getText());

        Window owner = receiveFilesRoot.getScene() != null ? receiveFilesRoot.getScene().getWindow() : null;
        File chosen = chooser.showDialog(owner);
        if (chosen != null) {
            setDestinationFieldProgrammatically(chosen.toPath().toAbsolutePath().normalize().toString());
            validateDestinationInput(true);
        }
    }

    private static void configureInitialDirectory(DirectoryChooser chooser, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return;
        }
        try {
            Path candidate = Path.of(rawPath.trim()).toAbsolutePath().normalize();
            if (Files.isDirectory(candidate)) {
                chooser.setInitialDirectory(candidate.toFile());
            } else if (candidate.getParent() != null && Files.isDirectory(candidate.getParent())) {
                chooser.setInitialDirectory(candidate.getParent().toFile());
            }
        } catch (InvalidPathException | SecurityException ignored) {
        }
    }

    private boolean validateDestinationInput(boolean showBannerFeedback) {
        if (gateway == null) {
            return false;
        }
        ReceiveDestinationValidator.ValidationResult result =
            gateway.validateDestination(receiveDirField.getText());
        if (result.valid() && result.resolvedDirectory() != null) {
            receiveDirField.setValidation(
                SemanticTone.SUCCESS,
                "Writable directory verified: " + result.resolvedDirectory()
            );
            setCreateDirButtonVisible(false);
            if (showBannerFeedback) {
                showBanner(
                    SemanticTone.SUCCESS,
                    "Destination Directory Validated",
                    result.message()
                );
            }
            return true;
        }

        receiveDirField.setValidation(SemanticTone.ERROR, result.message());
        setCreateDirButtonVisible(result.missingDirectory() && !gateway.isListening());
        if (showBannerFeedback) {
            showBanner(SemanticTone.ERROR, "Invalid Destination Directory", result.message());
        }
        return false;
    }

    private void handleCreateDestination() {
        if (gateway == null || gateway.isListening()) {
            return;
        }
        ReceiveDestinationValidator.ValidationResult result =
            gateway.createAndValidateDestination(receiveDirField.getText());
        if (result.valid() && result.resolvedDirectory() != null) {
            setDestinationFieldProgrammatically(result.resolvedDirectory().toString());
            receiveDirField.setValidation(
                SemanticTone.SUCCESS,
                "Created and verified writable directory: " + result.resolvedDirectory()
            );
            setCreateDirButtonVisible(false);
            showBanner(
                SemanticTone.SUCCESS,
                "Destination Folder Ready",
                "Created " + result.resolvedDirectory() + " and verified write permissions."
            );
        } else {
            receiveDirField.setValidation(SemanticTone.ERROR, result.message());
            showBanner(SemanticTone.ERROR, "Unable to Create Folder", result.message());
        }
    }

    private void handleToggleListener() {
        if (gateway == null || listenerService == null || listenerService.isRunning()) {
            return;
        }
        if (gateway.isListening()) {
            listenerService.requestStop();
        } else {
            listenerService.requestStart(receiveDirField.getText());
        }
        updateToggleButtonAppearance();
    }

    private void updateListenerCard(ConnectionStatus status) {
        if (status == null) {
            return;
        }
        listenerBadge.setText(status.state().displayName());
        listenerBadge.setTone(status.state().tone());
        listenerHeadlineLabel.setText(status.headline());

        if (gateway != null && gateway.isListening()) {
            String dirText = receiveDirField != null ? receiveDirField.getText().trim() : "";
            String suffix = dirText.isBlank() ? "" : " • Saving into " + dirText;
            listenerDetailLabel.setText(status.detail() + suffix);
        } else {
            listenerDetailLabel.setText(status.detail());
        }

        updateDestinationControlsDisabled();
        updateToggleButtonAppearance();
    }

    private void updateDestinationControlsDisabled() {
        boolean busy = listenerService != null && listenerService.isRunning();
        boolean listening = gateway != null && gateway.isListening();
        boolean lockDestination = busy || listening;

        if (receiveDirField != null) {
            receiveDirField.getTextField().setDisable(lockDestination);
        }
        if (browseDirButton != null) {
            browseDirButton.setDisable(lockDestination);
        }
        if (validateDirButton != null) {
            validateDirButton.setDisable(lockDestination);
        }
        if (lockDestination) {
            setCreateDirButtonVisible(false);
        }
    }

    private void updateToggleButtonAppearance() {
        if (toggleListenerButton == null || gateway == null) {
            return;
        }
        boolean busy = listenerService != null && listenerService.isRunning();
        boolean listening = gateway.isListening();

        toggleListenerButton.setDisable(busy);
        if (busy) {
            toggleListenerButton.setText(listening ? "Stopping..." : "Starting...");
            UiButtons.apply(
                toggleListenerButton,
                listening ? UiButtons.Variant.DANGER_OUTLINE : UiButtons.Variant.PRIMARY,
                UiButtons.Size.SM
            );
            return;
        }

        toggleListenerButton.setText(listening ? "Stop Receiving" : "Start Receiving");
        UiButtons.apply(
            toggleListenerButton,
            listening ? UiButtons.Variant.DANGER_OUTLINE : UiButtons.Variant.PRIMARY,
            UiButtons.Size.SM
        );
    }

    private void updateIncomingSessionCard(IncomingTransferSession session, boolean emitNotifications) {
        if (sessionBadge == null || receiveEmptyState == null || sessionPane == null) {
            return;
        }
        IncomingTransferSession resolved = session != null ? session : IncomingTransferSession.idle();
        IncomingTransferSession.Stage stage = resolved.stage();

        sessionBadge.setText(stage.displayName());
        sessionBadge.setTone(stage.tone());

        boolean idle = stage == IncomingTransferSession.Stage.IDLE;
        setNodeVisibleAndManaged(receiveEmptyState, idle);
        setNodeVisibleAndManaged(sessionPane, !idle);

        if (idle) {
            updateEmptyStateCopy();
            return;
        }

        boolean preOffer = stage == IncomingTransferSession.Stage.CONNECTED;
        fileNameValueLabel.setText(preOffer ? "Negotiating incoming offer..." : resolved.fileName());
        fileSizeValueLabel.setText(
            preOffer
                ? "Awaiting offer metadata"
                : formatReadableByteSize(resolved.totalBytes())
        );
        senderValueLabel.setText(resolved.senderAddress());
        outcomeValueLabel.setText(formatOutcomeText(resolved));

        transferProgressBar.setTitle(resolved.statusTitle());
        transferProgressBar.setProgress(resolved.progress());
        transferProgressBar.setTone(stage.tone());
        transferProgressBar.setDetail(resolved.formattedProgressDetail());

        boolean terminal = stage == IncomingTransferSession.Stage.COMPLETED
            || stage == IncomingTransferSession.Stage.FAILED
            || stage == IncomingTransferSession.Stage.REJECTED;
        setNodeVisibleAndManaged(sessionActionRow, terminal);

        if (emitNotifications) {
            emitSessionNotificationIfChanged(resolved);
        }
    }

    private void emitSessionNotificationIfChanged(IncomingTransferSession session) {
        if (session.stage() == IncomingTransferSession.Stage.IDLE) {
            return;
        }
        String key = session.transferId() + ":" + session.stage();
        if (key.equals(lastNotifiedStageKey)) {
            return;
        }
        lastNotifiedStageKey = key;

        switch (session.stage()) {
            case CONNECTED -> showBanner(
                SemanticTone.INFO,
                "Incoming Peer Connected",
                "Authenticated Mutual TLS 1.3 peer: " + session.senderAddress()
            );
            case AWAITING_APPROVAL -> showBanner(
                SemanticTone.WARNING,
                "Incoming File Offer",
                "Inspect and approve or reject " + session.fileName()
                    + " (" + formatReadableByteSize(session.totalBytes()) + ") from " + session.senderAddress() + "."
            );
            case RECEIVING -> showBanner(
                SemanticTone.TEAL,
                "Receiving Encrypted File",
                "Receiving " + session.fileName() + " (" + session.formattedSize() + ") from " + session.senderAddress() + "."
            );
            case COMPLETED -> showBanner(
                SemanticTone.SUCCESS,
                "File Received & Verified",
                session.fileName() + " (" + session.formattedSize() + ") from " + session.senderAddress()
                    + " verified with SHA-256 and saved to "
                    + (session.savedPath() != null ? session.savedPath().toAbsolutePath() : "destination folder") + "."
            );
            case REJECTED -> showBanner(
                SemanticTone.WARNING,
                "Incoming Transfer Rejected",
                session.statusDetail()
            );
            case FAILED -> showBanner(
                SemanticTone.ERROR,
                "Incoming Transfer Failed",
                session.statusDetail()
            );
            case IDLE -> {
            }
        }
    }

    private void updateEmptyStateCopy() {
        boolean listening = gateway != null && gateway.isListening();
        ConnectionStatus status = gateway != null ? gateway.getStatus() : null;
        if (listening) {
            int port = gateway.getBoundPort() > 0
                ? gateway.getBoundPort()
                : (status != null ? status.listenPort() : 5050);
            receiveEmptyState.setIcon(UiIcons.IconType.WIFI, SemanticTone.SUCCESS);
            receiveEmptyState.setTitle("Listening for incoming connections");
            receiveEmptyState.setDescription(
                "Mutual TLS 1.3 receiver active on TCP port " + port
                    + ". Incoming file metadata and live progress appear automatically when a peer connects."
            );
        } else if (status != null && status.state() == ConnectionState.ERROR) {
            receiveEmptyState.setIcon(UiIcons.IconType.ALERT_CIRCLE, SemanticTone.ERROR);
            receiveEmptyState.setTitle("Receiver listener unavailable");
            receiveEmptyState.setDescription(status.detail());
        } else if (status != null && status.state() == ConnectionState.TLS_UNCONFIGURED) {
            receiveEmptyState.setIcon(UiIcons.IconType.LOCK, SemanticTone.WARNING);
            receiveEmptyState.setTitle("Mutual TLS setup required");
            receiveEmptyState.setDescription(status.detail());
        } else {
            receiveEmptyState.setIcon(UiIcons.IconType.RECEIVE, SemanticTone.INFO);
            receiveEmptyState.setTitle("No incoming connection");
            receiveEmptyState.setDescription(
                "Choose a verified destination directory and click Start Receiving to listen for peer file offers."
            );
        }
    }

    private String formatOutcomeText(IncomingTransferSession session) {
        if (session.stage() == IncomingTransferSession.Stage.COMPLETED && session.savedPath() != null) {
            return "Saved to " + session.savedPath().toAbsolutePath();
        }
        if (session.stage() == IncomingTransferSession.Stage.FAILED
            || session.stage() == IncomingTransferSession.Stage.REJECTED) {
            return session.statusDetail();
        }
        String currentDir = receiveDirField != null ? receiveDirField.getText().trim() : "";
        return currentDir.isBlank() ? session.statusDetail() : currentDir;
    }

    private static String formatReadableByteSize(long bytes) {
        long safeBytes = Math.max(0L, bytes);
        if (safeBytes < 1024L) {
            return TransferItem.formatByteCount(safeBytes);
        }
        return TransferItem.formatByteCount(safeBytes) + " (" + INTEGER_FORMAT.format(safeBytes) + " bytes)";
    }

    private void renderIncomingActivityExtras() {
        if (services == null || multiActiveBox == null) {
            return;
        }
        List<TransferItem> activeIncoming = services.transferService().getActiveTransfers().stream()
            .filter(item -> item.direction() == TransferDirection.INCOMING)
            .toList();

        if (activeIncoming.size() <= 1) {
            multiActiveBox.getChildren().clear();
            setNodeVisibleAndManaged(multiActiveBox, false);
            return;
        }

        multiActiveBox.getChildren().clear();
        Label header = new Label("Concurrent Incoming Transfers (" + activeIncoming.size() + ")");
        header.getStyleClass().add("text-eyebrow");
        multiActiveBox.getChildren().add(header);

        for (TransferItem item : activeIncoming) {
            VBox row = new VBox(6);
            row.getStyleClass().add("transfer-summary-row");
            row.setMinWidth(0);

            HBox top = new HBox(DesignSystem.SPACE_XS);
            top.setAlignment(Pos.CENTER_LEFT);
            Label title = new Label(item.fileName() + " • " + item.peerAddress());
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
            multiActiveBox.getChildren().add(row);
        }
        setNodeVisibleAndManaged(multiActiveBox, true);
    }

    private void renderPendingRequests() {
        if (services == null || requestsListBox == null) {
            return;
        }
        List<IncomingRequestItem> requests = gateway.getPendingRequests();
        queueCountBadge.setText(requests.size() + " Pending");
        queueCountBadge.setTone(requests.isEmpty() ? SemanticTone.NEUTRAL : SemanticTone.WARNING);

        if (requests.isEmpty()) {
            boolean manualApproval = services.settingsService().getSettings().requireApproval();
            EmptyStatePane empty = new EmptyStatePane(
                UiIcons.IconType.SHIELD_CHECK,
                manualApproval ? SemanticTone.INFO : SemanticTone.TEAL,
                "No pending incoming requests",
                manualApproval
                    ? "Manual approval is enabled. Incoming file offers pause here for Accept/Reject confirmation before saving."
                    : "Automatic approval is enabled in Settings. Incoming files from trusted mTLS peers start receiving immediately."
            ).setCompact(true);
            requestsListBox.getChildren().setAll(empty);
            return;
        }

        requestsListBox.getChildren().clear();
        for (IncomingRequestItem req : requests) {
            HBox row = new HBox(DesignSystem.SPACE_SM);
            row.getStyleClass().add("transfer-summary-row");
            row.setAlignment(Pos.CENTER_LEFT);
            row.setMinWidth(0);

            VBox info = new VBox(2);
            info.setMinWidth(0);
            HBox.setHgrow(info, Priority.ALWAYS);

            Label title = new Label("#" + req.requestId() + " • " + req.fileName());
            title.getStyleClass().add("transfer-summary-title");
            title.setWrapText(true);

            Label meta = new Label(
                "From " + req.senderAddress()
                    + " • " + formatReadableByteSize(req.fileSize())
                    + " • " + req.formattedTime()
            );
            meta.getStyleClass().add("transfer-summary-meta");
            meta.setWrapText(true);
            info.getChildren().addAll(title, meta);

            Button rejectBtn = UiButtons.create("Reject", UiButtons.Variant.DANGER_OUTLINE, UiButtons.Size.SM);
            rejectBtn.setOnAction(e -> gateway.approveRequest(req.requestId(), false));

            Button acceptBtn = UiButtons.create("Accept", UiButtons.Variant.SUCCESS, UiButtons.Size.SM);
            acceptBtn.setOnAction(e -> gateway.approveRequest(req.requestId(), true));

            HBox actions = new HBox(DesignSystem.SPACE_XS, rejectBtn, acceptBtn);
            actions.setAlignment(Pos.CENTER_RIGHT);
            actions.setMinWidth(Region.USE_PREF_SIZE);

            row.getChildren().addAll(info, actions);
            requestsListBox.getChildren().add(row);
        }
    }

    private void renderRecentIncoming() {
        if (services == null || recentListBox == null) {
            return;
        }
        List<TransferItem> incomingHistory = services.transferService().getTransferHistory().stream()
            .filter(item -> item.direction() == TransferDirection.INCOMING)
            .toList();

        recentCountBadge.setText(incomingHistory.size() + (incomingHistory.size() == 1 ? " Item" : " Items"));
        recentCountBadge.setTone(incomingHistory.isEmpty() ? SemanticTone.NEUTRAL : SemanticTone.INFO);

        if (incomingHistory.isEmpty()) {
            EmptyStatePane empty = new EmptyStatePane(
                UiIcons.IconType.CLOCK,
                SemanticTone.INFO,
                "No received files in this session",
                "Verified incoming files and interrupted attempts appear here once a peer transfer finishes."
            ).setCompact(true);
            recentListBox.getChildren().setAll(empty);
            return;
        }

        recentListBox.getChildren().clear();
        int limit = Math.min(4, incomingHistory.size());
        for (int i = 0; i < limit; i++) {
            TransferItem item = incomingHistory.get(i);
            HBox row = new HBox(DesignSystem.SPACE_SM);
            row.getStyleClass().add("transfer-summary-row");
            row.setAlignment(Pos.CENTER_LEFT);
            row.setMinWidth(0);

            VBox info = new VBox(2);
            info.setMinWidth(0);
            HBox.setHgrow(info, Priority.ALWAYS);

            Label title = new Label(item.fileName());
            title.getStyleClass().add("transfer-summary-title");
            title.setWrapText(true);

            String noteSuffix = item.note() != null && !item.note().isBlank() ? " • " + item.note() : "";
            Label meta = new Label(
                item.peerAddress()
                    + " • " + item.formattedSize()
                    + " • " + item.formattedTimestamp()
                    + noteSuffix
            );
            meta.getStyleClass().add("transfer-summary-meta");
            meta.setWrapText(true);
            info.getChildren().addAll(title, meta);

            StatusBadge badge = new StatusBadge(
                item.status().displayName(),
                item.status().tone(),
                StatusBadge.IndicatorMode.ICON
            );
            badge.setCompact(true);

            row.getChildren().addAll(info, badge);
            recentListBox.getChildren().add(row);
        }
    }

    private VBox createMetadataCell(String key, Label valueLabel, boolean mono) {
        VBox cell = new VBox(4);
        cell.getStyleClass().add("device-info-item");
        cell.setMinWidth(0);

        Label keyLabel = new Label(key);
        keyLabel.getStyleClass().add("device-info-key");

        valueLabel.getStyleClass().add(mono ? "device-info-mono" : "device-info-value");
        valueLabel.setWrapText(true);
        valueLabel.setMinWidth(0);

        cell.getChildren().addAll(keyLabel, valueLabel);
        return cell;
    }

    private void syncDirectoryFromSettingsIfEditable(AppSettings settings) {
        if (settings == null || receiveDirField == null) {
            return;
        }
        String latestSettingDir = settings.receiveDirectory() != null ? settings.receiveDirectory() : "";
        boolean busy = listenerService != null && listenerService.isRunning();
        boolean listening = gateway != null && gateway.isListening();
        if (!busy && !listening && !latestSettingDir.equals(lastSyncedSettingDir)) {
            lastSyncedSettingDir = latestSettingDir;
            setDestinationFieldProgrammatically(latestSettingDir);
            validateDestinationInput(false);
        }
    }

    private void setDestinationFieldProgrammatically(String path) {
        if (receiveDirField == null) {
            return;
        }
        suppressFieldReset = true;
        try {
            receiveDirField.setText(path != null ? path : "");
        } finally {
            suppressFieldReset = false;
        }
    }

    private void setCreateDirButtonVisible(boolean visible) {
        setNodeVisibleAndManaged(createDirButton, visible);
    }

    private static void setNodeVisibleAndManaged(Node node, boolean visible) {
        if (node != null) {
            node.setVisible(visible);
            node.setManaged(visible);
        }
    }

    private void showBanner(SemanticTone tone, String title, String message) {
        if (feedbackBanner != null) {
            feedbackBanner.setTone(tone).setTitle(title).setMessage(message);
            feedbackBanner.show();
        }
    }

    private void configureResponsiveReflow() {
        if (receiveFilesRoot != null) {
            receiveFilesRoot.widthProperty().addListener((obs, oldWidth, newWidth) ->
                applyResponsiveGridColumns(newWidth.doubleValue())
            );
        }
    }

    private void applyResponsiveGridColumns(double availableWidth) {
        if (metaGrid == null) {
            return;
        }
        double width = availableWidth > 0 ? availableWidth : 780;
        int columns = width >= 540 ? 2 : 1;
        List<Node> items = List.copyOf(metaGrid.getChildren());
        metaGrid.getColumnConstraints().clear();
        for (int c = 0; c < columns; c++) {
            ColumnConstraints col = new ColumnConstraints();
            col.setPercentWidth(100.0 / columns);
            col.setFillWidth(true);
            col.setMinWidth(0);
            metaGrid.getColumnConstraints().add(col);
        }
        for (int i = 0; i < items.size(); i++) {
            Node child = items.get(i);
            GridPane.setColumnIndex(child, i % columns);
            GridPane.setRowIndex(child, i / columns);
            GridPane.setFillWidth(child, true);
        }
    }

    ReceiveListenerService getListenerServiceForTesting() {
        return listenerService;
    }
}
