package com.syncshare.ui.controller;

import com.syncshare.model.AppSettings;
import com.syncshare.model.LocalDeviceInfo;
import com.syncshare.service.SettingsService;
import com.syncshare.service.SettingsValidator;
import com.syncshare.service.SyncShareServices;
import com.syncshare.ui.design.DesignSystem;
import com.syncshare.ui.design.FeedbackBanner;
import com.syncshare.ui.design.SemanticTone;
import com.syncshare.ui.design.StatusBadge;
import com.syncshare.ui.design.UiButtons;
import com.syncshare.ui.design.UiCard;
import com.syncshare.ui.design.UiDialogs;
import com.syncshare.ui.design.UiIcons;
import com.syncshare.ui.design.ValidatedTextField;
import com.syncshare.ui.navigation.NavigationAware;
import com.syncshare.ui.navigation.ViewNavigator;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

import java.io.File;
import java.util.Optional;

/**
 * Controller for the complete SyncShare Application Settings module.
 *
 * <p>Organized into distinct sections: Storage, Application, Notifications,
 * Appearance, Network, and Actions. Delegates all business logic, validation,
 * and persistence to {@link SettingsService}.</p>
 */
public class SettingsController implements NavigationAware {

    @FXML
    private VBox settingsRoot;

    private SyncShareServices services;
    private boolean initializedContext;

    // Feedback
    private FeedbackBanner feedbackBanner;

    // Storage section
    private ValidatedTextField receiveDirField;
    private ValidatedTextField downloadDirField;
    private Button browseReceiveBtn;
    private Button browseDownloadBtn;

    // Application section
    private CheckBox autoStartCheck;
    private CheckBox startMinimizedCheck;

    // Notifications section
    private CheckBox notificationsEnabledCheck;
    private CheckBox notifyOnTransferCompleteCheck;
    private CheckBox notifyOnIncomingRequestCheck;
    private CheckBox soundEnabledCheck;
    private VBox notificationSubOptions;

    // Appearance section
    private StatusBadge themeBadge;

    // Network section
    private ValidatedTextField aliasField;
    private ValidatedTextField portField;
    private CheckBox requireApprovalCheck;
    private CheckBox verifySha256Check;
    private StatusBadge tlsStatusBadge;
    private Label tlsDetailLabel;
    private Label networkInfoLabel;

    // Action buttons
    private Button resetBtn;
    private Button saveBtn;

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
        this.initializedContext = true;

        services.settingsService().settingsProperty().addListener((obs, oldVal, newVal) -> populateFromModel(newVal));
        services.deviceInfoService().deviceInfoProperty().addListener((obs, oldVal, newVal) -> updateNetworkInfo(newVal));

