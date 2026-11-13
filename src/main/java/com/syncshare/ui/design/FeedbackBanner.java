package com.syncshare.ui.design;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.Objects;

/**
 * Reusable inline status banner for error, warning, success, and info messages.
 */
public class FeedbackBanner extends HBox {

    private final StackPane iconHolder = new StackPane();
    private final VBox textBox = new VBox(2);
    private final Label titleLabel = new Label();
    private final Label messageLabel = new Label();
    private final HBox actionSlot = new HBox(DesignSystem.SPACE_XS);

    private SemanticTone tone = SemanticTone.INFO;
    private boolean dismissible;
    private Runnable onDismiss;

    public FeedbackBanner() {
        this(SemanticTone.INFO, "", "", false);
    }

    public FeedbackBanner(SemanticTone tone, String title, String message) {
        this(tone, title, message, false);
    }

    public FeedbackBanner(SemanticTone tone, String title, String message, boolean dismissible) {
        getStyleClass().add("alert-banner");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(DesignSystem.SPACE_SM);

        titleLabel.getStyleClass().add("alert-title");
        titleLabel.setWrapText(true);

        messageLabel.getStyleClass().add("alert-message");
        messageLabel.setWrapText(true);

        HBox.setHgrow(textBox, Priority.ALWAYS);
        textBox.getChildren().addAll(titleLabel, messageLabel);

        actionSlot.setAlignment(Pos.CENTER_RIGHT);

        getChildren().addAll(iconHolder, textBox, actionSlot);

        addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (this.dismissible && event.getCode() == KeyCode.ESCAPE) {
                dismiss();
                event.consume();
            }
        });

        setTone(tone);
        setTitle(title);
        setMessage(message);
        setDismissible(dismissible);
    }

    public FeedbackBanner setTone(SemanticTone tone) {
        this.tone = Objects.requireNonNullElse(tone, SemanticTone.INFO);
        DesignSystem.swapExclusiveClass(this, SemanticTone.allAlertClasses(), this.tone.alertStyleClass());

        StackPane icon = UiIcons.create(UiIcons.iconForTone(this.tone), UiIcons.Size.MD, this.tone);
        iconHolder.getChildren().setAll(icon);
        return this;
    }

    public SemanticTone getTone() {
        return tone;
    }

    public FeedbackBanner setTitle(String title) {
        String safe = title != null ? title : "";
        titleLabel.setText(safe);
        titleLabel.setManaged(!safe.isBlank());
        titleLabel.setVisible(!safe.isBlank());
        return this;
    }

    public String getTitle() {
        return titleLabel.getText();
    }

    public FeedbackBanner setMessage(String message) {
        String safe = message != null ? message : "";
        messageLabel.setText(safe);
        messageLabel.setManaged(!safe.isBlank());
        messageLabel.setVisible(!safe.isBlank());
        return this;
    }

    public String getMessage() {
        return messageLabel.getText();
    }

    public FeedbackBanner setDismissible(boolean dismissible) {
        this.dismissible = dismissible;
        rebuildActions(null);
        return this;
    }

    public FeedbackBanner setAction(String actionLabel, Runnable onAction) {
        Button actionBtn = UiButtons.create(actionLabel, UiButtons.Variant.SECONDARY);
        UiButtons.apply(actionBtn, UiButtons.Variant.SECONDARY, UiButtons.Size.SM);
        actionBtn.setOnAction(e -> {
            if (onAction != null) {
                onAction.run();
            }
        });
        rebuildActions(actionBtn);
        return this;
    }

    public FeedbackBanner setOnDismiss(Runnable onDismiss) {
        this.onDismiss = onDismiss;
        return this;
    }

    public void dismiss() {
        setVisible(false);
        setManaged(false);
        if (onDismiss != null) {
            onDismiss.run();
        }
    }

    public void show() {
        setVisible(true);
        setManaged(true);
    }

    public void show(SemanticTone tone, String title, String message) {
        setTone(tone);
        setTitle(title);
        setMessage(message);
        show();
    }

    private void rebuildActions(Button actionBtn) {
        actionSlot.getChildren().clear();
        if (actionBtn != null) {
            actionSlot.getChildren().add(actionBtn);
        }
        if (dismissible) {
            Button closeBtn = UiButtons.createIconButton(
                UiIcons.create(UiIcons.IconType.CLOSE, UiIcons.Size.SM),
                "Dismiss alert",
                UiButtons.Variant.GHOST
            );
            closeBtn.setOnAction(e -> dismiss());
            actionSlot.getChildren().add(closeBtn);
        }
        boolean hasActions = !actionSlot.getChildren().isEmpty();
        actionSlot.setManaged(hasActions);
        actionSlot.setVisible(hasActions);
    }
}
