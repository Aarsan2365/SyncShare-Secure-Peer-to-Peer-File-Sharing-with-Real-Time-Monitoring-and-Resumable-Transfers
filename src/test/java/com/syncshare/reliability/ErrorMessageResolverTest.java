package com.syncshare.reliability;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;
import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.BindException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NotDirectoryException;

import static org.junit.jupiter.api.Assertions.*;

class ErrorMessageResolverTest {

    @Test
    void resolvesConnectionFailedMessages() {
        ConnectException connectEx = new ConnectException("Connection refused");
        String msg = ErrorMessageResolver.resolveUserMessage(AppErrorCategory.CONNECTION_FAILED, connectEx, connectEx, "10.0.0.5:5050");
        assertTrue(msg.startsWith("Connection failed:"));
        assertTrue(msg.contains("10.0.0.5:5050"));
        assertTrue(msg.contains("receiver is running"));

        SSLHandshakeException sslEx = new SSLHandshakeException("bad certificate");
        String sslMsg = ErrorMessageResolver.resolveUserMessage(AppErrorCategory.CONNECTION_FAILED, sslEx, sslEx, "node-b:5050");
        assertTrue(sslMsg.contains("Mutual TLS security handshake"));

        UnknownHostException hostEx = new UnknownHostException("nonexistent-node");
        String hostMsg = ErrorMessageResolver.resolveUserMessage(AppErrorCategory.CONNECTION_FAILED, hostEx, hostEx, "nonexistent-node:5050");
        assertTrue(hostMsg.contains("Destination host name could not be resolved"));
    }

    @Test
    void resolvesFileAndStorageMessages() {
        FileNotFoundException fnf = new FileNotFoundException("data.bin");
        String fnfMsg = ErrorMessageResolver.resolveUserMessage(AppErrorCategory.FILE_NOT_FOUND, fnf, fnf, "data.bin");
        assertTrue(fnfMsg.startsWith("File not found:"));
        assertTrue(fnfMsg.contains("moved, renamed, or deleted"));

        NotDirectoryException notDir = new NotDirectoryException("data.txt");
        String notDirMsg = ErrorMessageResolver.resolveUserMessage(AppErrorCategory.DESTINATION_UNAVAILABLE, notDir, notDir, "data.txt");
        assertTrue(notDirMsg.startsWith("Destination unavailable:"));

        IOException storageEx = new IOException("no space left on device");
        String storageMsg = ErrorMessageResolver.resolveUserMessage(AppErrorCategory.INSUFFICIENT_STORAGE, storageEx, storageEx, "D:/recv");
        assertTrue(storageMsg.startsWith("Insufficient storage:"));

        AccessDeniedException permEx = new AccessDeniedException("C:/windows/system32");
        String permMsg = ErrorMessageResolver.resolveUserMessage(AppErrorCategory.PERMISSION_DENIED, permEx, permEx, "C:/windows/system32");
        assertTrue(permMsg.startsWith("Permission denied:"));
    }

    @Test
    void resolvesInterruptedAndConfigurationMessages() {
        SocketTimeoutException timeout = new SocketTimeoutException("Read timed out");
        String timeoutMsg = ErrorMessageResolver.resolveUserMessage(AppErrorCategory.TRANSFER_INTERRUPTED, timeout, timeout, "tx-99");
        assertTrue(timeoutMsg.startsWith("Transfer interrupted:"));
        assertTrue(timeoutMsg.contains("timed out"));

        EOFException eof = new EOFException("closed");
        String eofMsg = ErrorMessageResolver.resolveUserMessage(AppErrorCategory.TRANSFER_INTERRUPTED, eof, eof, "tx-99");
        assertTrue(eofMsg.startsWith("Transfer interrupted:"));
        assertTrue(eofMsg.contains("closed unexpectedly"));

        BindException bind = new BindException("Address in use");
        String bindMsg = ErrorMessageResolver.resolveUserMessage(AppErrorCategory.INVALID_CONFIGURATION, bind, bind, "5050");
        assertTrue(bindMsg.startsWith("Invalid configuration:"));
        assertTrue(bindMsg.contains("Port is already in use"));
    }

    @Test
    void redactsSensitiveInformationFromContextAndMessages() {
        ConnectException ex = new ConnectException("Failed with token=secretToken123");
        String msg = ErrorMessageResolver.resolveUserMessage(AppErrorCategory.CONNECTION_FAILED, ex, ex, "peer password=SecretPassword");
        assertFalse(msg.contains("SecretPassword"));
        assertTrue(msg.contains("password=[REDACTED]"));
    }
}
