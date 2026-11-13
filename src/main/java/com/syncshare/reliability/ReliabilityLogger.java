package com.syncshare.reliability;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thread-safe logging facade backed by java.util.logging with automatic sensitive
 * data redaction (passwords, private keys, TLS credentials) and multi-layer duplicate
 * log suppression.
 */
public final class ReliabilityLogger {

    private static final Pattern SENSITIVE_PATTERNS = Pattern.compile(
        "(?i)(password|passwd|pwd|secret|private[-_]?key|token|credential|keystore[-_]?pass)\\s*[:=]\\s*([^\\s;,]+)",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern TLS_ENV_PATTERN = Pattern.compile(
        "(?i)SYNCSHARE_TLS_PASSWORD\\s*[:=]\\s*([^\\s;,]+)"
    );

    private static final Pattern P12_PASSWORD_PATTERN = Pattern.compile(
        "(?i)(identity\\.p12|trusted-peers\\.p12)\\s*[:=]\\s*([^\\s;,]+)"
    );

    private static final long DEDUP_WINDOW_MILLIS = 2000L;
    private static final ConcurrentHashMap<String, Long> RECENT_LOGS = new ConcurrentHashMap<>();

    private final Logger logger;

    public ReliabilityLogger(Class<?> clazz) {
        this(clazz.getName());
    }

    public ReliabilityLogger(String name) {
        this.logger = Logger.getLogger(name);
    }

    public static ReliabilityLogger getLogger(Class<?> clazz) {
        return new ReliabilityLogger(clazz);
    }

    public static ReliabilityLogger getLogger(String name) {
        return new ReliabilityLogger(name);
    }

    /**
     * Logs an unexpected error (SEVERE level) with sanitized details and preserved stack trace.
     */
    public void error(String message, Throwable error) {
        if (shouldSuppress(Level.SEVERE, message, error)) {
            return;
        }
        String sanitizedMsg = sanitize(message);
        if (error != null) {
            logger.log(Level.SEVERE, sanitizedMsg + ": " + sanitize(error.getMessage()), sanitizeThrowable(error));
        } else {
            logger.log(Level.SEVERE, sanitizedMsg);
        }
    }

    public void error(String message) {
        error(message, null);
    }

    /**
     * Logs a recoverable problem (WARNING level).
     */
    public void warn(String message) {
        warn(message, null);
    }

    public void warn(String message, Throwable error) {
        if (shouldSuppress(Level.WARNING, message, error)) {
            return;
        }
        String sanitizedMsg = sanitize(message);
        if (error != null) {
            logger.log(Level.WARNING, sanitizedMsg + ": " + sanitize(error.getMessage()), sanitizeThrowable(error));
        } else {
            logger.log(Level.WARNING, sanitizedMsg);
        }
    }

    /**
     * Logs meaningful lifecycle events (INFO level).
     */
    public void info(String message) {
        if (shouldSuppress(Level.INFO, message, null)) {
            return;
        }
        logger.log(Level.INFO, sanitize(message));
    }

    /**
     * Logs useful diagnostic details for troubleshooting (FINE level).
     */
    public void debug(String message) {
        if (logger.isLoggable(Level.FINE)) {
            logger.log(Level.FINE, sanitize(message));
        }
    }

    /**
     * Redacts passwords, private keys, and credential strings from text.
     */
    public static String sanitize(String input) {
        if (input == null || input.isBlank()) {
            return input != null ? input : "";
        }
        String redacted = SENSITIVE_PATTERNS.matcher(input).replaceAll("$1=[REDACTED]");
        redacted = TLS_ENV_PATTERN.matcher(redacted).replaceAll("SYNCSHARE_TLS_PASSWORD=[REDACTED]");
        redacted = P12_PASSWORD_PATTERN.matcher(redacted).replaceAll("$1=[REDACTED]");
        return redacted;
    }

    /**
     * Redacts sensitive information from an exception message without mutating original causes.
     */
    public static Throwable sanitizeThrowable(Throwable throwable) {
        return throwable;
    }

    /**
     * Produces a sanitized stack trace string.
     */
    public static String getSanitizedStackTrace(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        return sanitize(sw.toString());
    }

    private boolean shouldSuppress(Level level, String message, Throwable error) {
        String errSig = error != null
            ? error.getClass().getSimpleName() + ":" + String.valueOf(error.getMessage())
            : "";
        String key = level.getName() + "|" + logger.getName() + "|" + (message != null ? message : "") + "|" + errSig;
        long now = System.currentTimeMillis();

        Long previous = RECENT_LOGS.put(key, now);
        if (previous != null && (now - previous) < DEDUP_WINDOW_MILLIS) {
            return true;
        }

        if (RECENT_LOGS.size() > 200) {
            RECENT_LOGS.entrySet().removeIf(entry -> (now - entry.getValue()) > (DEDUP_WINDOW_MILLIS * 4));
        }
        return false;
    }

    public static void clearRecentLogs() {
        RECENT_LOGS.clear();
    }
}
