package com.syncshare.ui;

import com.syncshare.ui.design.DesignSystem;
import com.syncshare.ui.design.FeedbackBanner;
import com.syncshare.ui.design.LabeledProgressBar;
import com.syncshare.ui.design.SemanticTone;
import com.syncshare.ui.design.StatusBadge;
import com.syncshare.ui.design.UiButtons;
import com.syncshare.ui.design.UiCard;
import com.syncshare.ui.design.UiDialogs;
import com.syncshare.ui.design.UiIcons;
import com.syncshare.ui.design.ValidatedTextField;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class MainController {

    private static final Pattern IPV4_OR_HOST = Pattern.compile(
        "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}|[a-zA-Z0-9.-]{2,})$"
    );

    public record TransferPreviewRow(
        String fileName,
        String peerAddress,
        String sizeText,
        double progress,
        String statusText,
        SemanticTone tone
    ) {
    }

    @FXML
    private StackPane brandMarkContainer;

    @FXML
    private VBox sidebarNavContainer;

    @FXML
    private Button navOverviewButton;

    @FXML
    private Button navDesignSystemButton;

    @FXML
    private Label pageTitleLabel;

    @FXML
    private Label pageSubtitleLabel;

    @FXML
    private HBox topbarRightSlot;

    @FXML
    private Button previewDialogButton;

    @FXML
    private ScrollPane contentScrollPane;

    @FXML
    private StackPane viewHost;

    @FXML
    private VBox overviewView;

    @FXML
    private VBox designSystemView;

    @FXML
    private Region statusDot;

    @FXML
    private Label statusLabel;

    @FXML
    private Label runtimeInfoLabel;

    private volatile boolean initialized;
    private boolean designSystemBuilt;

    private GridPane metricsGrid;
    private GridPane workspaceGrid;
    private ValidatedTextField peerAddressField;
    private ValidatedTextField filePathField;
    private LabeledProgressBar transferProgressBar;
    private FeedbackBanner overviewBanner;
    private TextField searchField;
    private TableView<TransferPreviewRow> transferTable;
    private FilteredList<TransferPreviewRow> filteredTransfers;
    private String activeStatusFilter = "All";

    @FXML
    public void initialize() {
        initialized = true;

        if (statusLabel != null) {
            statusLabel.setText("Status: Ready");
        }

        if (runtimeInfoLabel != null) {
            String javaVersion = System.getProperty("java.version", "unknown");
            String javafxVersion = System.getProperty("javafx.version", "21");
            runtimeInfoLabel.setText("Runtime: Java " + javaVersion + " | JavaFX " + javafxVersion);
        }

        configureBrandAndNavigation();
        configureTopbar();
        buildOverviewView();
        configureResponsiveReflow();
        showOverviewView();
    }

    private void configureBrandAndNavigation() {
        if (brandMarkContainer != null) {
            brandMarkContainer.getChildren().setAll(
                UiIcons.createInverse(UiIcons.IconType.SHIELD_CHECK, UiIcons.Size.LG)
            );
        }

        if (navOverviewButton != null) {
            navOverviewButton.setGraphic(UiIcons.create(UiIcons.IconType.ACTIVITY, UiIcons.Size.MD));
            navOverviewButton.setMnemonicParsing(false);
        }

        if (navDesignSystemButton != null) {
            navDesignSystemButton.setGraphic(UiIcons.create(UiIcons.IconType.LAYERS, UiIcons.Size.MD));
            navDesignSystemButton.setMnemonicParsing(false);
        }

        if (sidebarNavContainer != null) {
            sidebarNavContainer.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
                if (event.getCode() == KeyCode.DOWN || event.getCode() == KeyCode.UP) {
                    List<Button> navButtons = getNavigationButtons();
                    int idx = -1;
                    for (int i = 0; i < navButtons.size(); i++) {
                        if (navButtons.get(i).isFocused()) {
                            idx = i;
                            break;
                        }
                    }
                    if (idx >= 0 && !navButtons.isEmpty()) {
                        int next = event.getCode() == KeyCode.DOWN
                            ? (idx + 1) % navButtons.size()
                            : (idx - 1 + navButtons.size()) % navButtons.size();
                        navButtons.get(next).requestFocus();
                        event.consume();
                    }
                }
            });
        }
    }

    private void configureTopbar() {
        if (topbarRightSlot != null) {
            StatusBadge systemBadge = new StatusBadge("Design System v1.0", SemanticTone.TEAL);
            topbarRightSlot.getChildren().add(0, systemBadge);
        }
        if (previewDialogButton != null) {
            previewDialogButton.setGraphic(UiIcons.create(UiIcons.IconType.SHIELD_CHECK, UiIcons.Size.SM, SemanticTone.TEAL));
            previewDialogButton.setMnemonicParsing(false);
        }
    }

    private void buildOverviewView() {
        if (overviewView == null) {
            return;
        }
        overviewView.getChildren().clear();

        // 1. Summary KPI Metric Cards
        metricsGrid = new GridPane();
        metricsGrid.setHgap(DesignSystem.SPACE_MD);
        metricsGrid.setVgap(DesignSystem.SPACE_MD);

        UiCard securityCard = UiCard.metric(
            "SECURITY",
            "Mutual TLS 1.3",
            "Client & server certificate verification enabled",
            UiIcons.IconType.LOCK,
            SemanticTone.SUCCESS,
            "Encrypted"
        );
        UiCard endpointCard = UiCard.metric(
            "RECEIVER",
            "TCP Port 5050",
            "Standby listener layout ready for peer wiring",
            UiIcons.IconType.PEERS,
            SemanticTone.TEAL,
            "Standby"
        );
        UiCard integrityCard = UiCard.metric(
            "INTEGRITY",
            "SHA-256 Digest",
            "Resumable chunk checkpoints & hash validation",
            UiIcons.IconType.SHIELD_CHECK,
            SemanticTone.INFO,
            "Verified"
        );

        metricsGrid.getChildren().addAll(securityCard, endpointCard, integrityCard);

        // 2. Two-column Workspace Row (Quick Transfer Preview + Feedback/Dialogs)
        workspaceGrid = new GridPane();
        workspaceGrid.setHgap(DesignSystem.SPACE_MD);
        workspaceGrid.setVgap(DesignSystem.SPACE_MD);

        UiCard sendFormCard = buildQuickTransferCard();
        UiCard feedbackCard = buildFeedbackAndDialogsCard();
        workspaceGrid.getChildren().addAll(sendFormCard, feedbackCard);

        // 3. Transfer Activity Table Card
        UiCard tableCard = buildTransferTableCard();

        overviewView.getChildren().addAll(metricsGrid, workspaceGrid, tableCard);
        applyResponsiveGridColumns(overviewView.getWidth());
    }

    private UiCard buildQuickTransferCard() {
        UiCard card = new UiCard(UiCard.Variant.DEFAULT);
        card.setHeader(
            "Outgoing Transfer Controls",
            "Reusable validated text fields, buttons, and progress bars",
            UiIcons.create(UiIcons.IconType.SEND, UiIcons.Size.LG, SemanticTone.TEAL),
            new StatusBadge("UI Preview", SemanticTone.NEUTRAL)
        );

        peerAddressField = new ValidatedTextField(
            "Target Peer Address",
            "e.g. 192.168.1.42",
            "Enter peer IPv4 address or local hostname"
        );
        peerAddressField.setText("192.168.1.42");

        filePathField = new ValidatedTextField(
            "Selected File Path",
            "e.g. C:\\Users\\Documents\\release-notes.pdf",
            "Provide the local file path to verify form styling"
        );
        filePathField.setText("quarterly-report.pdf");

        transferProgressBar = new LabeledProgressBar(
            "Active Chunk Stream",
            0.68,
            SemanticTone.TEAL
        ).setDetail("142.8 MB of 210.0 MB • SHA-256 checkpoint active");

        Button validateBtn = UiButtons.create(
            "Validate",
            UiIcons.create(UiIcons.IconType.CHECK_CIRCLE, UiIcons.Size.SM),
            UiButtons.Variant.PRIMARY,
            UiButtons.Size.SM
        );
        validateBtn.setOnAction(e -> validateTransferForm());

        Button warnBtn = UiButtons.create("Warn", UiButtons.Variant.OUTLINE_TEAL, UiButtons.Size.SM);
        warnBtn.setOnAction(e -> simulateWarningState());

        Button resetBtn = UiButtons.create("Reset", UiButtons.Variant.SECONDARY, UiButtons.Size.SM);
        resetBtn.setOnAction(e -> resetTransferForm());

        HBox actions = new HBox(DesignSystem.SPACE_XS, validateBtn, warnBtn, resetBtn);
        actions.setAlignment(Pos.CENTER_RIGHT);

        card.setBodyContent(peerAddressField, filePathField, transferProgressBar);
        card.setFooterContent(actions);
        return card;
    }

    private UiCard buildFeedbackAndDialogsCard() {
        UiCard card = new UiCard(UiCard.Variant.DEFAULT);
        card.setHeader(
            "System Alerts & Dialogs",
            "Standardized error, warning, success, and confirmation patterns",
            UiIcons.create(UiIcons.IconType.INFO_CIRCLE, UiIcons.Size.LG, SemanticTone.INFO),
            null
        );

        overviewBanner = new FeedbackBanner(
            SemanticTone.INFO,
            "Design System Active",
            "All controls inherit centralized deep-navy, teal, and light-surface tokens."
        );

        FeedbackBanner successBanner = new FeedbackBanner(
            SemanticTone.SUCCESS,
            "Checksum Verified",
            "Sample banner showing dismissible success feedback with ESC key support.",
            true
        );

        FeedbackBanner warningBanner = new FeedbackBanner(
            SemanticTone.WARNING,
            "Awaiting Peer Approval",
            "Incoming request #12 requires user confirmation before writing chunks."
        );

        Button infoDlgBtn = UiButtons.create("Info", UiButtons.Variant.SECONDARY, UiButtons.Size.SM);
        infoDlgBtn.setOnAction(e -> showAlertIfInteractive(
            UiDialogs.info(currentWindow(), "SyncShare Info", "Peer Discovery Ready", "Local peer discovery metadata can be wired directly into this dialog helper.")
        ));

        Button successDlgBtn = UiButtons.create("Success", UiButtons.Variant.SUCCESS, UiButtons.Size.SM);
        successDlgBtn.setOnAction(e -> showAlertIfInteractive(
            UiDialogs.success(currentWindow(), "Transfer Complete", "SHA-256 Integrity Verified", "The received file matched the sender's cryptographic checksum.")
        ));

        Button warnDlgBtn = UiButtons.create("Warning", UiButtons.Variant.WARNING, UiButtons.Size.SM);
        warnDlgBtn.setOnAction(e -> showAlertIfInteractive(
            UiDialogs.warning(currentWindow(), "Resume Checkpoint", "Partial File Detected", "An interrupted transfer checkpoint exists for this peer. Resuming from byte 1,048,576.")
        ));

        Button errorDlgBtn = UiButtons.create("Error", UiButtons.Variant.DANGER, UiButtons.Size.SM);
        errorDlgBtn.setOnAction(e -> showAlertIfInteractive(
            UiDialogs.error(currentWindow(), "Transfer Error", "Connection Timed Out", "Peer 192.168.1.99 did not respond within the mTLS handshake window.")
        ));

        FlowPane dialogButtons = new FlowPane(DesignSystem.SPACE_XS, DesignSystem.SPACE_XS);
        dialogButtons.getChildren().addAll(infoDlgBtn, successDlgBtn, warnDlgBtn, errorDlgBtn);

        card.setBodyContent(overviewBanner, successBanner, warningBanner);
        card.setFooterContent(dialogButtons);
        return card;
    }

    private UiCard buildTransferTableCard() {
        UiCard card = new UiCard(UiCard.Variant.DEFAULT);
        card.setHeader(
            "Transfer Queue Preview",
            "Responsive TableView with semantic badges and compact progress bars",
            UiIcons.create(UiIcons.IconType.FILE, UiIcons.Size.LG, SemanticTone.TEAL),
            new StatusBadge("4 Items", SemanticTone.TEAL)
        );

        HBox filterBar = new HBox(DesignSystem.SPACE_SM);
        filterBar.setAlignment(Pos.CENTER_LEFT);

        searchField = new TextField();
        searchField.setPromptText("Filter files or peers...");
        searchField.setAccessibleText("Filter transfers by file name or peer");
        searchField.getStyleClass().addAll("input-field", "search-field");
        searchField.setPrefWidth(220);
        HBox.setHgrow(searchField, Priority.ALWAYS);

        HBox segmented = new HBox();
        segmented.getStyleClass().add("segmented-bar");
        segmented.setMinWidth(Region.USE_PREF_SIZE);
        List<String> filters = List.of("All", "Active", "Verified", "Failed");
        for (String filter : filters) {
            Button segBtn = new Button(filter);
            segBtn.setMnemonicParsing(false);
            segBtn.setMinWidth(Region.USE_PREF_SIZE);
            segBtn.getStyleClass().add("segmented-item");
            if ("All".equals(filter)) {
                segBtn.getStyleClass().add("segmented-item-active");
            }
            segBtn.setOnAction(e -> {
                activeStatusFilter = filter;
                for (Node sibling : segmented.getChildren()) {
                    sibling.getStyleClass().remove("segmented-item-active");
                }
                segBtn.getStyleClass().add("segmented-item-active");
                applyTransferFilter();
            });
            segmented.getChildren().add(segBtn);
        }

        filterBar.getChildren().addAll(searchField, segmented);

        ObservableList<TransferPreviewRow> rows = FXCollections.observableArrayList(
            new TransferPreviewRow("design-assets-v2.zip", "192.168.1.42", "210.0 MB", 0.68, "Sending", SemanticTone.TEAL),
            new TransferPreviewRow("architecture-spec.pdf", "192.168.1.18", "14.2 MB", 1.00, "Verified", SemanticTone.SUCCESS),
            new TransferPreviewRow("database-backup.tar.gz", "10.0.0.15", "842.5 MB", 0.42, "Pending", SemanticTone.WARNING),
            new TransferPreviewRow("build-artifact.jar", "192.168.1.99", "64.8 MB", 0.25, "Failed", SemanticTone.ERROR)
        );
        filteredTransfers = new FilteredList<>(rows, row -> true);
        searchField.textProperty().addListener((obs, oldVal, newVal) -> applyTransferFilter());

        transferTable = createStyledTransferTable(filteredTransfers);
        card.setBodyContent(filterBar, transferTable);
        return card;
    }

    private TableView<TransferPreviewRow> createStyledTransferTable(ObservableList<TransferPreviewRow> items) {
        TableView<TransferPreviewRow> table = new TableView<>(items);
        table.getStyleClass().add("ss-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPrefHeight(236);
        table.setMinHeight(196);
        table.setPlaceholder(new Label("No matching transfer previews found."));

        TableColumn<TransferPreviewRow, String> fileCol = new TableColumn<>("FILE");
        fileCol.setMinWidth(130);
        fileCol.setCellValueFactory(data -> new ReadOnlyStringWrapper(data.getValue().fileName()));

        TableColumn<TransferPreviewRow, String> peerCol = new TableColumn<>("PEER");
        peerCol.setMinWidth(95);
        peerCol.setCellValueFactory(data -> new ReadOnlyStringWrapper(data.getValue().peerAddress()));
        peerCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().remove("table-cell-mono");
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item);
                    getStyleClass().add("table-cell-mono");
                }
            }
        });

        TableColumn<TransferPreviewRow, String> sizeCol = new TableColumn<>("SIZE");
        sizeCol.setMinWidth(70);
        sizeCol.setCellValueFactory(data -> new ReadOnlyStringWrapper(data.getValue().sizeText()));
        sizeCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().remove("table-cell-mono");
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item);
                    getStyleClass().add("table-cell-mono");
                }
            }
        });

        TableColumn<TransferPreviewRow, TransferPreviewRow> progressCol = new TableColumn<>("PROGRESS");
        progressCol.setMinWidth(115);
        progressCol.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue()));
        progressCol.setCellFactory(col -> new TableCell<>() {
            private final ProgressBar bar = new ProgressBar();
            private final Label pct = new Label();
            private final HBox box = new HBox(DesignSystem.SPACE_XS, bar, pct);

            {
                box.setAlignment(Pos.CENTER_LEFT);
                bar.getStyleClass().addAll("ss-progress", "progress-sm");
                bar.setMinWidth(48);
                bar.setPrefWidth(68);
                HBox.setHgrow(bar, Priority.ALWAYS);
                pct.getStyleClass().add("text-mono-sm");
                pct.setMinWidth(Region.USE_PREF_SIZE);
            }

            @Override
            protected void updateItem(TransferPreviewRow row, boolean empty) {
                super.updateItem(row, empty);
                if (empty || row == null) {
                    setGraphic(null);
                } else {
                    bar.setProgress(row.progress());
                    String progressToneClass = row.tone() == SemanticTone.TEAL
                        ? null
                        : row.tone().progressStyleClass();
                    DesignSystem.swapExclusiveClass(bar, SemanticTone.allProgressClasses(), progressToneClass);
                    pct.setText(Math.round(row.progress() * 100) + "%");
                    setGraphic(box);
                }
            }
        });

        TableColumn<TransferPreviewRow, TransferPreviewRow> statusCol = new TableColumn<>("STATUS");
        statusCol.setMinWidth(95);
        statusCol.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue()));
        statusCol.setCellFactory(col -> new TableCell<>() {
            private final StatusBadge badge = new StatusBadge();

            {
                badge.setCompact(true);
            }

            @Override
            protected void updateItem(TransferPreviewRow row, boolean empty) {
                super.updateItem(row, empty);
                if (empty || row == null) {
                    setGraphic(null);
                } else {
                    badge.setText(row.statusText());
                    badge.setTone(row.tone());
                    setGraphic(badge);
                }
            }
        });

        table.getColumns().addAll(List.of(fileCol, peerCol, sizeCol, progressCol, statusCol));
        return table;
    }

    private void applyTransferFilter() {
        if (filteredTransfers == null) {
            return;
        }
        String query = searchField != null && searchField.getText() != null
            ? searchField.getText().trim().toLowerCase(Locale.ROOT)
            : "";

        filteredTransfers.setPredicate(row -> {
            boolean matchesQuery = query.isEmpty()
                || row.fileName().toLowerCase(Locale.ROOT).contains(query)
                || row.peerAddress().toLowerCase(Locale.ROOT).contains(query)
                || row.statusText().toLowerCase(Locale.ROOT).contains(query);

            boolean matchesTab = switch (activeStatusFilter) {
                case "Active" -> row.tone() == SemanticTone.TEAL || row.tone() == SemanticTone.WARNING;
                case "Verified" -> row.tone() == SemanticTone.SUCCESS;
                case "Failed" -> row.tone() == SemanticTone.ERROR;
                default -> true;
            };
            return matchesQuery && matchesTab;
        });
    }

    private void buildDesignSystemView() {
        if (designSystemView == null || designSystemBuilt) {
            return;
        }
        designSystemBuilt = true;

        // Section 1: Tokens & Typography
        UiCard tokensCard = new UiCard(UiCard.Variant.DEFAULT);
        tokensCard.setHeader(
            "1. Color Tokens & Typography",
            "Deep navy surfaces, teal primary accents, neutral surfaces, and readable type hierarchy",
            UiIcons.create(UiIcons.IconType.LAYERS, UiIcons.Size.LG, SemanticTone.TEAL),
            new StatusBadge("tokens.css", SemanticTone.TEAL)
        );

        FlowPane swatches = new FlowPane(DesignSystem.SPACE_MD, DesignSystem.SPACE_SM);
        swatches.getChildren().addAll(
            createSwatch("Navy 900", "#0F172A", "swatch-navy-900"),
            createSwatch("Navy 800", "#1E293B", "swatch-navy-800"),
            createSwatch("Teal 700", "#0F766E", "swatch-teal-700"),
            createSwatch("Teal 600", "#0D9488", "swatch-teal-600"),
            createSwatch("Teal 400", "#2DD4BF", "swatch-teal-400"),
            createSwatch("Canvas", "#F4F7FA", "swatch-surface-canvas"),
            createSwatch("Card", "#FFFFFF", "swatch-surface-card"),
            createSwatch("Success", "#16A34A", "swatch-success"),
            createSwatch("Warning", "#F59E0B", "swatch-warning"),
            createSwatch("Error", "#DC2626", "swatch-error"),
            createSwatch("Info", "#0284C7", "swatch-info")
        );

        VBox typeScale = new VBox(6);
        Label tDisplay = new Label("Display Heading — SyncShare Desktop");
        tDisplay.getStyleClass().add("text-display");
        Label tTitle = new Label("Section Title — Peer-to-Peer Encrypted Transfers");
        tTitle.getStyleClass().add("text-section-title");
        Label tBody = new Label("Body Text — Readable 13px system typography with clear hierarchy and spacing.");
        tBody.getStyleClass().add("text-body");
        tBody.setWrapText(true);
        Label tMono = new Label("192.168.1.42:5050  •  SHA256: 8f94c2e1...a09b");
        tMono.getStyleClass().add("text-mono");
        typeScale.getChildren().addAll(tDisplay, tTitle, tBody, tMono);

        tokensCard.setBodyContent(swatches, new Separator(), typeScale);

        // Section 2: Buttons & Badges
        UiCard buttonsCard = new UiCard(UiCard.Variant.DEFAULT);
        buttonsCard.setHeader(
            "2. Reusable Buttons & 5. Status Badges",
            "Accessible focus rings, semantic variants, sizes, and status indicators",
            UiIcons.create(UiIcons.IconType.CHECK_CIRCLE, UiIcons.Size.LG, SemanticTone.TEAL),
            null
        );

        FlowPane buttonRow = new FlowPane(DesignSystem.SPACE_XS, DesignSystem.SPACE_XS);
        Button disabledBtn = UiButtons.create("Disabled", UiButtons.Variant.PRIMARY);
        disabledBtn.setDisable(true);
        buttonRow.getChildren().addAll(
            UiButtons.create("Primary Teal", UiIcons.create(UiIcons.IconType.SEND, UiIcons.Size.SM), UiButtons.Variant.PRIMARY),
            UiButtons.create("Secondary", UiButtons.Variant.SECONDARY),
            UiButtons.create("Outline Teal", UiButtons.Variant.OUTLINE_TEAL),
            UiButtons.create("Ghost", UiButtons.Variant.GHOST),
            UiButtons.create("Deep Navy", UiButtons.Variant.NAVY),
            UiButtons.create("Success", UiButtons.Variant.SUCCESS),
            UiButtons.create("Warning", UiButtons.Variant.WARNING),
            UiButtons.create("Danger", UiButtons.Variant.DANGER),
            UiButtons.create("Small", null, UiButtons.Variant.SECONDARY, UiButtons.Size.SM),
            UiButtons.create("Large", null, UiButtons.Variant.PRIMARY, UiButtons.Size.LG),
            disabledBtn
        );

        FlowPane badgeRow = new FlowPane(DesignSystem.SPACE_XS, DesignSystem.SPACE_XS);
        badgeRow.getChildren().addAll(
            new StatusBadge("Standby", SemanticTone.NEUTRAL),
            new StatusBadge("Connected", SemanticTone.TEAL),
            new StatusBadge("Syncing", SemanticTone.INFO),
            new StatusBadge("Verified", SemanticTone.SUCCESS, StatusBadge.IndicatorMode.ICON),
            new StatusBadge("Pending Approval", SemanticTone.WARNING, StatusBadge.IndicatorMode.ICON),
            new StatusBadge("Rejected", SemanticTone.ERROR, StatusBadge.IndicatorMode.ICON)
        );
        StatusBadge darkBadge = new StatusBadge("mTLS Active", SemanticTone.NEUTRAL, StatusBadge.IndicatorMode.NONE);
        DesignSystem.swapExclusiveClass(darkBadge, SemanticTone.allBadgeClasses(), "badge-dark");
        badgeRow.getChildren().add(darkBadge);

        buttonsCard.setBodyContent(buttonRow, new Separator(), badgeRow);

        // Section 3 & 4: Cards & Form Inputs
        UiCard formsCard = new UiCard(UiCard.Variant.DEFAULT);
        formsCard.setHeader(
            "3. Cards, 4. Form Inputs & 6. Progress Indicators",
            "Input validation states, combo boxes, checkboxes, and semantic progress bars",
            UiIcons.create(UiIcons.IconType.ACTIVITY, UiIcons.Size.LG, SemanticTone.TEAL),
            null
        );

        ValidatedTextField normalInput = new ValidatedTextField("Standard Input", "Peer alias", "Neutral helper hint");
        ValidatedTextField successInput = new ValidatedTextField("Success State", "192.168.1.42", "");
        successInput.setText("192.168.1.42");
        successInput.setValidation(SemanticTone.SUCCESS, "Reachable local peer verified");

        ValidatedTextField warningInput = new ValidatedTextField("Warning State", "Resume offset", "");
        warningInput.setText("partial-transfer.part");
        warningInput.setValidation(SemanticTone.WARNING, "Partial checkpoint will be resumed");

        ValidatedTextField errorInput = new ValidatedTextField("Error State", "Invalid host", "");
        errorInput.setText("999.999.0.1");
        errorInput.setValidation(SemanticTone.ERROR, "Enter a valid IPv4 address or hostname");

        HBox extraInputs = new HBox(DesignSystem.SPACE_SM);
        extraInputs.setAlignment(Pos.CENTER_LEFT);
        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("Keystore passphrase");
        passwordField.getStyleClass().add("input-field");
        ComboBox<String> comboBox = new ComboBox<>(FXCollections.observableArrayList("Port 5050 (Default)", "Port 5051", "Port 6050"));
        comboBox.getSelectionModel().selectFirst();
        comboBox.getStyleClass().add("combo-input");
        CheckBox checkBox = new CheckBox("Require SHA-256 verification");
        checkBox.setSelected(true);
        checkBox.getStyleClass().add("ss-checkbox");
        extraInputs.getChildren().addAll(passwordField, comboBox, checkBox);

        LabeledProgressBar pTeal = new LabeledProgressBar("Primary Transfer (Teal)", 0.72, SemanticTone.TEAL);
        LabeledProgressBar pSuccess = new LabeledProgressBar("Verified (Success)", 1.0, SemanticTone.SUCCESS);
        LabeledProgressBar pWarning = new LabeledProgressBar("Paused (Warning)", 0.45, SemanticTone.WARNING);
        LabeledProgressBar pError = new LabeledProgressBar("Interrupted (Error)", 0.28, SemanticTone.ERROR);
        LabeledProgressBar pIndet = new LabeledProgressBar("Handshake (Indeterminate)", -1, SemanticTone.INFO)
            .setSize(LabeledProgressBar.Size.SM);

        formsCard.setBodyContent(
            normalInput, successInput, warningInput, errorInput,
            extraInputs, new Separator(),
            pTeal, pSuccess, pWarning, pError, pIndet
        );

        // Section 7, 9, 10: Navigation Tabs, Card Variants, Banners
        UiCard patternsCard = new UiCard(UiCard.Variant.DEFAULT);
        patternsCard.setHeader(
            "7. Tabs, 3. Card Variants & 10. Semantic Banners",
            "Keyboard-navigable tabs, card surfaces, and inline feedback alerts",
            UiIcons.create(UiIcons.IconType.PEERS, UiIcons.Size.LG, SemanticTone.TEAL),
            null
        );

        TabPane tabPane = new TabPane();
        tabPane.getStyleClass().add("ss-tabs");
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        HBox cardVariantsRow = new HBox(DesignSystem.SPACE_SM);
        cardVariantsRow.setAlignment(Pos.TOP_LEFT);
        cardVariantsRow.setFillHeight(false);
        UiCard elevated = new UiCard(UiCard.Variant.ELEVATED)
            .setHeader("Elevated Card", "Subtle drop shadow")
            .setBodyContent(createWrapBody("Used for focal transfer workflows."));
        UiCard muted = new UiCard(UiCard.Variant.MUTED)
            .setHeader("Muted Card", "Light-grey surface")
            .setBodyContent(createWrapBody("Used for secondary metadata."));
        UiCard navy = new UiCard(UiCard.Variant.NAVY)
            .setHeader("Navy Card", "Brand dark contrast")
            .setBodyContent(createWrapBody("Used for security status."));
        UiCard interactive = new UiCard(UiCard.Variant.DEFAULT)
            .setHeader("Interactive Card", "Press Enter or click")
            .setBodyContent(createWrapBody("Keyboard accessible focus ring."))
            .setInteractive(() -> setStatus("Status: Interactive card activated"));
        HBox.setHgrow(elevated, Priority.ALWAYS);
        HBox.setHgrow(muted, Priority.ALWAYS);
        HBox.setHgrow(navy, Priority.ALWAYS);
        HBox.setHgrow(interactive, Priority.ALWAYS);
        cardVariantsRow.getChildren().addAll(elevated, muted, navy, interactive);

        VBox bannersBox = new VBox(DesignSystem.SPACE_XS,
            new FeedbackBanner(SemanticTone.INFO, "Information", "TLS certificate fingerprint verified for local network."),
            new FeedbackBanner(SemanticTone.SUCCESS, "Success", "File received and verified with SHA-256."),
            new FeedbackBanner(SemanticTone.WARNING, "Warning", "Receiver approval request expires in 30 seconds."),
            new FeedbackBanner(SemanticTone.ERROR, "Error", "Checksum mismatch detected. Transfer aborted safely.")
        );

        Tab cardsTab = new Tab("Card Surfaces", cardVariantsRow);
        Tab alertsTab = new Tab("Feedback Banners", bannersBox);
        tabPane.getTabs().addAll(cardsTab, alertsTab);

        patternsCard.setBodyContent(tabPane);

        designSystemView.getChildren().addAll(tokensCard, buttonsCard, formsCard, patternsCard);
    }

    private Label createWrapBody(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("text-body");
        label.setWrapText(true);
        return label;
    }

    private HBox createSwatch(String name, String hex, String swatchClass) {
        HBox box = new HBox(DesignSystem.SPACE_XS);
        box.setAlignment(Pos.CENTER_LEFT);
        Region chip = new Region();
        chip.getStyleClass().addAll("swatch-chip", swatchClass);
        VBox labels = new VBox(1);
        Label title = new Label(name);
        title.getStyleClass().add("text-body-strong");
        Label code = new Label(hex);
        code.getStyleClass().add("text-mono-sm");
        labels.getChildren().addAll(title, code);
        box.getChildren().addAll(chip, labels);
        return box;
    }

    private void configureResponsiveReflow() {
        if (overviewView != null) {
            overviewView.widthProperty().addListener((obs, oldWidth, newWidth) ->
                applyResponsiveGridColumns(newWidth.doubleValue())
            );
        }
    }

    private void applyResponsiveGridColumns(double availableWidth) {
        double width = availableWidth > 0 ? availableWidth : 680;
        if (metricsGrid != null) {
            int metricCols = width >= 660 ? 3 : (width >= 440 ? 2 : 1);
            reflowGrid(metricsGrid, metricCols);
        }
        if (workspaceGrid != null) {
            int workspaceCols = width >= 640 ? 2 : 1;
            reflowGrid(workspaceGrid, workspaceCols);
        }
    }

    private void reflowGrid(GridPane grid, int columns) {
        grid.getColumnConstraints().clear();
        for (int i = 0; i < columns; i++) {
            ColumnConstraints cc = new ColumnConstraints();
            cc.setPercentWidth(100.0 / columns);
            cc.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().add(cc);
        }
        List<Node> children = List.copyOf(grid.getChildren());
        for (int idx = 0; idx < children.size(); idx++) {
            Node child = children.get(idx);
            GridPane.setColumnIndex(child, idx % columns);
            GridPane.setRowIndex(child, idx / columns);
            GridPane.setHgrow(child, Priority.ALWAYS);
            if (columns == 2 && children.size() == 3 && idx == 2) {
                GridPane.setColumnSpan(child, 2);
            } else {
                GridPane.setColumnSpan(child, 1);
            }
        }
    }

    private void validateTransferForm() {
        String peer = peerAddressField.getText() != null ? peerAddressField.getText().trim() : "";
        String file = filePathField.getText() != null ? filePathField.getText().trim() : "";

        boolean peerValid = !peer.isEmpty() && IPV4_OR_HOST.matcher(peer).matches();
        boolean fileValid = !file.isEmpty();

        if (!peerValid) {
            peerAddressField.setValidation(SemanticTone.ERROR, "Enter a valid peer IPv4 address or hostname.");
        } else {
            peerAddressField.setValidation(SemanticTone.SUCCESS, "Peer address syntax valid (" + peer + ").");
        }

        if (!fileValid) {
            filePathField.setValidation(SemanticTone.ERROR, "File path cannot be empty.");
        } else {
            filePathField.setValidation(SemanticTone.SUCCESS, "File path format valid.");
        }

        if (peerValid && fileValid) {
            transferProgressBar.setTone(SemanticTone.SUCCESS).setProgress(1.0)
                .setDetail("Validation succeeded • Ready for FileSender integration");
            overviewBanner.setTone(SemanticTone.SUCCESS)
                .setTitle("Validation Passed")
                .setMessage("Target peer and file input satisfy UI preflight checks.");
            setStatus("Status: Ready");
        } else {
            transferProgressBar.setTone(SemanticTone.ERROR).setProgress(0.15)
                .setDetail("Fix highlighted validation errors before sending");
            overviewBanner.setTone(SemanticTone.ERROR)
                .setTitle("Validation Error")
                .setMessage("Please correct the highlighted fields below.");
            setStatus("Status: Validation Error");
        }
    }

    private void simulateWarningState() {
        peerAddressField.setValidation(SemanticTone.WARNING, "Peer certificate fingerprint uncached.");
        filePathField.setValidation(SemanticTone.WARNING, "Large file detected; resume checkpoint enabled.");
        transferProgressBar.setTone(SemanticTone.WARNING).setProgress(0.45)
            .setDetail("Paused at resume checkpoint (94.5 MB / 210.0 MB)");
        overviewBanner.setTone(SemanticTone.WARNING)
            .setTitle("Approval & Resume Warning")
            .setMessage("Simulated warning state active across form, progress bar, and banner.");
        setStatus("Status: Warning Preview");
    }

    private void resetTransferForm() {
        peerAddressField.setText("192.168.1.42");
        peerAddressField.clearValidation();
        filePathField.setText("quarterly-report.pdf");
        filePathField.clearValidation();
        transferProgressBar.setTone(SemanticTone.TEAL).setProgress(0.68)
            .setDetail("142.8 MB of 210.0 MB • SHA-256 checkpoint active");
        overviewBanner.setTone(SemanticTone.INFO)
            .setTitle("Design System Active")
            .setMessage("All controls inherit centralized deep-navy, teal, and light-surface tokens.");
        setStatus("Status: Ready");
    }

    @FXML
    private void handleShowOverview() {
        showOverviewView();
    }

    @FXML
    private void handleShowDesignSystem() {
        showDesignSystemView();
    }

    @FXML
    private void handlePreviewApprovalDialog() {
        VBox body = new VBox(DesignSystem.SPACE_SM);
        Label summary = new Label("Peer 192.168.1.42 wants to send a file over Mutual TLS:");
        summary.getStyleClass().add("text-body");
        summary.setWrapText(true);

        UiCard details = new UiCard(UiCard.Variant.MUTED).setCompact(true);
        Label fileInfo = new Label("File: release-package-v2.zip (210.0 MB)");
        fileInfo.getStyleClass().add("text-body-strong");
        Label hashInfo = new Label("SHA-256: 8f94c2e1...9921c4a0");
        hashInfo.getStyleClass().add("text-mono-sm");
        details.getChildren().setAll(fileInfo, hashInfo);

        body.getChildren().addAll(summary, details);

        ButtonType reject = new ButtonType("Reject", ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType accept = new ButtonType("Accept Transfer", ButtonBar.ButtonData.OK_DONE);
        Dialog<ButtonType> dialog = UiDialogs.createCustomDialog(
            currentWindow(),
            "Incoming File Request",
            "Approve Encrypted Transfer?",
            SemanticTone.TEAL,
            body,
            reject,
            accept
        );

        if (!Boolean.getBoolean("syncshare.ui.suppressDialogs")) {
            dialog.showAndWait();
        }
    }

    public void showOverviewView() {
        setActiveView(true);
    }

    public void showDesignSystemView() {
        buildDesignSystemView();
        setActiveView(false);
    }

    private void setActiveView(boolean showOverview) {
        if (overviewView != null) {
            overviewView.setVisible(showOverview);
            overviewView.setManaged(showOverview);
        }
        if (designSystemView != null) {
            designSystemView.setVisible(!showOverview);
            designSystemView.setManaged(!showOverview);
        }

        DesignSystem.setStyleClassPresent(navOverviewButton, "nav-item-active", showOverview);
        DesignSystem.setStyleClassPresent(navDesignSystemButton, "nav-item-active", !showOverview);

        if (pageTitleLabel != null) {
            pageTitleLabel.setText(showOverview ? "Workspace Overview" : "SyncShare Design System");
        }
        if (pageSubtitleLabel != null) {
            pageSubtitleLabel.setText(
                showOverview
                    ? "Reusable desktop components and transfer monitoring surfaces"
                    : "Centralized CSS tokens, buttons, cards, forms, badges, progress bars, tables, and dialogs"
            );
        }

        if (contentScrollPane != null) {
            contentScrollPane.setVvalue(0);
        }
    }

    private void showAlertIfInteractive(Alert alert) {
        if (!Boolean.getBoolean("syncshare.ui.suppressDialogs")) {
            alert.showAndWait();
        }
    }

    private Window currentWindow() {
        return viewHost != null && viewHost.getScene() != null ? viewHost.getScene().getWindow() : null;
    }

    private void setStatus(String text) {
        if (statusLabel != null) {
            statusLabel.setText(text);
        }
    }

    public boolean isInitialized() {
        return initialized;
    }

    public String getStatusText() {
        return statusLabel != null ? statusLabel.getText() : "";
    }

    public boolean isOverviewVisible() {
        return overviewView != null && overviewView.isVisible();
    }

    public boolean isDesignSystemVisible() {
        return designSystemView != null && designSystemView.isVisible();
    }

    public List<Button> getNavigationButtons() {
        if (navOverviewButton == null || navDesignSystemButton == null) {
            return List.of();
        }
        return List.of(navOverviewButton, navDesignSystemButton);
    }

    public TableView<TransferPreviewRow> getTransferTable() {
        return transferTable;
    }

    public TextField getSearchField() {
        return searchField;
    }

    public ValidatedTextField getPeerAddressField() {
        return peerAddressField;
    }

    public ValidatedTextField getFilePathField() {
        return filePathField;
    }

    public void shutdown() {
        if (statusLabel != null) {
            statusLabel.setText("Status: Shutting down...");
        }
        if (statusDot != null) {
            DesignSystem.swapExclusiveClass(statusDot, SemanticTone.allDotClasses(), SemanticTone.WARNING.dotStyleClass());
        }
    }
}
