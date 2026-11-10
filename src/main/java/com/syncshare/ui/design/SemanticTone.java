package com.syncshare.ui.design;

import java.util.List;

/**
 * Semantic tone variants shared across SyncShare badges, banners, cards,
 * progress bars, validation states, icons, and dialogs.
 */
public enum SemanticTone {
    NEUTRAL("neutral"),
    TEAL("teal"),
    INFO("info"),
    SUCCESS("success"),
    WARNING("warning"),
    ERROR("error");

    private final String token;

    SemanticTone(String token) {
        this.token = token;
    }

    public String token() {
        return token;
    }

    public String badgeStyleClass() {
        return "badge-" + token;
    }

    public String dotStyleClass() {
        return "status-dot-" + token;
    }

    public String alertStyleClass() {
        return switch (this) {
            case INFO, TEAL -> "alert-info";
            case SUCCESS -> "alert-success";
            case WARNING -> "alert-warning";
            case ERROR -> "alert-error";
            case NEUTRAL -> "alert-banner";
        };
    }

    public String progressStyleClass() {
        return switch (this) {
            case INFO -> "progress-info";
            case SUCCESS -> "progress-success";
            case WARNING -> "progress-warning";
            case ERROR -> "progress-error";
            case NEUTRAL -> "progress-navy";
            case TEAL -> "ss-progress";
        };
    }

    public String inputStyleClass() {
        return switch (this) {
            case SUCCESS -> "input-success";
            case WARNING -> "input-warning";
            case ERROR -> "input-error";
            case NEUTRAL, TEAL, INFO -> null;
        };
    }

    public String fieldFeedbackStyleClass() {
        return switch (this) {
            case INFO, TEAL -> "field-feedback-info";
            case SUCCESS -> "field-feedback-success";
            case WARNING -> "field-feedback-warning";
            case ERROR -> "field-feedback-error";
            case NEUTRAL -> "field-hint";
        };
    }

    public String cardStyleClass() {
        return switch (this) {
            case INFO, TEAL -> "card-info";
            case SUCCESS -> "card-success";
            case WARNING -> "card-warning";
            case ERROR -> "card-error";
            case NEUTRAL -> null;
        };
    }

    public String dialogStyleClass() {
        return switch (this) {
            case INFO -> "dialog-info";
            case TEAL -> "dialog-teal";
            case SUCCESS -> "dialog-success";
            case WARNING -> "dialog-warning";
            case ERROR -> "dialog-error";
            case NEUTRAL -> null;
        };
    }

    public String iconToneClass() {
        return switch (this) {
            case TEAL -> "ui-icon-teal";
            case INFO -> "ui-icon-info";
            case SUCCESS -> "ui-icon-success";
            case WARNING -> "ui-icon-warning";
            case ERROR -> "ui-icon-error";
            case NEUTRAL -> "ui-icon-muted";
        };
    }

    public static List<String> allBadgeClasses() {
        return List.of(
            "badge-neutral", "badge-teal", "badge-info",
            "badge-success", "badge-warning", "badge-error", "badge-dark"
        );
    }

    public static List<String> allDotClasses() {
        return List.of(
            "status-dot-neutral", "status-dot-teal", "status-dot-info",
            "status-dot-success", "status-dot-warning", "status-dot-error"
        );
    }

    public static List<String> allAlertClasses() {
        return List.of("alert-info", "alert-success", "alert-warning", "alert-error");
    }

    public static List<String> allProgressClasses() {
        return List.of(
            "progress-info", "progress-success", "progress-warning",
            "progress-error", "progress-navy"
        );
    }

    public static List<String> allInputClasses() {
        return List.of("input-success", "input-warning", "input-error");
    }

    public static List<String> allFieldFeedbackClasses() {
        return List.of(
            "field-hint", "field-feedback-info", "field-feedback-success",
            "field-feedback-warning", "field-feedback-error"
        );
    }

    public static List<String> allCardToneClasses() {
        return List.of("card-info", "card-success", "card-warning", "card-error");
    }

    public static List<String> allDialogToneClasses() {
        return List.of(
            "dialog-info", "dialog-teal", "dialog-success",
            "dialog-warning", "dialog-error"
        );
    }

    public static List<String> allIconToneClasses() {
        return List.of(
            "ui-icon-primary", "ui-icon-muted", "ui-icon-teal", "ui-icon-teal-bright",
            "ui-icon-success", "ui-icon-warning", "ui-icon-error", "ui-icon-info",
            "ui-icon-inverse", "ui-icon-inverse-muted"
        );
    }
}
