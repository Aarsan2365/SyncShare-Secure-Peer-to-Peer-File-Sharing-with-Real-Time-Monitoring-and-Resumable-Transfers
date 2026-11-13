package com.syncshare.reliability;

import javafx.application.Platform;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.BindException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.PortUnreachableException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.security.cert.CertificateException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Centralized, thread-safe reliability error coordinator.
 * <p>
 * Responsible for classifying raw technical exceptions into domain error categories,
 * mapping them into clear, user-friendly diagnostic messages, deduplicating identical
 * recurring failures, and safely dispatching categorized {@link AppError} notifications
 * onto the JavaFX Application Thread without swallowing causes or blocking I/O workers.
 */
public class CentralErrorHandler {

    private static final ReliabilityLogger LOGGER = ReliabilityLogger.getLogger(CentralErrorHandler.class);
    private static final CentralErrorHandler DEFAULT_INSTANCE = new CentralErrorHandler();
    private static final long DEFAULT_DEDUP_WINDOW_MILLIS = 1500L;

    private final CopyOnWriteArrayList<AppErrorListener> listeners = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, Long> recentErrorTimestamps = new ConcurrentHashMap<>();
    private final AtomicReference<AppError> latestError = new AtomicReference<>();
    private final AtomicBoolean shutDown = new AtomicBoolean(false);
    private final long dedupWindowMillis;

    public CentralErrorHandler() {
        this(DEFAULT_DEDUP_WINDOW_MILLIS);
    }

    public CentralErrorHandler(long dedupWindowMillis) {
        this.dedupWindowMillis = Math.max(0L, dedupWindowMillis);
    }

    public static CentralErrorHandler getInstance() {
        return DEFAULT_INSTANCE;
    }

    /**
     * Registers a listener to receive categorized error events.
     * Prevents duplicate listener registrations.
     */
    public boolean addListener(AppErrorListener listener) {
        if (listener == null || shutDown.get()) {
            return false;
        }
        return listeners.addIfAbsent(listener);
    }

    /**
     * Removes an error listener.
     */
    public boolean removeListener(AppErrorListener listener) {
        if (listener == null) {
            return false;
        }
        return listeners.remove(listener);
    }

    /**
     * Clears all registered listeners.
     */
    public void clearListeners() {
        listeners.clear();
    }

    /**
     * Clears recent error history and timestamps for testing purposes.
     */
    public void clearRecentErrors() {
        recentErrorTimestamps.clear();
        latestError.set(null);
        shutDown.set(false);
    }

    /**
     * Returns the most recent error recorded by this handler.
     */
    public AppError getLatestError() {
        return latestError.get();
    }

    /**
     * Classifies a {@link Throwable} without dispatching it to listeners.
     */
    public AppError classify(Throwable error) {
        return classify(error, "");
    }

    /**
     * Classifies a {@link Throwable} with contextual information (e.g. file path, peer address).
     */
    public AppError classify(Throwable error, String context) {
        String safeContext = context != null ? context : "";
        if (error == null) {
            return AppError.of(
                AppErrorCategory.UNEXPECTED_ERROR,
                "Unexpected Error",
                "An unexpected application error occurred.",
                null,
                safeContext
            );
        }

        if (error instanceof AppException appEx) {
            return appEx.toAppError();
        }

        Throwable root = findRootCause(error);
        AppErrorCategory category = determineCategory(error, root);
        String userHeadline = category.displayName();
        String userMessage = ErrorMessageResolver.resolveUserMessage(category, error, root, safeContext);

        return AppError.of(category, userHeadline, userMessage, error, safeContext);
    }

    /**
     * Central entry point to process, classify, and dispatch an application failure.
     *
     * @return the classified {@link AppError}, or {@code null} if suppressed as a duplicate.
     */
    public AppError handleError(Throwable error) {
        return handleError(error, "");
    }

    /**
     * Processes an error with context, classifies it, deduplicates identical occurrences,
     * and notifies listeners.
     *
     * @return the classified {@link AppError}, or {@code null} if suppressed as a duplicate.
     */
    public AppError handleError(Throwable error, String context) {
        AppError classified = classify(error, context);
        return dispatchIfUnique(classified);
    }

    /**
     * Dispatches an existing {@link AppError} through deduplication and listener notification.
     */
    public AppError handleError(AppError error) {
        Objects.requireNonNull(error, "error");
        return dispatchIfUnique(error);
    }

    private AppError dispatchIfUnique(AppError error) {
        if (shutDown.get() || error == null) {
            return null;
        }

        if (isDuplicate(error)) {
            return null;
        }

        latestError.set(error);

        if (error.recoverable()) {
            LOGGER.warn(error.userHeadline() + ": " + error.userMessage(), error.cause());
        } else {
            LOGGER.error(error.userHeadline() + ": " + error.userMessage(), error.cause());
        }

        // Safe notification to listeners, ensuring JavaFX UI handlers run on FX Thread
        for (AppErrorListener listener : listeners) {
            runOnFxThreadSafely(() -> {
                try {
                    listener.onError(error);
                } catch (Throwable handlerError) {
                    System.err.println("Error in AppErrorListener: " + handlerError.getMessage());
                }
            });
        }

        return error;
    }

