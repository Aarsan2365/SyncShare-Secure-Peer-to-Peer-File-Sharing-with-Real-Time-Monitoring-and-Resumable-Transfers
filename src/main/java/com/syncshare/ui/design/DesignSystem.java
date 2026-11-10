package com.syncshare.ui.design;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import java.net.URL;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Central registry for SyncShare design system stylesheets, spacing tokens,
 * theme application, and keyboard accessibility helpers.
 */
public final class DesignSystem {

    public static final String MASTER_STYLESHEET = "/com/syncshare/ui/styles.css";
    public static final String TOKENS_STYLESHEET = "/com/syncshare/ui/styles/tokens.css";
    public static final String TYPOGRAPHY_STYLESHEET = "/com/syncshare/ui/styles/typography.css";
    public static final String BUTTONS_STYLESHEET = "/com/syncshare/ui/styles/buttons.css";
    public static final String CARDS_STYLESHEET = "/com/syncshare/ui/styles/cards.css";
    public static final String FORMS_STYLESHEET = "/com/syncshare/ui/styles/forms.css";
    public static final String BADGES_STYLESHEET = "/com/syncshare/ui/styles/badges.css";
    public static final String PROGRESS_STYLESHEET = "/com/syncshare/ui/styles/progress.css";
    public static final String NAVIGATION_STYLESHEET = "/com/syncshare/ui/styles/navigation.css";
    public static final String TABLE_STYLESHEET = "/com/syncshare/ui/styles/table.css";
    public static final String DIALOGS_STYLESHEET = "/com/syncshare/ui/styles/dialogs.css";
    public static final String FEEDBACK_STYLESHEET = "/com/syncshare/ui/styles/feedback.css";

    public static final List<String> ALL_STYLESHEETS = List.of(
        MASTER_STYLESHEET,
        TOKENS_STYLESHEET,
        TYPOGRAPHY_STYLESHEET,
        BUTTONS_STYLESHEET,
        CARDS_STYLESHEET,
        FORMS_STYLESHEET,
        BADGES_STYLESHEET,
        PROGRESS_STYLESHEET,
        NAVIGATION_STYLESHEET,
        TABLE_STYLESHEET,
        DIALOGS_STYLESHEET,
        FEEDBACK_STYLESHEET
    );

    // Spacing tokens (4px base grid)
    public static final double SPACE_XXS = 4;
    public static final double SPACE_XS = 8;
    public static final double SPACE_SM = 12;
    public static final double SPACE_MD = 16;
    public static final double SPACE_LG = 24;
    public static final double SPACE_XL = 32;

    // Corner radius tokens
    public static final double RADIUS_SM = 6;
    public static final double RADIUS_MD = 10;
    public static final double RADIUS_LG = 12;

    // Layout dimensions
    public static final double SIDEBAR_WIDTH = 228;

    private DesignSystem() {
    }

    public static URL requireStylesheet(String resourcePath) {
        Objects.requireNonNull(resourcePath, "resourcePath");
        URL url = DesignSystem.class.getResource(resourcePath);
        if (url == null) {
            throw new IllegalStateException("Required CSS resource not found: " + resourcePath);
        }
        return url;
    }

    public static void verifyStylesheetsAvailable() {
        for (String stylesheet : ALL_STYLESHEETS) {
            requireStylesheet(stylesheet);
        }
    }

    public static String masterStylesheetUrl() {
        return requireStylesheet(MASTER_STYLESHEET).toExternalForm();
    }

    public static void applyTheme(Scene scene) {
        Objects.requireNonNull(scene, "scene");
        String url = masterStylesheetUrl();
        if (!scene.getStylesheets().contains(url)) {
            scene.getStylesheets().add(url);
        }
    }

    public static void applyTheme(DialogPane dialogPane) {
        Objects.requireNonNull(dialogPane, "dialogPane");
        String url = masterStylesheetUrl();
        if (!dialogPane.getStylesheets().contains(url)) {
            dialogPane.getStylesheets().add(url);
        }
        ensureStyleClass(dialogPane, "ss-dialog");
    }

    public static void applyTheme(Dialog<?> dialog) {
        Objects.requireNonNull(dialog, "dialog");
        applyTheme(dialog.getDialogPane());
    }

    public static void ensureStyleClass(Node node, String styleClass) {
        Objects.requireNonNull(node, "node");
        if (styleClass != null && !styleClass.isBlank() && !node.getStyleClass().contains(styleClass)) {
            node.getStyleClass().add(styleClass);
        }
    }

    public static void setStyleClassPresent(Node node, String styleClass, boolean present) {
        Objects.requireNonNull(node, "node");
        if (styleClass == null || styleClass.isBlank()) {
            return;
        }
        if (present) {
            ensureStyleClass(node, styleClass);
        } else {
            node.getStyleClass().remove(styleClass);
        }
    }

    public static void swapExclusiveClass(Node node, Collection<String> exclusiveGroup, String activeClass) {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(exclusiveGroup, "exclusiveGroup");
        node.getStyleClass().removeAll(exclusiveGroup);
        if (activeClass != null && !activeClass.isBlank()) {
            ensureStyleClass(node, activeClass);
        }
    }

    public static void makeKeyboardActivatable(Node node, Runnable action) {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(action, "action");
        node.setFocusTraversable(true);
        node.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.SPACE) {
                action.run();
                event.consume();
            }
        });
    }
}
