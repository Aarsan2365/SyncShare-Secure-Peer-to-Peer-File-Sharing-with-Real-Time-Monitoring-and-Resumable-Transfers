package com.syncshare.ui.design;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;

import java.util.List;
import java.util.Objects;

/**
 * Factory and styling utilities for SyncShare reusable buttons.
 */
public final class UiButtons {

    public enum Variant {
        PRIMARY("btn-primary"),
        SECONDARY("btn-secondary"),
        OUTLINE_TEAL("btn-outline-teal"),
        GHOST("btn-ghost"),
        NAVY("btn-navy"),
        SUCCESS("btn-success"),
        WARNING("btn-warning"),
        DANGER("btn-danger"),
        DANGER_OUTLINE("btn-danger-outline");

        private final String styleClass;

        Variant(String styleClass) {
            this.styleClass = styleClass;
        }

        public String styleClass() {
            return styleClass;
        }
    }

    public enum Size {
        SM("btn-sm"),
        MD(null),
        LG("btn-lg");

        private final String styleClass;

        Size(String styleClass) {
            this.styleClass = styleClass;
        }

        public String styleClass() {
            return styleClass;
        }
    }

    private static final List<String> ALL_VARIANTS = List.of(
        "btn-primary", "btn-secondary", "btn-outline-teal", "btn-ghost",
        "btn-navy", "btn-success", "btn-warning", "btn-danger", "btn-danger-outline"
    );

    private static final List<String> ALL_SIZES = List.of("btn-sm", "btn-lg");

    private UiButtons() {
    }

    public static Button create(String text, Variant variant) {
        return create(text, null, variant, Size.MD);
    }

    public static Button create(String text, Variant variant, Size size) {
        return create(text, null, variant, size);
    }

    public static Button create(String text, Node icon, Variant variant) {
        return create(text, icon, variant, Size.MD);
    }

    public static Button create(String text, Node icon, Variant variant, Size size) {
        Button button = new Button(text);
        if (icon != null) {
            button.setGraphic(icon);
        }
        return apply(button, variant, size);
    }

    public static Button createIconButton(Node icon, String accessibleText, Variant variant) {
        Objects.requireNonNull(icon, "icon");
        Button button = new Button();
        button.setGraphic(icon);
        button.setAccessibleText(accessibleText);
        apply(button, variant, Size.MD);
        DesignSystem.ensureStyleClass(button, "btn-icon");
        return button;
    }

    public static <T extends ButtonBase> T apply(T button, Variant variant) {
        return apply(button, variant, Size.MD);
    }

    public static <T extends ButtonBase> T apply(T button, Variant variant, Size size) {
        Objects.requireNonNull(button, "button");
        Objects.requireNonNull(variant, "variant");
        Objects.requireNonNull(size, "size");

        DesignSystem.ensureStyleClass(button, "btn");
        DesignSystem.swapExclusiveClass(button, ALL_VARIANTS, variant.styleClass());
        DesignSystem.swapExclusiveClass(button, ALL_SIZES, size.styleClass());
        button.setMnemonicParsing(false);
        button.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        return button;
    }
}
