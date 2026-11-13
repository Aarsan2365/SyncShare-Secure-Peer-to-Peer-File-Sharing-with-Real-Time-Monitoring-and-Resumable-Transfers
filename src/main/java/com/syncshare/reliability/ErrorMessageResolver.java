package com.syncshare.reliability;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import java.io.EOFException;
import java.io.InterruptedIOException;
import java.net.BindException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.PortUnreachableException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;

/**
 * Translates low-level technical exceptions into clear, understandable user-facing
 * messages with diagnostic context while ensuring sensitive credentials remain redacted.
 */
public final class ErrorMessageResolver {

    private ErrorMessageResolver() {
    }

    public static String resolveUserMessage(AppErrorCategory category, Throwable error, String context) {
        return resolveUserMessage(category, error, CentralErrorHandler.findRootCause(error), context);
    }

    public static String resolveUserMessage(AppErrorCategory category, Throwable error, Throwable root, String context) {
        String safeContext = (context != null && !context.isBlank()) ? " (" + ReliabilityLogger.sanitize(context) + ")" : "";
        String rootMsg = (root != null && root.getMessage() != null && !root.getMessage().isBlank())
            ? ReliabilityLogger.sanitize(root.getMessage())
            : "";

        return switch (category) {
            case CONNECTION_FAILED -> resolveConnectionMessage(root, safeContext, rootMsg);
            case FILE_NOT_FOUND ->
                "File not found: The selected file could not be located" + safeContext + ". Verify that the file has not been moved, renamed, or deleted.";
            case DESTINATION_UNAVAILABLE ->
                "Destination unavailable: Selected receive directory does not exist or is not accessible" + safeContext + ".";
            case INSUFFICIENT_STORAGE ->
                "Insufficient storage: The destination drive does not have enough free disk space for this transfer" + safeContext + ".";
            case PERMISSION_DENIED ->
                "Permission denied: Insufficient system permissions to read or write file" + safeContext + ". Check file and folder permissions.";
            case TRANSFER_INTERRUPTED -> resolveInterruptedMessage(root, safeContext, rootMsg);
            case INVALID_CONFIGURATION -> resolveConfigurationMessage(root, safeContext, rootMsg);
            case STARTUP_FAILED ->
                "Application startup failed: Unable to initialize required application resources or window" + safeContext + ".";
            case UNEXPECTED_ERROR ->
                "An unexpected error occurred" + safeContext + (!rootMsg.isBlank() ? ": " + rootMsg : ".");
        };
    }

    private static String resolveConnectionMessage(Throwable root, String context, String rootMsg) {
        if (root instanceof SSLHandshakeException || root instanceof SSLException || root instanceof CertificateException) {
            return "Connection failed: Mutual TLS security handshake or certificate verification failed" + context
                + ". Verify that trusted certificates and TLS passwords match.";
        }
        if (root instanceof UnknownHostException) {
            return "Connection failed: Destination host name could not be resolved" + context
                + ". Check that the peer hostname or IP address is spelled correctly.";
        }
        if (root instanceof ConnectException || root instanceof NoRouteToHostException || root instanceof PortUnreachableException) {
            return "Connection failed: Unable to connect to destination peer" + context
                + ". Confirm that the remote peer receiver is running and the port is open.";
        }
        return "Connection failed: Could not establish a connection" + context
            + (!rootMsg.isBlank() ? " (" + rootMsg + ")" : "") + ".";
    }

    private static String resolveInterruptedMessage(Throwable root, String context, String rootMsg) {
        if (root instanceof SocketTimeoutException) {
            return "Transfer interrupted: Peer communication or approval timed out" + context + ".";
        }
        if (root instanceof EOFException) {
            return "Transfer interrupted: The connection was closed unexpectedly by the peer" + context + ".";
        }
        if (root instanceof InterruptedIOException) {
            return "Transfer interrupted: Operation was cancelled or interrupted" + context + ".";
        }
        if (root instanceof SocketException) {
            return "Transfer interrupted: Network socket was closed or disconnected during transfer" + context + ".";
        }
        return "Transfer interrupted: Connection to peer was lost during transfer" + context + ".";
    }

    private static String resolveConfigurationMessage(Throwable root, String context, String rootMsg) {
        if (root instanceof BindException) {
            return "Invalid configuration: Port is already in use by another service" + context
                + ". Stop conflicting services or choose a different port in Settings.";
        }
        return "Invalid configuration: " + (!rootMsg.isBlank() ? rootMsg : "Parameters are invalid" + context) + ".";
    }
}
