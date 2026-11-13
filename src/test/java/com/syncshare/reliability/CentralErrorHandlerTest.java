package com.syncshare.reliability;

import com.syncshare.ui.JavaFxToolkitExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(JavaFxToolkitExtension.class)
class CentralErrorHandlerTest {

    private CentralErrorHandler errorHandler;

    @BeforeEach
    void setUp() {
        errorHandler = new CentralErrorHandler(500L);
    }

    @Test
    void classifiesConnectionFailuresCorrectly() {
        AppError connectErr = errorHandler.classify(new ConnectException("Connection refused: connect"), "192.168.1.10:5050");
        assertEquals(AppErrorCategory.CONNECTION_FAILED, connectErr.category());
        assertTrue(connectErr.userMessage().contains("192.168.1.10:5050"));
        assertTrue(connectErr.recoverable());
        assertNotNull(connectErr.cause());

        AppError sslErr = errorHandler.classify(new SSLHandshakeException("Received fatal alert: bad_certificate"), "peer-cert");
        assertEquals(AppErrorCategory.CONNECTION_FAILED, sslErr.category());
        assertTrue(sslErr.userMessage().toLowerCase().contains("tls"));

        AppError hostErr = errorHandler.classify(new UnknownHostException("unknown-peer.local"), "unknown-peer.local");
        assertEquals(AppErrorCategory.CONNECTION_FAILED, hostErr.category());
        assertTrue(hostErr.userMessage().contains("unknown-peer.local"));
    }

    @Test
    void classifiesFileSystemAndStorageErrorsCorrectly() {
        AppError fileErr = errorHandler.classify(new FileNotFoundException("document.pdf"), "C:/data/document.pdf");
        assertEquals(AppErrorCategory.FILE_NOT_FOUND, fileErr.category());

        AppError accessErr = errorHandler.classify(new AccessDeniedException("C:/restricted"), "C:/restricted");
        assertEquals(AppErrorCategory.PERMISSION_DENIED, accessErr.category());

        AppError notDirErr = errorHandler.classify(new NotDirectoryException("C:/file.txt"), "C:/file.txt");
        assertEquals(AppErrorCategory.DESTINATION_UNAVAILABLE, notDirErr.category());

        AppError storageErr = errorHandler.classify(new IOException("There is not enough space on the disk"), "D:/incoming");
        assertEquals(AppErrorCategory.INSUFFICIENT_STORAGE, storageErr.category());
    }

    @Test
    void classifiesTransferInterruptedAndConfigurationFailures() {
        AppError timeoutErr = errorHandler.classify(new SocketTimeoutException("Read timed out"), "tx-123");
        assertEquals(AppErrorCategory.TRANSFER_INTERRUPTED, timeoutErr.category());

        AppError eofErr = errorHandler.classify(new EOFException("Connection reset"), "tx-456");
        assertEquals(AppErrorCategory.TRANSFER_INTERRUPTED, eofErr.category());

        AppError bindErr = errorHandler.classify(new BindException("Address already in use: JVM_Bind"), "5050");
        assertEquals(AppErrorCategory.INVALID_CONFIGURATION, bindErr.category());

        AppError argErr = errorHandler.classify(new IllegalArgumentException("Invalid port: 99999"), "port");
        assertEquals(AppErrorCategory.INVALID_CONFIGURATION, argErr.category());
    }

    @Test
    void classifiesUnexpectedAndStartupErrors() {
        AppError startupErr = errorHandler.classify(new IllegalStateException("Required UI resource not found: /main-view.fxml"));
        assertEquals(AppErrorCategory.STARTUP_FAILED, startupErr.category());
        assertFalse(startupErr.recoverable());

        AppError unexpected = errorHandler.classify(new NullPointerException("NPE test"));
        assertEquals(AppErrorCategory.UNEXPECTED_ERROR, unexpected.category());
        assertNotNull(unexpected.errorId());
    }

    @Test
    void appExceptionCarriesCustomCategoryAndContext() {
        AppException ex = new AppException(
            AppErrorCategory.DESTINATION_UNAVAILABLE,
            "Folder Inaccessible",
            "Drive was unmounted during transfer.",
            new IOException("Drive disconnected"),
            "E:/incoming"
        );
        AppError classified = errorHandler.classify(ex);
        assertEquals(AppErrorCategory.DESTINATION_UNAVAILABLE, classified.category());
        assertEquals("Folder Inaccessible", classified.userHeadline());
        assertEquals("Drive was unmounted during transfer.", classified.userMessage());
        assertEquals("E:/incoming", classified.context());
    }

    @Test
    void deduplicatesRepeatedFailuresWithinWindow() {
        Exception connectErr = new ConnectException("Connection refused: connect");
        AppError first = errorHandler.handleError(connectErr, "10.0.0.1:5050");
        assertNotNull(first, "First failure must be dispatched");

        AppError duplicate = errorHandler.handleError(connectErr, "10.0.0.1:5050");
        assertNull(duplicate, "Immediate duplicate failure must be suppressed");

        AppError differentContext = errorHandler.handleError(connectErr, "10.0.0.2:5050");
        assertNotNull(differentContext, "Failure with different endpoint context must be dispatched");
    }

    @Test
    void notifiesListenersSafelyFromBackgroundThread() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<AppError> received = new AtomicReference<>();

        errorHandler.addListener(error -> {
            received.set(error);
            latch.countDown();
        });

        Thread bg = new Thread(() -> {
            errorHandler.handleError(new AccessDeniedException("C:/secret"), "C:/secret");
        });
        bg.start();
        bg.join();

        assertTrue(latch.await(3, TimeUnit.SECONDS), "Listener should be notified within timeout");
        assertNotNull(received.get());
        assertEquals(AppErrorCategory.PERMISSION_DENIED, received.get().category());
    }

    @Test
    void listenerLifecycleAndShutdown() throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            List<AppError> list = new ArrayList<>();
            AppErrorListener listener = list::add;

            assertTrue(errorHandler.addListener(listener));
            errorHandler.handleError(new IOException("disk full"));
            assertEquals(1, list.size());

            assertTrue(errorHandler.removeListener(listener));
            errorHandler.handleError(new FileNotFoundException("gone.txt"));
            assertEquals(1, list.size(), "Removed listener should not receive subsequent errors");

            errorHandler.shutdown();
            assertFalse(errorHandler.addListener(listener), "Cannot add listener after shutdown");
        });
    }
}
