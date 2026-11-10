package com.syncshare.network;

import com.syncshare.transfer.FileSender;
import java.net.Socket;
import java.nio.file.Path;

public class TcpClient {
     public static void send(
        String ip,
        int port,
        Path file
    ) throws Exception {

        try (Socket socket = new Socket(ip, port)) {

            System.out.println("Connected to: " + ip);

            FileSender.send(socket, file);
        }
    }
    
}
