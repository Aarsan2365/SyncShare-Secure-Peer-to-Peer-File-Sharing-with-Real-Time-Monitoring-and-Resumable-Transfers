package com.syncshare.reliability;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable diagnostic representation of an application failure, decoupling
 * technical exception context from user-facing presentation.
 */
public record AppError(
    String errorId,
    AppErrorCategory category,
    String userHeadline,
    String userMessage,
    String technicalDetails,
    Throwable cause,
    Instant timestamp,
    String context,
    boolean recoverable
) {
    public AppError {
        Objects.requireNonNull(errorId, "errorId");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(userHeadline, "userHeadline");
        Objects.requireNonNull(userMessage, "userMessage");
        timestamp = timestamp != null ? timestamp : Instant.now();
        technicalDetails = technicalDetails != null ? technicalDetails : "";
        context = context != null ? context : "";
    }

    public static AppError of(
        AppErrorCategory category,
        String userHeadline,
        String userMessage,
        Throwable cause,
        String context
    ) {
        String technical = (cause != null)
            ? (cause.getMessage() != null && !cause.getMessage().isBlank()
                ? cause.getClass().getName() + ": " + cause.getMessage()
                : cause.getClass().getName())
            : "";
        return new AppError(
            UUID.randomUUID().toString(),
            category,
            userHeadline,
            userMessage,
            technical,
            cause,
            Instant.now(),
            context != null ? context : "",
            category.isRecoverable()
        );
    }
}