    private boolean isDuplicate(AppError error) {
        if (dedupWindowMillis <= 0L) {
            return false;
        }
        String signature = buildDedupSignature(error);
        long now = System.currentTimeMillis();

        Long previous = recentErrorTimestamps.put(signature, now);
        if (previous != null && (now - previous) < dedupWindowMillis) {
            return true;
        }

        // Clean stale entries occasionally
        if (recentErrorTimestamps.size() > 100) {
            recentErrorTimestamps.entrySet().removeIf(entry -> (now - entry.getValue()) > (dedupWindowMillis * 4));
        }

        return false;
    }

    private static String buildDedupSignature(AppError error) {
        String causeSig = error.cause() != null
            ? error.cause().getClass().getSimpleName() + ":" + String.valueOf(error.cause().getMessage())
            : "";
        return error.category().name() + "|" + error.context() + "|" + causeSig;
    }

    public static Throwable findRootCause(Throwable throwable) {
        Throwable current = throwable;
        int depth = 0;
        while (current.getCause() != null && current.getCause() != current && depth < 20) {
            current = current.getCause();
            depth++;
        }
        return current;
    }

    private static AppErrorCategory determineCategory(Throwable error, Throwable root) {
        // Direct category checks across both top-level and root cause
        if (isConfigurationFailure(error) || isConfigurationFailure(root)) {
            return AppErrorCategory.INVALID_CONFIGURATION;
        }
        if (isConnectionFailure(error) || isConnectionFailure(root)) {
            return AppErrorCategory.CONNECTION_FAILED;
        }
        if (isFileNotFound(error) || isFileNotFound(root)) {
            return AppErrorCategory.FILE_NOT_FOUND;
        }
        if (isPermissionDenied(error) || isPermissionDenied(root)) {
            return AppErrorCategory.PERMISSION_DENIED;
        }
        if (isInsufficientStorage(error) || isInsufficientStorage(root)) {
            return AppErrorCategory.INSUFFICIENT_STORAGE;
        }
        if (isDestinationUnavailable(error) || isDestinationUnavailable(root)) {
            return AppErrorCategory.DESTINATION_UNAVAILABLE;
        }
        if (isTransferInterrupted(error) || isTransferInterrupted(root)) {
            return AppErrorCategory.TRANSFER_INTERRUPTED;
        }
        if (isStartupFailure(error) || isStartupFailure(root)) {
            return AppErrorCategory.STARTUP_FAILED;
        }
        return AppErrorCategory.UNEXPECTED_ERROR;
    }

    private static boolean isConnectionFailure(Throwable t) {
        return t instanceof ConnectException
            || t instanceof UnknownHostException
            || t instanceof NoRouteToHostException
            || t instanceof PortUnreachableException
            || t instanceof SSLHandshakeException
            || t instanceof SSLException
            || t instanceof CertificateException;
    }

    private static boolean isFileNotFound(Throwable t) {
        return t instanceof FileNotFoundException
            || t instanceof NoSuchFileException;
    }

    private static boolean isPermissionDenied(Throwable t) {
        if (t instanceof AccessDeniedException || t instanceof SecurityException) {
            return true;
        }
        String msg = t.getMessage();
        return msg != null && (msg.contains("Access is denied") || msg.contains("Permission denied"));
    }

    private static boolean isInsufficientStorage(Throwable t) {
        String msg = t.getMessage();
        if (msg == null) {
            return false;
        }
        String lower = msg.toLowerCase();
        return lower.contains("no space left")
            || lower.contains("not enough space")
            || lower.contains("insufficient space")
            || lower.contains("disk full")
            || lower.contains("insufficient storage");
    }

    private static boolean isDestinationUnavailable(Throwable t) {
        return t instanceof NotDirectoryException;
    }

    private static boolean isTransferInterrupted(Throwable t) {
        if (t instanceof BindException) {
            return false;
        }
        return t instanceof SocketTimeoutException
            || t instanceof InterruptedIOException
            || t instanceof EOFException
            || t instanceof SocketException;
    }

    private static boolean isConfigurationFailure(Throwable t) {
        return t instanceof BindException
            || t instanceof InvalidPathException
            || t instanceof IllegalArgumentException;
    }

    private static boolean isStartupFailure(Throwable t) {
        String msg = t.getMessage();
        return msg != null && (msg.contains("Required UI resource not found")
            || msg.contains("Unable to initialize the application window")
            || msg.contains("JavaFX launch"));
    }

    private static void runOnFxThreadSafely(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            try {
                Platform.runLater(action);
            } catch (IllegalStateException toolkitNotInitialized) {
                // If JavaFX toolkit is not active (e.g. pure unit tests), run on current thread
                action.run();
            }
        }
    }

    public void shutdown() {
        if (!shutDown.compareAndSet(false, true)) {
            return;
        }
        listeners.clear();
        recentErrorTimestamps.clear();
    }
}
