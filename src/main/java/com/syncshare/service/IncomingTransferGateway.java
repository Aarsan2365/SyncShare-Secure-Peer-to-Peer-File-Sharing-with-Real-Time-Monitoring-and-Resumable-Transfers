package com.syncshare.service;

import com.syncshare.model.ConnectionStatus;
import com.syncshare.model.IncomingRequestItem;
import com.syncshare.model.IncomingTransferSession;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.collections.ObservableList;

import java.nio.file.Path;

/**
 * Clean integration boundary between JavaFX Receive Files UI/services and the
 * underlying SyncShare peer receiver networking layer.
 */
public interface IncomingTransferGateway {

    ReceiveDestinationValidator.ValidationResult validateDestination(String directoryPath);

    ReceiveDestinationValidator.ValidationResult createAndValidateDestination(String directoryPath);

    boolean startReceiving(Path validatedDestination);

    void stopReceiving();

    boolean isListening();

    int getBoundPort();

    ConnectionStatus getStatus();

    ReadOnlyObjectProperty<ConnectionStatus> statusProperty();

    IncomingTransferSession getIncomingSession();

    ReadOnlyObjectProperty<IncomingTransferSession> incomingSessionProperty();

    ObservableList<IncomingRequestItem> getPendingRequests();

    boolean approveRequest(int requestId, boolean accepted);

    void clearSessionNotice();
}