        populateFromModel(services.settingsService().getSettings());
        updateNetworkInfo(services.deviceInfoService().getDeviceInfo());
    }

    @Override
    public void onNavigatedTo() {
        if (initializedContext) {
            populateFromModel(services.settingsService().getSettings());
            updateNetworkInfo(services.deviceInfoService().getDeviceInfo());
        }
    }

    private void buildLayout() {
        if (settingsRoot == null) {
            return;
        }
        settingsRoot.getChildren().clear();
        settingsRoot.setSpacing(DesignSystem.SPACE_MD);

        // 1. Feedback Banner
        feedbackBanner = new FeedbackBanner(
            SemanticTone.INFO,
            "Application Settings",
            "Configure storage directories, application startup, notifications, and network preferences.",
            true
        );
        feedbackBanner.setId("settingsFeedbackBanner");

        // 2. Storage Section Card
        UiCard storageCard = buildStorageCard();

        // 3. Application & Startup Card
        UiCard appCard = buildApplicationCard();

        // 4. Notifications Card
        UiCard notificationsCard = buildNotificationsCard();

        // 5. Appearance Card
        UiCard appearanceCard = buildAppearanceCard();

        // 6. Network Configuration Card
        UiCard networkCard = buildNetworkCard();

        // 7. Actions Bar
        HBox actionsBar = buildActionsBar();

        settingsRoot.getChildren().addAll(
            feedbackBanner,
            storageCard,
            appCard,
            notificationsCard,
            appearanceCard,
            networkCard,
            actionsBar
        );
    }

    private UiCard buildStorageCard() {
        UiCard card = new UiCard(UiCard.Variant.DEFAULT);
        card.setMinWidth(0);
        card.setHeader(
            "Storage & File Locations",
            "Configure default paths for saving incoming files and download destination",
            UiIcons.create(UiIcons.IconType.FOLDER, UiIcons.Size.LG, SemanticTone.TEAL),
            null
        );

        // Receive directory row (default save location)
        receiveDirField = new ValidatedTextField(
            "Default Save Location (Incoming Files)",
            "received_files",
            "Directory where incoming peer files are safely written and verified"
        );
        receiveDirField.setId("settingsReceiveDirField");
        receiveDirField.setMinWidth(0);
        HBox.setHgrow(receiveDirField, Priority.ALWAYS);

        browseReceiveBtn = UiButtons.create(
            "Browse...",
            UiIcons.create(UiIcons.IconType.FOLDER, UiIcons.Size.SM),
            UiButtons.Variant.SECONDARY,
            UiButtons.Size.MD
        );
        browseReceiveBtn.setId("browseReceiveDirButton");
        browseReceiveBtn.setOnAction(e -> browseReceiveDirectory());

        HBox receiveRow = new HBox(DesignSystem.SPACE_XS, receiveDirField, browseReceiveBtn);
        receiveRow.setAlignment(Pos.BOTTOM_LEFT);

        // Download directory row
        downloadDirField = new ValidatedTextField(
            "Default Download Directory",
            "downloads",
            "Primary directory used for downloads and file picker fallbacks"
        );
        downloadDirField.setId("settingsDownloadDirField");
        downloadDirField.setMinWidth(0);
        HBox.setHgrow(downloadDirField, Priority.ALWAYS);

        browseDownloadBtn = UiButtons.create(
            "Browse...",
            UiIcons.create(UiIcons.IconType.FOLDER, UiIcons.Size.SM),
            UiButtons.Variant.SECONDARY,
            UiButtons.Size.MD
        );
        browseDownloadBtn.setId("browseDownloadDirButton");
        browseDownloadBtn.setOnAction(e -> browseDownloadDirectory());

        HBox downloadRow = new HBox(DesignSystem.SPACE_XS, downloadDirField, browseDownloadBtn);
        downloadRow.setAlignment(Pos.BOTTOM_LEFT);

        card.setBodyContent(receiveRow, downloadRow);
        return card;
    }

    private UiCard buildApplicationCard() {
        UiCard card = new UiCard(UiCard.Variant.DEFAULT);
        card.setMinWidth(0);
        card.setHeader(
            "Application & Startup",
            "Configure background listener startup and launch behavior",
            UiIcons.create(UiIcons.IconType.SETTINGS, UiIcons.Size.LG, SemanticTone.TEAL),
            null
        );

        autoStartCheck = new CheckBox("Auto-start background receiver on startup when mTLS is configured");
        autoStartCheck.setId("settingsAutoStartCheck");
        autoStartCheck.getStyleClass().add("ss-checkbox");

        startMinimizedCheck = new CheckBox("Start application minimized to system tray / taskbar");
        startMinimizedCheck.setId("settingsStartMinimizedCheck");
        startMinimizedCheck.getStyleClass().add("ss-checkbox");

        card.setBodyContent(autoStartCheck, startMinimizedCheck);
        return card;
    }

    private UiCard buildNotificationsCard() {
        UiCard card = new UiCard(UiCard.Variant.DEFAULT);
        card.setMinWidth(0);
        card.setHeader(
            "Notification Preferences",
            "Manage desktop alerts, completion notices, and sound effects",
            UiIcons.create(UiIcons.IconType.ALERT_CIRCLE, UiIcons.Size.LG, SemanticTone.TEAL),
            null
        );

        notificationsEnabledCheck = new CheckBox("Enable desktop notifications (Master toggle)");
        notificationsEnabledCheck.setId("settingsNotificationsEnabledCheck");
        notificationsEnabledCheck.getStyleClass().add("ss-checkbox");

        notifyOnTransferCompleteCheck = new CheckBox("Notify when a file transfer completes successfully or fails");
        notifyOnTransferCompleteCheck.setId("settingsNotifyTransferCompleteCheck");
        notifyOnTransferCompleteCheck.getStyleClass().add("ss-checkbox");

        notifyOnIncomingRequestCheck = new CheckBox("Notify when an incoming file transfer request arrives");
        notifyOnIncomingRequestCheck.setId("settingsNotifyIncomingRequestCheck");
        notifyOnIncomingRequestCheck.getStyleClass().add("ss-checkbox");

        soundEnabledCheck = new CheckBox("Play audio sounds for transfer events");
        soundEnabledCheck.setId("settingsSoundEnabledCheck");
        soundEnabledCheck.getStyleClass().add("ss-checkbox");

        notificationSubOptions = new VBox(
            DesignSystem.SPACE_XS,
            notifyOnTransferCompleteCheck,
            notifyOnIncomingRequestCheck,
            soundEnabledCheck
        );
        notificationSubOptions.setPadding(new Insets(0, 0, 0, 24));

        notificationsEnabledCheck.selectedProperty().addListener((obs, oldVal, enabled) ->
            notificationSubOptions.setDisable(!enabled)
        );

        card.setBodyContent(notificationsEnabledCheck, notificationSubOptions);
        return card;
    }

    private UiCard buildAppearanceCard() {
        UiCard card = new UiCard(UiCard.Variant.DEFAULT);
        card.setMinWidth(0);

        themeBadge = new StatusBadge("SyncShare Navy & Teal (Default)", SemanticTone.TEAL);
        card.setHeader(
            "Appearance & Theme",
            "Application visual theme and high-contrast styling",
            UiIcons.create(UiIcons.IconType.LAYERS, UiIcons.Size.LG, SemanticTone.TEAL),
            themeBadge
        );

        Label themeInfo = new Label(
            "SyncShare is styled using the master Deep Navy & Teal enterprise theme. "
                + "Alternative themes are not supported by the current UI architecture."
        );
        themeInfo.getStyleClass().add("text-caption");
        themeInfo.setWrapText(true);

        card.setBodyContent(themeInfo);
        return card;
    }

    private UiCard buildNetworkCard() {
        UiCard card = new UiCard(UiCard.Variant.DEFAULT);
        card.setMinWidth(0);

        tlsStatusBadge = new StatusBadge("Checking", SemanticTone.NEUTRAL);
        card.setHeader(
            "Device & Network Configuration",
            "Local peer identity, listening port, and mutual TLS 1.3 security",
            UiIcons.create(UiIcons.IconType.LOCK, UiIcons.Size.LG, SemanticTone.TEAL),
            tlsStatusBadge
        );

        aliasField = new ValidatedTextField(
            "Device Alias",
            "SyncShare-Workstation",
            "Friendly name advertised to local network peers"
        );
        aliasField.setId("settingsAliasField");

        portField = new ValidatedTextField(
            "Listening TCP Port",
            "5050",
            "Valid port range: 1024 - 65535"
        );
        portField.setId("settingsPortField");

        requireApprovalCheck = new CheckBox("Require manual approval before receiving incoming files");
        requireApprovalCheck.setId("settingsRequireApprovalCheck");
        requireApprovalCheck.getStyleClass().add("ss-checkbox");

        verifySha256Check = new CheckBox("Verify SHA-256 checksum on every completed transfer");
        verifySha256Check.setId("settingsVerifySha256Check");
        verifySha256Check.getStyleClass().add("ss-checkbox");

        networkInfoLabel = new Label("Resolving network status...");
        networkInfoLabel.getStyleClass().add("text-caption");
        networkInfoLabel.setWrapText(true);

        tlsDetailLabel = new Label("Checking mTLS keystore configuration...");
        tlsDetailLabel.getStyleClass().add("text-caption");
        tlsDetailLabel.setWrapText(true);

        Label securityNote = new Label(
            "Security Policy: Passwords, private keys, and TLS credentials (SYNCSHARE_TLS_PASSWORD) "
                + "are managed via secure environment variables and are never stored in settings files."
        );
        securityNote.getStyleClass().add("text-caption");
        securityNote.setWrapText(true);

        card.setBodyContent(
            aliasField,
            portField,
            requireApprovalCheck,
            verifySha256Check,
            networkInfoLabel,
            tlsDetailLabel,
            securityNote
        );

        return card;
    }

    private HBox buildActionsBar() {
        resetBtn = UiButtons.create("Restore Defaults", UiButtons.Variant.SECONDARY, UiButtons.Size.SM);
        resetBtn.setId("settingsResetButton");
        resetBtn.setOnAction(event -> handleResetDefaults());

        saveBtn = UiButtons.create(
            "Save Settings",
            UiIcons.create(UiIcons.IconType.CHECK_CIRCLE, UiIcons.Size.SM),
            UiButtons.Variant.PRIMARY,
            UiButtons.Size.SM
        );
        saveBtn.setId("settingsSaveButton");
        saveBtn.setOnAction(event -> handleSave());

        HBox actionsBar = new HBox(DesignSystem.SPACE_XS, resetBtn, saveBtn);
        actionsBar.setAlignment(Pos.CENTER_RIGHT);
        actionsBar.setPadding(new Insets(DesignSystem.SPACE_XS, 0, DesignSystem.SPACE_MD, 0));
        return actionsBar;
    }

    private void populateFromModel(AppSettings settings) {
        if (settings == null) {
            return;
        }
        aliasField.setText(settings.deviceAlias());
        aliasField.clearValidation();

        portField.setText(Integer.toString(settings.listeningPort()));
        portField.clearValidation();

        receiveDirField.setText(settings.receiveDirectory());
        receiveDirField.clearValidation();

        downloadDirField.setText(settings.downloadDirectory());
        downloadDirField.clearValidation();

        requireApprovalCheck.setSelected(settings.requireApproval());
        verifySha256Check.setSelected(settings.verifySha256());
        autoStartCheck.setSelected(settings.autoStartReceiver());
        startMinimizedCheck.setSelected(settings.startMinimized());

        notificationsEnabledCheck.setSelected(settings.notificationsEnabled());
        notifyOnTransferCompleteCheck.setSelected(settings.notifyOnTransferComplete());
        notifyOnIncomingRequestCheck.setSelected(settings.notifyOnIncomingRequest());
        soundEnabledCheck.setSelected(settings.soundEnabled());
        notificationSubOptions.setDisable(!settings.notificationsEnabled());
    }

    private void updateNetworkInfo(LocalDeviceInfo info) {
        if (info == null) {
            return;
        }
        tlsStatusBadge.setText(info.tlsConfigured() ? "mTLS Configured" : "Setup Required");
        tlsStatusBadge.setTone(info.tlsConfigured() ? SemanticTone.SUCCESS : SemanticTone.WARNING);

        networkInfoLabel.setText(
            "IP Address: " + info.ipAddress()
                + " • Operating System: " + info.operatingSystem()
                + " • Bound Port: " + info.listeningPort()
        );

        tlsDetailLabel.setText(
            "Identity Store: " + info.keyStorePath()
                + " • Trust Store: " + info.trustStorePath()
                + " • Status: " + info.tlsSummary()
        );
    }

    private void browseDirectory(ValidatedTextField targetField, String title) {
        if (Boolean.getBoolean("syncshare.ui.suppressDialogs")) {
            return;
        }
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(title);
        Window owner = getWindow();
        File dir = chooser.showDialog(owner);
        if (dir != null) {
            targetField.setText(dir.getAbsolutePath());
            targetField.clearValidation();
        }
    }

    private void browseReceiveDirectory() {
        browseDirectory(receiveDirField, "Select Default Save Location");
    }

    private void browseDownloadDirectory() {
        browseDirectory(downloadDirField, "Select Default Download Directory");
    }

    private void handleSave() {
        if (services == null) {
            return;
        }

        aliasField.clearValidation();
        portField.clearValidation();
        receiveDirField.clearValidation();
        downloadDirField.clearValidation();

        SettingsService settingsService = services.settingsService();
        String aliasText = aliasField.getText();
        String portText = portField.getText();
        String receiveDir = receiveDirField.getText();
        String downloadDir = downloadDirField.getText();

        SettingsValidator.ValidationResult aliasCheck = SettingsValidator.validateDeviceAlias(aliasText);
        if (!aliasCheck.valid()) {
            aliasField.setValidation(SemanticTone.ERROR, aliasCheck.message());
            showFeedback(SemanticTone.ERROR, "Invalid Device Alias", aliasCheck.message());
            return;
        }

        SettingsValidator.ValidationResult portCheck = SettingsValidator.validatePort(portText);
        if (!portCheck.valid()) {
            portField.setValidation(SemanticTone.ERROR, portCheck.message());
            showFeedback(SemanticTone.ERROR, "Invalid Port", portCheck.message());
            return;
        }

        SettingsValidator.ValidationResult receiveCheck = settingsService.validateDirectory(
            receiveDir,
            "Receive directory",
            false
        );
        if (!receiveCheck.valid()) {
            receiveDirField.setValidation(SemanticTone.ERROR, receiveCheck.message());
            showFeedback(SemanticTone.ERROR, "Invalid Receive Directory", receiveCheck.message());
            return;
        }

        SettingsValidator.ValidationResult downloadCheck = settingsService.validateDirectory(
            downloadDir,
            "Download directory",
            false
        );
        if (!downloadCheck.valid()) {
            downloadDirField.setValidation(SemanticTone.ERROR, downloadCheck.message());
            showFeedback(SemanticTone.ERROR, "Invalid Download Directory", downloadCheck.message());
            return;
        }

        int port = Integer.parseInt(portText.trim());
        AppSettings updated = new AppSettings(
            aliasText.trim(),
            port,
            receiveDir.trim(),
            downloadDir.trim(),
            requireApprovalCheck.isSelected(),
            verifySha256Check.isSelected(),
            autoStartCheck.isSelected(),
            startMinimizedCheck.isSelected(),
            notificationsEnabledCheck.isSelected(),
            notifyOnTransferCompleteCheck.isSelected(),
            notifyOnIncomingRequestCheck.isSelected(),
            soundEnabledCheck.isSelected(),
            AppSettings.DEFAULT_THEME
        );

        SettingsService.ValidationResult saveResult = settingsService.updateSettings(updated);
        if (saveResult.valid()) {
            showFeedback(SemanticTone.SUCCESS, "Settings Saved", "Preferences saved and applied successfully.");
        } else {
            showFeedback(SemanticTone.ERROR, "Save Failed", saveResult.message());
        }
    }

    private void handleResetDefaults() {
        if (services == null) {
            return;
        }

        if (!Boolean.getBoolean("syncshare.ui.suppressDialogs")) {
            Alert confirmDialog = UiDialogs.confirm(
                getWindow(),
                "Confirm Restore Defaults",
                "Reset All Application Settings?",
                "This will revert all preferences, storage locations, and network configuration to default values. This action cannot be undone.",
                "Restore Defaults"
            );
            Optional<ButtonType> choice = confirmDialog.showAndWait();
            if (choice.isEmpty() || choice.get().getButtonData() != ButtonBar.ButtonData.OK_DONE) {
                return;
            }
        }

        services.settingsService().resetDefaults();
        populateFromModel(services.settingsService().getSettings());
        showFeedback(SemanticTone.INFO, "Defaults Restored", "Settings reverted to SyncShare factory defaults.");
    }

    private void showFeedback(SemanticTone tone, String title, String message) {
        if (feedbackBanner != null) {
            feedbackBanner.setTone(tone)
                .setTitle(title)
                .setMessage(message);
            feedbackBanner.show();
        }
    }

    private Window getWindow() {
        return (settingsRoot != null && settingsRoot.getScene() != null)
            ? settingsRoot.getScene().getWindow()
            : null;
    }

    // Accessors for unit testing UI components
    public ValidatedTextField getAliasField() {
        return aliasField;
    }

    public ValidatedTextField getPortField() {
        return portField;
    }

    public ValidatedTextField getReceiveDirField() {
        return receiveDirField;
    }

    public ValidatedTextField getDownloadDirField() {
        return downloadDirField;
    }

    public CheckBox getAutoStartCheck() {
        return autoStartCheck;
    }

    public CheckBox getStartMinimizedCheck() {
        return startMinimizedCheck;
    }

    public CheckBox getNotificationsEnabledCheck() {
        return notificationsEnabledCheck;
    }

    public CheckBox getNotifyOnTransferCompleteCheck() {
        return notifyOnTransferCompleteCheck;
    }

    public CheckBox getNotifyOnIncomingRequestCheck() {
        return notifyOnIncomingRequestCheck;
    }

    public CheckBox getSoundEnabledCheck() {
        return soundEnabledCheck;
    }

    public CheckBox getRequireApprovalCheck() {
        return requireApprovalCheck;
    }

    public CheckBox getVerifySha256Check() {
        return verifySha256Check;
    }

    public Button getSaveButton() {
        return saveBtn;
    }

    public Button getResetButton() {
        return resetBtn;
    }

    public FeedbackBanner getFeedbackBanner() {
        return feedbackBanner;
    }

    public StatusBadge getTlsStatusBadge() {
        return tlsStatusBadge;
    }
}
