package com.syncshare.ui.design;

import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Window;

import java.util.Objects;

/**
 * Factory for SyncShare-themed JavaFX Alerts and Dialogs with consistent
 * external CSS styling, semantic icons, and accessible button hierarchy.
 */
public final class UiDialogs {

    private UiDialogs() {
    }

    public static Alert info(Window owner, String title, String header, String message) {
        return createAlert(Alert.AlertType.INFORMATION, SemanticTone.INFO, owner, title, header, message);
    }

    public static Alert success(Window owner, String title, String header, String message) {
        return createAlert(Alert.AlertType.INFORMATION, SemanticTone.SUCCESS, owner, title, header, message);
    }

    public static Alert warning(Window owner, String title, String header, String message) {
        return createAlert(Alert.AlertType.WARNING, SemanticTone.WARNING, owner, title, header, message);
    }

    public static Alert error(Window owner, String title, String header, String message) {
        return createAlert(Alert.AlertType.ERROR, SemanticTone.ERROR, owner, title, header, message);
    }

    public static Alert confirm(Window owner, String title, String header, String message, String confirmLabel) {
        Alert alert = createAlert(Alert.AlertType.CONFIRMATION, SemanticTone.TEAL, owner, title, header, message);
        ButtonType accept = new ButtonType(
            confirmLabel != null && !confirmLabel.isBlank() ? confirmLabel : "Confirm",
            ButtonBar.ButtonData.OK_DONE
        );
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(cancel, accept);
        styleDialogButtons(alert.getDialogPane(), false);
        return alert;
    }

    public static Alert confirmDanger(Window owner, String title, String header, String message, String dangerLabel) {
        Alert alert = createAlert(Alert.AlertType.CONFIRMATION, SemanticTone.ERROR, owner, title, header, message);
        ButtonType proceed = new ButtonType(
            dangerLabel != null && !dangerLabel.isBlank() ? dangerLabel : "Reject",
            ButtonBar.ButtonData.OK_DONE
        );
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(cancel, proceed);
        styleDialogButtons(alert.getDialogPane(), true);
        return alert;
    }

    public static Alert createAlert(
        Alert.AlertType type,
        SemanticTone tone,
        Window owner,
        String title,
        String header,
        String message
    ) {
        Objects.requireNonNull(type, "type");
        SemanticTone resolvedTone = Objects.requireNonNullElse(tone, SemanticTone.INFO);

        Alert alert = new Alert(type);
        if (owner != null) {
            alert.initOwner(owner);
        }
        alert.setTitle(title != null ? title : "SyncShare");
        alert.setHeaderText(header);
        alert.setContentText(message);

        applyDialogStyling(alert.getDialogPane(), resolvedTone, resolvedTone == SemanticTone.ERROR);
        return alert;
    }

    public static Dialog<ButtonType> createCustomDialog(
        Window owner,
        String title,
        String header,
        SemanticTone tone,
        Node content,
        ButtonType... buttons
    ) {
        Dialog<ButtonType> dialog = new Dialog<>();
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setTitle(title != null ? title : "SyncShare");
        dialog.setHeaderText(header);

        DialogPane pane = dialog.getDialogPane();
        if (content != null) {
            pane.setContent(content);
        }
        if (buttons != null && buttons.length > 0) {
            pane.getButtonTypes().setAll(buttons);
        } else {
            pane.getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
        }

        SemanticTone resolvedTone = Objects.requireNonNullElse(tone, SemanticTone.TEAL);
        applyDialogStyling(pane, resolvedTone, resolvedTone == SemanticTone.ERROR);
        return dialog;
    }

    public static void applyDialogStyling(DialogPane pane, SemanticTone tone, boolean dangerPrimary) {
        Objects.requireNonNull(pane, "pane");
        DesignSystem.applyTheme(pane);

        String toneClass = tone != null ? tone.dialogStyleClass() : null;
        DesignSystem.swapExclusiveClass(pane, SemanticTone.allDialogToneClasses(), toneClass);

        StackPane graphic = UiIcons.create(UiIcons.iconForTone(tone), UiIcons.Size.XL, tone);
        pane.setGraphic(graphic);

        styleDialogButtons(pane, dangerPrimary);
    }

    public static void styleDialogButtons(DialogPane pane, boolean dangerPrimary) {
        for (ButtonType buttonType : pane.getButtonTypes()) {
            Node buttonNode = pane.lookupButton(buttonType);
            if (buttonNode instanceof Button button) {
                ButtonBar.ButtonData data = buttonType.getButtonData();
                if (data == ButtonBar.ButtonData.OK_DONE
                    || data == ButtonBar.ButtonData.YES
                    || data == ButtonBar.ButtonData.APPLY
                    || data == ButtonBar.ButtonData.FINISH) {
                    UiButtons.apply(
                        button,
                        dangerPrimary ? UiButtons.Variant.DANGER : UiButtons.Variant.PRIMARY
                    );
                } else {
                    UiButtons.apply(button, UiButtons.Variant.SECONDARY);
                }
            }
        }
    }
}
