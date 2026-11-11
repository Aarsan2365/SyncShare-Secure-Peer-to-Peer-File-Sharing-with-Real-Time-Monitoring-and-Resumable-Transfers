package com.syncshare.network;

import com.syncshare.events.ConsoleTransferListener;
import com.syncshare.events.TransferListener;
import com.syncshare.transfer.FileSender;
import java.net.Socket;
import java.nio.file.Path;
import com.syncshare.security.TlsSupport;

public class TcpClient {
    public static void send(
        String ip,
        int port,
        Path file
    ) throws Exception {
        send(ip, port, file, new ConsoleTransferListener());
    }

    public static void send(
        String ip,
        int port,
        Path file,
        TransferListener listener
    ) throws Exception {

        try (Socket socket = TlsSupport.connect(ip, port)) {

            socket.setSoTimeout(180_000);

            FileSender.send(socket, file, listener);
        }
    }

}
