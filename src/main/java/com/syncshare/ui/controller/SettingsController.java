package com.syncshare.ui.controller;

import com.syncshare.model.AppSettings;
import com.syncshare.model.LocalDeviceInfo;
import com.syncshare.service.SettingsService;
import com.syncshare.service.SyncShareServices;
import com.syncshare.ui.design.DesignSystem;
import com.syncshare.ui.design.FeedbackBanner;
import com.syncshare.ui.design.SemanticTone;
import com.syncshare.ui.design.StatusBadge;
import com.syncshare.ui.design.UiButtons;
import com.syncshare.ui.design.UiCard;
import com.syncshare.ui.design.UiIcons;
import com.syncshare.ui.design.ValidatedTextField;
import com.syncshare.ui.navigation.NavigationAware;
import com.syncshare.ui.navigation.ViewNavigator;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

import java.io.File;

/**
 * Controller for the Settings screen.
 * Delegates validation and state updates to SettingsService and DeviceInfoService.
 */
public class SettingsController implements NavigationAware {

    @FXML
    private VBox settingsRoot;

    private SyncShareServices services;

    private FeedbackBanner feedbackBanner;
    private ValidatedTextField aliasField;
    private ValidatedTextField portField;
    private ValidatedTextField receiveDirField;
    private CheckBox requireApprovalCheck;
    private CheckBox verifySha256Check;
    private CheckBox autoStartCheck;

    private StatusBadge tlsStatusBadge;
    private Label tlsDetailLabel;

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
        this.initializedContext = true;

        services.settingsService().settingsProperty().addListener((obs, oldVal, newVal) -> populateFromModel(newVal));
        services.deviceInfoService().deviceInfoProperty().addListener((obs, oldVal, newVal) -> updateTlsCard(newVal));

