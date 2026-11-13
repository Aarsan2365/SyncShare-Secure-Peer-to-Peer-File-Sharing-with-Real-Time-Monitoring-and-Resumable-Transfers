package com.syncshare.reliability;

import java.util.Objects;

/**
 * Unchecked reliability exception encapsulating categorized error context.
 */
public class AppException extends RuntimeException {

    private final AppErrorCategory category;
    private final String userHeadline;
    private final String userMessage;
    private final String context;

    public AppException(AppErrorCategory category, String userHeadline, String userMessage, Throwable cause, String context) {
        super(userMessage != null && !userMessage.isBlank() ? userMessage : (cause != null ? cause.getMessage() : category.displayName()), cause);
        this.category = Objects.requireNonNull(category, "category");
        this.userHeadline = userHeadline != null ? userHeadline : category.displayName();
        this.userMessage = userMessage != null ? userMessage : "";
        this.context = context != null ? context : "";
    }

    public AppException(AppErrorCategory category, String userMessage) {
        this(category, category.displayName(), userMessage, null, "");
    }

    public AppException(AppErrorCategory category, String userMessage, Throwable cause) {
        this(category, category.displayName(), userMessage, cause, "");
    }

    public AppErrorCategory category() {
        return category;
    }

    public String userHeadline() {
        return userHeadline;
    }

    public String userMessage() {
        return userMessage;
    }

    public String context() {
        return context;
    }

    public AppError toAppError() {
        return AppError.of(category, userHeadline, userMessage, getCause(), context);
    }
}
