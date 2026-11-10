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
import javafx.application.Platform;
import javafx.css.CssParser;
import javafx.css.Stylesheet;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(JavaFxToolkitExtension.class)
class DesignSystemTest {

    @Test
    void allCssResourcesResolveAndParseCleanly() throws Exception {
        DesignSystem.verifyStylesheetsAvailable();

        CssParser parser = new CssParser();
        int errorsBefore = CssParser.errorsProperty().size();

        for (String path : DesignSystem.ALL_STYLESHEETS) {
            URL url = DesignSystem.requireStylesheet(path);
            assertNotNull(url, "Missing stylesheet: " + path);
            Stylesheet stylesheet = parser.parse(url);
            assertNotNull(stylesheet, "Parsed stylesheet should not be null: " + path);
            assertFalse(stylesheet.getRules().isEmpty(), "Stylesheet should define rules: " + path);
        }

        assertEquals(
            errorsBefore,
            CssParser.errorsProperty().size(),
            "CSS parser reported errors: " + CssParser.errorsProperty()
        );
    }

    @Test
    void missingCssResourceThrowsClearError() {
        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> DesignSystem.requireStylesheet("/com/syncshare/ui/styles/does-not-exist.css")
        );
        assertTrue(error.getMessage().contains("does-not-exist.css"));
    }

    @Test
    void sceneAndViewsRenderWithoutCssWarningsOrInlineStyles() throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            List<String> cssWarnings = new ArrayList<>();
            Logger cssLogger = Logger.getLogger("javafx.css");
            Handler handler = new Handler() {
                @Override
                public void publish(LogRecord record) {
                    if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                        cssWarnings.add(record.getLevel() + ": " + record.getMessage());
                    }
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            };
            cssLogger.addHandler(handler);

            Stage stage = new Stage();
            try {
                SyncShareApplication app = new SyncShareApplication();
                app.init();
                app.initializeStage(stage);
                Platform.setImplicitExit(false);

                Scene scene = stage.getScene();
                // Verify idempotent theme application
                int initialCount = scene.getStylesheets().size();
                DesignSystem.applyTheme(scene);
                assertEquals(initialCount, scene.getStylesheets().size());

                Parent root = scene.getRoot();
                root.applyCss();
                root.layout();

                MainController controller = app.getMainController();
                assertTrue(controller.isOverviewVisible());
                assertFalse(controller.isDesignSystemVisible());
                assertNoInlineStyles(root);

                // Switch to Design System view and force full CSS + layout pass
                controller.showDesignSystemView();
                root.applyCss();
                root.layout();

                assertFalse(controller.isOverviewVisible());
                assertTrue(controller.isDesignSystemVisible());
                assertNoInlineStyles(root);

                assertTrue(cssWarnings.isEmpty(), "Unexpected JavaFX CSS warnings: " + cssWarnings);
            } finally {
                cssLogger.removeHandler(handler);
                stage.close();
            }
        });
    }

    @Test
    void reusableComponentsRenderAndSwitchStatesCorrectly() throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            StatusBadge badge = new StatusBadge("Pending", SemanticTone.WARNING);
            assertTrue(badge.getStyleClass().contains("badge"));
            assertTrue(badge.getStyleClass().contains("badge-warning"));
            badge.setTone(SemanticTone.SUCCESS);
            assertFalse(badge.getStyleClass().contains("badge-warning"));
            assertTrue(badge.getStyleClass().contains("badge-success"));

            badge.setIndicatorMode(StatusBadge.IndicatorMode.ICON);
            assertEquals(2, badge.getChildren().size());

            AtomicBoolean cardActivated = new AtomicBoolean(false);
            UiCard card = new UiCard(UiCard.Variant.ELEVATED)
                .setHeader("Card Title", "Card Subtitle", UiIcons.create(UiIcons.IconType.FILE), badge)
                .setBodyContent(new javafx.scene.control.Label("Body"))
                .setInteractive(() -> cardActivated.set(true));
            assertTrue(card.getStyleClass().contains("card-elevated"));
            assertTrue(card.getStyleClass().contains("card-interactive"));
            assertTrue(card.isFocusTraversable());
            card.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
            assertTrue(cardActivated.get(), "Interactive card should activate on ENTER key");

            ValidatedTextField field = new ValidatedTextField("Peer IP", "192.168.1.10", "Enter IP");
            field.setValidation(SemanticTone.ERROR, "Invalid address");
            assertTrue(field.getTextField().getStyleClass().contains("input-error"));
            field.setValidation(SemanticTone.SUCCESS, "Reachable");
            assertFalse(field.getTextField().getStyleClass().contains("input-error"));
            assertTrue(field.getTextField().getStyleClass().contains("input-success"));
            field.clearValidation();
            assertFalse(field.getTextField().getStyleClass().contains("input-success"));

            LabeledProgressBar progress = new LabeledProgressBar("Upload", 0.5, SemanticTone.TEAL)
                .setSize(LabeledProgressBar.Size.LG);
            assertTrue(progress.getProgressBar().getStyleClass().contains("ss-progress"));
            assertTrue(progress.getProgressBar().getStyleClass().contains("progress-lg"));
            progress.setTone(SemanticTone.ERROR);
            assertTrue(progress.getProgressBar().getStyleClass().contains("progress-error"));

            AtomicBoolean dismissed = new AtomicBoolean(false);
            FeedbackBanner banner = new FeedbackBanner(SemanticTone.WARNING, "Alert", "Check peer", true)
                .setOnDismiss(() -> dismissed.set(true));
            assertTrue(banner.getStyleClass().contains("alert-warning"));
            banner.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
            assertTrue(dismissed.get());
            assertFalse(banner.isVisible());

            Button primaryBtn = UiButtons.create("Send", UiIcons.create(UiIcons.IconType.SEND), UiButtons.Variant.PRIMARY, UiButtons.Size.SM);
            assertTrue(primaryBtn.getStyleClass().containsAll(List.of("btn", "btn-primary", "btn-sm")));
            assertTrue(primaryBtn.getStyle().isEmpty());

            // Render all controls inside a live Scene to verify CSS application
            VBox container = new VBox(12, badge, card, field, progress, primaryBtn);
            Scene scene = new Scene(container, 640, 480);
            DesignSystem.applyTheme(scene);
            container.applyCss();
            container.layout();

            assertTrue(badge.getWidth() > 0 && badge.getHeight() > 0);
            assertTrue(card.getWidth() > 0 && card.getHeight() > 0);
            assertTrue(field.getWidth() > 0 && field.getHeight() > 0);
            assertTrue(progress.getWidth() > 0 && progress.getHeight() > 0);
            assertTrue(primaryBtn.getWidth() > 0 && primaryBtn.getHeight() > 0);
        });
    }

    @Test
    void dialogsApplyDesignSystemStylesAndSemanticClasses() throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            Alert confirm = UiDialogs.confirm(null, "Approve", "Accept file?", "Allow transfer?", "Accept");
            DialogPane pane = confirm.getDialogPane();
            assertTrue(pane.getStyleClass().contains("ss-dialog"));
            assertTrue(pane.getStyleClass().contains("dialog-teal"));
            assertFalse(pane.getStylesheets().isEmpty());
            assertNotNull(pane.getGraphic());

            Node okBtn = pane.lookupButton(confirm.getButtonTypes().get(1));
            assertNotNull(okBtn);
            assertTrue(okBtn.getStyleClass().contains("btn-primary"));

            Alert danger = UiDialogs.confirmDanger(null, "Reject", "Reject request?", "Decline incoming file?", "Reject");
            assertTrue(danger.getDialogPane().getStyleClass().contains("dialog-error"));
            Node dangerBtn = danger.getDialogPane().lookupButton(danger.getButtonTypes().get(1));
            assertNotNull(dangerBtn);
            assertTrue(dangerBtn.getStyleClass().contains("btn-danger"));

            Dialog<ButtonType> custom = UiDialogs.createCustomDialog(
                null,
                "Custom",
                "Details",
                SemanticTone.WARNING,
                new javafx.scene.control.Label("Body")
            );
            assertTrue(custom.getDialogPane().getStyleClass().contains("ss-dialog"));
            assertTrue(custom.getDialogPane().getStyleClass().contains("dialog-warning"));
        });
    }

    @Test
    void layoutResizesCleanlyAcrossDesktopBreakpoints() throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            Stage stage = new Stage();
            try {
                SyncShareApplication app = new SyncShareApplication();
                app.init();
                app.initializeStage(stage);
                Platform.setImplicitExit(false);

                MainController controller = app.getMainController();
                Scene scene = stage.getScene();
                Parent root = scene.getRoot();

                double[][] sizes = {
                    {SyncShareApplication.MIN_WIDTH, SyncShareApplication.MIN_HEIGHT},
                    {SyncShareApplication.INITIAL_WIDTH, SyncShareApplication.INITIAL_HEIGHT},
                    {1440, 900}
                };

                for (double[] dims : sizes) {
                    double width = dims[0];
                    double height = dims[1];

                    stage.setWidth(width);
                    stage.setHeight(height);
                    root.applyCss();
                    root.layout();

                    double sceneWidth = scene.getWidth();
                    double sceneHeight = scene.getHeight();
                    assertTrue(sceneWidth > 0 && sceneHeight > 0);

                    TableView<MainController.TransferPreviewRow> table = controller.getTransferTable();
                    assertNotNull(table);
                    assertTrue(table.getWidth() > 300, "Table should retain usable width at " + width + "x" + height);
                    assertTrue(table.getHeight() >= 180);

                    Bounds tableBounds = table.localToScene(table.getBoundsInLocal());
                    assertTrue(
                        tableBounds.getMaxX() <= sceneWidth + 2.0,
                        "Table overflows scene horizontally at " + width + "x" + height
                    );

                    double totalColumnWidth = table.getColumns().stream()
                        .mapToDouble(TableColumn::getWidth)
                        .sum();
                    assertTrue(
                        totalColumnWidth <= table.getWidth() + 4.0,
                        "Table columns overflow table width (" + totalColumnWidth + " > " + table.getWidth() + ")"
                    );

                    // Verify Design System view also resizes cleanly
                    controller.showDesignSystemView();
                    root.applyCss();
                    root.layout();
                    assertTrue(controller.isDesignSystemVisible());
                    controller.showOverviewView();
                }

                // Verify table filtering and navigation state
                assertEquals(4, controller.getTransferTable().getItems().size());
                controller.getSearchField().setText("architecture");
                assertEquals(1, controller.getTransferTable().getItems().size());
                controller.getSearchField().clear();
                assertEquals(4, controller.getTransferTable().getItems().size());
            } finally {
                stage.close();
            }
        });
    }

    private static void assertNoInlineStyles(Node node) {
        String inlineStyle = node.getStyle();
        assertTrue(
            inlineStyle == null || inlineStyle.isBlank(),
            "Found unexpected inline style on " + node.getClass().getSimpleName() + ": " + inlineStyle
        );
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                assertNoInlineStyles(child);
            }
        }
    }
}