        populateFromModel(services.settingsService().getSettings());
        updateTlsCard(services.deviceInfoService().getDeviceInfo());
    }

    @Override
    public void onNavigatedTo() {
        if (initializedContext) {
            populateFromModel(services.settingsService().getSettings());
            updateTlsCard(services.deviceInfoService().getDeviceInfo());
        }
    }

    private void buildLayout() {
        if (settingsRoot == null) {
            return;
        }
        settingsRoot.getChildren().clear();

        feedbackBanner = new FeedbackBanner(
            SemanticTone.INFO,
            "SyncShare Settings",
            "Configure local peer identity, TCP port, receive folder, and security defaults.",
            true
        );

        UiCard generalCard = new UiCard(UiCard.Variant.DEFAULT);
        generalCard.setMinWidth(0);
        generalCard.setHeader(
            "Device & Network Configuration",
            "Local identity and incoming file storage location",
            UiIcons.create(UiIcons.IconType.SETTINGS, UiIcons.Size.LG, SemanticTone.TEAL),
            null
        );

        aliasField = new ValidatedTextField(
            "Device Alias",
            "SyncShare-Workstation",
            "Friendly name displayed on the Dashboard"
        );
        aliasField.setId("settingsAliasField");

        portField = new ValidatedTextField(
            "Listening TCP Port",
            "5050",
            "Valid port range: 1024 - 65535"
        );
        portField.setId("settingsPortField");

        receiveDirField = new ValidatedTextField(
            "Receive Directory",
            "received_files",
            "Folder where verified incoming files are saved"
        );
        receiveDirField.setMinWidth(0);
        HBox.setHgrow(receiveDirField, Priority.ALWAYS);

        Button browseDirBtn = UiButtons.create(
            "Browse...",
            UiIcons.create(UiIcons.IconType.FOLDER, UiIcons.Size.SM),
            UiButtons.Variant.SECONDARY,
            UiButtons.Size.MD
        );
        browseDirBtn.setOnAction(event -> browseReceiveDirectory());

        HBox dirRow = new HBox(DesignSystem.SPACE_XS, receiveDirField, browseDirBtn);
        dirRow.getStyleClass().add("form-row");
        dirRow.setAlignment(Pos.BOTTOM_LEFT);

        generalCard.setBodyContent(aliasField, portField, dirRow);

        UiCard securityCard = new UiCard(UiCard.Variant.DEFAULT);
        securityCard.setMinWidth(0);

        tlsStatusBadge = new StatusBadge("Checking", SemanticTone.NEUTRAL);
        securityCard.setHeader(
            "Security & Verification Policy",
            "Mutual TLS 1.3 and SHA-256 verification controls",
            UiIcons.create(UiIcons.IconType.LOCK, UiIcons.Size.LG, SemanticTone.TEAL),
            tlsStatusBadge
        );

        requireApprovalCheck = new CheckBox("Require manual approval before receiving incoming files");
        requireApprovalCheck.getStyleClass().add("ss-checkbox");

        verifySha256Check = new CheckBox("Verify SHA-256 checksum on every completed transfer");
        verifySha256Check.getStyleClass().add("ss-checkbox");

        autoStartCheck = new CheckBox("Auto-start background listener on startup when mTLS is configured");
        autoStartCheck.getStyleClass().add("ss-checkbox");

        tlsDetailLabel = new Label("Checking mTLS keystore configuration...");
        tlsDetailLabel.getStyleClass().add("text-caption");
        tlsDetailLabel.setWrapText(true);

        Button resetBtn = UiButtons.create("Reset Defaults", UiButtons.Variant.SECONDARY, UiButtons.Size.SM);
        resetBtn.setId("settingsResetButton");
        resetBtn.setOnAction(event -> handleResetDefaults());

        Button saveBtn = UiButtons.create(
            "Save Settings",
            UiIcons.create(UiIcons.IconType.CHECK_CIRCLE, UiIcons.Size.SM),
            UiButtons.Variant.PRIMARY,
            UiButtons.Size.SM
        );
        saveBtn.setId("settingsSaveButton");
        saveBtn.setOnAction(event -> handleSave());

        FlowPane footerActions = new FlowPane(DesignSystem.SPACE_XS, DesignSystem.SPACE_XS);
        footerActions.setAlignment(Pos.CENTER_RIGHT);
        footerActions.getChildren().addAll(resetBtn, saveBtn);

        securityCard.setBodyContent(requireApprovalCheck, verifySha256Check, autoStartCheck, tlsDetailLabel);
        securityCard.setFooterContent(footerActions);

        settingsRoot.getChildren().addAll(feedbackBanner, generalCard, securityCard);
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
        requireApprovalCheck.setSelected(settings.requireApproval());
        verifySha256Check.setSelected(settings.verifySha256());
        autoStartCheck.setSelected(settings.autoStartReceiver());
    }

    private void updateTlsCard(LocalDeviceInfo info) {
        if (info == null) {
            return;
        }
        tlsStatusBadge.setText(info.tlsConfigured() ? "mTLS Configured" : "Setup Required");
        tlsStatusBadge.setTone(info.tlsConfigured() ? SemanticTone.SUCCESS : SemanticTone.WARNING);
        tlsDetailLabel.setText(
            "Identity store: " + info.keyStorePath()
                + " • Trust store: " + info.trustStorePath()
                + " • Status: " + info.tlsSummary()
        );
    }

    private void browseReceiveDirectory() {
        if (Boolean.getBoolean("syncshare.ui.suppressDialogs")) {
            return;
        }
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select SyncShare Receive Directory");
        Window owner = settingsRoot.getScene() != null ? settingsRoot.getScene().getWindow() : null;
        File dir = chooser.showDialog(owner);
        if (dir != null) {
            receiveDirField.setText(dir.getAbsolutePath());
        }
    }

    private void handleSave() {
        if (services == null) {
            return;
        }
        SettingsService settingsService = services.settingsService();
        SettingsService.ValidationResult check = settingsService.validate(
            aliasField.getText(),
            portField.getText(),
            receiveDirField.getText()
        );
        if (!check.valid()) {
            feedbackBanner.setTone(SemanticTone.ERROR)
                .setTitle("Invalid Settings")
                .setMessage(check.message());
            feedbackBanner.show();
            return;
        }

        int port = Integer.parseInt(portField.getText().trim());
        AppSettings updated = new AppSettings(
            aliasField.getText().trim(),
            port,
            receiveDirField.getText().trim(),
            requireApprovalCheck.isSelected(),
            verifySha256Check.isSelected(),
            autoStartCheck.isSelected()
        );
        SettingsService.ValidationResult saved = settingsService.updateSettings(updated);
        feedbackBanner.setTone(saved.valid() ? SemanticTone.SUCCESS : SemanticTone.ERROR)
            .setTitle(saved.valid() ? "Settings Saved" : "Save Failed")
            .setMessage(saved.message());
        feedbackBanner.show();
    }

    private void handleResetDefaults() {
        if (services == null) {
            return;
        }
        services.settingsService().resetDefaults();
        feedbackBanner.setTone(SemanticTone.INFO)
            .setTitle("Defaults Restored")
            .setMessage("Settings reverted to SyncShare defaults.");
        feedbackBanner.show();
    }
}
