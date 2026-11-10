package com.syncshare.ui.design;

import javafx.scene.Group;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;

import java.util.Objects;

/**
 * Consistent vector iconography built with native JavaFX SVGPath geometry
 * and styled entirely via external CSS tokens.
 */
public final class UiIcons {

    public enum Size {
        SM(14),
        MD(16),
        LG(20),
        XL(24);

        private final double px;

        Size(double px) {
            this.px = px;
        }

        public double px() {
            return px;
        }
    }

    public enum IconType {
        // 24x24 normalized geometric vector paths
        SHIELD_CHECK(
            "M12 2L4 5.5v6.09c0 5.05 3.41 9.76 8 10.91 4.59-1.15 8-5.86 8-10.91V5.5L12 2zm-1.1 13.4l-3.3-3.3 1.4-1.4 1.9 1.9 4.6-4.6 1.4 1.4-6 6z"
        ),
        SEND(
            "M2.01 21L23 12 2.01 3 2 10l15 2-15 2z"
        ),
        RECEIVE(
            "M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z"
        ),
        PEERS(
            "M16 11c1.66 0 2.99-1.34 2.99-3S17.66 5 16 5s-3 1.34-3 3 1.34 3 3 3zm-8 0c1.66 0 2.99-1.34 2.99-3S9.66 5 8 5 5 6.34 5 8s1.34 3 3 3zm0 2c-2.33 0-7 1.17-7 3.5V19h14v-2.5c0-2.33-4.67-3.5-7-3.5zm8 0c-.29 0-.62.02-.97.05 1.16.84 1.97 1.97 1.97 3.45V19h6v-2.5c0-2.33-4.67-3.5-7-3.5z"
        ),
        ACTIVITY(
            "M3 13h4l3-8 4 14 3-6h4v2h-2.8l-4.2 8.4L10 9.6 7.8 15H3v-2z"
        ),
        CHECK_CIRCLE(
            "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z"
        ),
        ALERT_TRIANGLE(
            "M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z"
        ),
        ALERT_CIRCLE(
            "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z"
        ),
        INFO_CIRCLE(
            "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z"
        ),
        FILE(
            "M14 2H6c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6zm2 16H8v-2h8v2zm0-4H8v-2h8v2zm-3-5V3.5L18.5 9H13z"
        ),
        LOCK(
            "M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2z"
        ),
        REFRESH(
            "M17.65 6.35A7.958 7.958 0 0012 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0112 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z"
        ),
        LAYERS(
            "M11.99 18.54l-7.37-5.73L3 14.07l9 7 9-7-1.63-1.27-7.38 5.74zM12 16l7.36-5.73L21 9l-9-7-9 7 1.63 1.27L12 16z"
        ),
        CLOSE(
            "M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"
        ),
        SEARCH(
            "M15.5 14h-.79l-.28-.27A6.471 6.471 0 0016 9.5 6.5 6.5 0 109.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z"
        ),
        CLOCK(
            "M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8zm.5-13H11v6l5.25 3.15.75-1.23-4.5-2.67z"
        );

        private final String svgPathData;

        IconType(String svgPathData) {
            this.svgPathData = svgPathData;
        }

        public String svgPathData() {
            return svgPathData;
        }
    }

    private UiIcons() {
    }

    public static StackPane create(IconType type) {
        return create(type, Size.MD, null);
    }

    public static StackPane create(IconType type, Size size) {
        return create(type, size, null);
    }

    public static StackPane create(IconType type, Size size, SemanticTone tone) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(size, "size");

        SVGPath svgPath = new SVGPath();
        svgPath.setContent(type.svgPathData());
        svgPath.getStyleClass().add("ui-icon-svg");

        double scale = size.px() / 24.0;
        svgPath.setScaleX(scale);
        svgPath.setScaleY(scale);

        Group wrapper = new Group(svgPath);
        StackPane container = new StackPane(wrapper);
        container.getStyleClass().add("ui-icon");
        if (tone != null) {
            container.getStyleClass().add(tone.iconToneClass());
        }
        container.setMinSize(size.px(), size.px());
        container.setPrefSize(size.px(), size.px());
        container.setMaxSize(size.px(), size.px());
        container.setMouseTransparent(true);
        return container;
    }

    public static StackPane createInverse(IconType type, Size size) {
        StackPane icon = create(type, size);
        icon.getStyleClass().add("ui-icon-inverse");
        return icon;
    }

    public static void setTone(StackPane iconContainer, SemanticTone tone) {
        Objects.requireNonNull(iconContainer, "iconContainer");
        DesignSystem.swapExclusiveClass(
            iconContainer,
            SemanticTone.allIconToneClasses(),
            tone != null ? tone.iconToneClass() : null
        );
    }

    public static IconType iconForTone(SemanticTone tone) {
        if (tone == null) {
            return IconType.INFO_CIRCLE;
        }
        return switch (tone) {
            case SUCCESS -> IconType.CHECK_CIRCLE;
            case WARNING -> IconType.ALERT_TRIANGLE;
            case ERROR -> IconType.ALERT_CIRCLE;
            case INFO, TEAL, NEUTRAL -> IconType.INFO_CIRCLE;
        };
    }
}
