package com.syncshare.service;

import com.syncshare.model.ConnectionStatus;
import com.syncshare.ui.design.SemanticTone;
import javafx.concurrent.Service;
import javafx.concurrent.Task;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JavaFX {@link Service} bridging the Receive Files UI controller and {@link IncomingTransferGateway}.
 * Runs blocking directory verification, TCP/mTLS socket binding, and listener shutdown on a daemon
 * background thread so the JavaFX Application Thread never blocks.
 */
public class ReceiveListenerService extends Service<ReceiveListenerService.ActionResult> {

    public enum Operation {
        START,
        STOP
    }

    public record ActionResult(
        Operation operation,
        boolean success,
        boolean listening,
        String title,
        String message,
        SemanticTone tone,
        ReceiveDestinationValidator.ValidationResult destinationValidation
    ) {
    }

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger(1);

    private final IncomingTransferGateway gateway;
    private final ExecutorService executor;

    private volatile Operation pendingOperation = Operation.START;
    private volatile String pendingDestinationPath;

    public ReceiveListenerService(IncomingTransferGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.executor = new ThreadPoolExecutor(
            0,
            1,
            30L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            runnable -> {
                Thread thread = new Thread(
                    runnable,
                    "syncshare-receive-fx-" + THREAD_SEQ.getAndIncrement()
                );
                thread.setDaemon(true);
                return thread;
            }
        );
        setExecutor(this.executor);
    }

    public boolean requestStart(String destinationDirectoryPath) {
        if (isRunning()) {
            return false;
        }
        this.pendingOperation = Operation.START;
        this.pendingDestinationPath = destinationDirectoryPath;
        restart();
        return true;
    }

    public boolean requestStop() {
        if (isRunning()) {
            return false;
        }
        this.pendingOperation = Operation.STOP;
        this.pendingDestinationPath = null;
        restart();
        return true;
    }

    @Override
    protected Task<ActionResult> createTask() {
        final Operation op = pendingOperation;
        final String pathInput = pendingDestinationPath;

        return new Task<>() {
            @Override
            protected ActionResult call() {
                if (op == Operation.STOP) {
                    updateTitle("Stopping Receiver");
                    updateMessage("Releasing listener socket and active sessions...");
                    gateway.stopReceiving();
                    return new ActionResult(
                        Operation.STOP,
                        true,
                        gateway.isListening(),
                        "Receiver Stopped",
                        "Incoming peer connections are paused.",
                        SemanticTone.INFO,
                        null
                    );
                }

                updateTitle("Validating Destination");
                updateMessage("Checking destination directory existence and write permissions...");
                ReceiveDestinationValidator.ValidationResult dirCheck =
                    gateway.validateDestination(pathInput);
                if (!dirCheck.valid() || dirCheck.resolvedDirectory() == null) {
                    return new ActionResult(
                        Operation.START,
                        false,
                        gateway.isListening(),
                        "Invalid Destination Directory",
                        dirCheck.message(),
                        SemanticTone.ERROR,
                        dirCheck
                    );
                }

                if (gateway.isListening()) {
                    ConnectionStatus current = gateway.getStatus();
                    return new ActionResult(
                        Operation.START,
                        true,
                        true,
                        current != null ? current.headline() : "Receiver Listening",
                        current != null ? current.detail() : "Listener is already active.",
                        SemanticTone.INFO,
                        dirCheck
                    );
                }

                updateTitle("Starting Receiver");
                updateMessage("Binding Mutual TLS 1.3 peer listener...");
                boolean started = gateway.startReceiving(dirCheck.resolvedDirectory());
                ConnectionStatus status = gateway.getStatus();
                if (started) {
                    int activePort = gateway.getBoundPort() > 0
                        ? gateway.getBoundPort()
                        : (status != null ? status.listenPort() : 0);
                    return new ActionResult(
                        Operation.START,
                        true,
                        true,
                        status != null ? status.headline() : "Listening • Port " + activePort,
                        "Accepting Mutual TLS 1.3 offers on port " + activePort
                            + " -> " + dirCheck.resolvedDirectory(),
                        SemanticTone.SUCCESS,
                        dirCheck
                    );
                }

                SemanticTone tone = status != null ? status.state().tone() : SemanticTone.ERROR;
                String title = status != null ? status.headline() : "Listener Error";
                String detail = status != null && !status.detail().isBlank()
                    ? status.detail()
                    : "Unable to start receiver on configured port.";
                return new ActionResult(
                    Operation.START,
                    false,
                    gateway.isListening(),
                    title,
                    detail,
                    tone,
                    dirCheck
                );
            }
        };
    }

    public void dispose() {
        executor.shutdownNow();
    }
}
