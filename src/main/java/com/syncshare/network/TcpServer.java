package com.syncshare.network;

import com.syncshare.transfer.FileReceiver;
import java.net.ServerSocket;
import java.net.Socket;


public class TcpServer {
    public static void start(int port) throws Exception {

        try (ServerSocket server = new ServerSocket(port)) {

            System.out.println("SyncShare Receiver Started!");
            System.out.println("Listening on port: " + port);

            while (true) {
                Socket client = server.accept();

                System.out.println(
                    "Connected: " + client.getInetAddress()
                );

                // Receive file from the connected sender
                try (client) {
                    FileReceiver.receive(client);
                } catch (Exception e) {
                    System.out.println(
                        "Transfer failed: " + e.getMessage()
                    );
                }
            }
        }
    }
    
}
