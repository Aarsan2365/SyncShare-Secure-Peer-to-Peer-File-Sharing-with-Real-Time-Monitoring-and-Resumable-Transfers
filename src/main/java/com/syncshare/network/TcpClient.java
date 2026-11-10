package com.syncshare.network;

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

        try (Socket socket = TlsSupport.connect(ip, port)) {

            socket.setSoTimeout(180_000);

            FileSender.send(socket, file);
        }
    }
    
}
