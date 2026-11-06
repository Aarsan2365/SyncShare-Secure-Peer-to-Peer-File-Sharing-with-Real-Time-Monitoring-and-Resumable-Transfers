package com.syncshare;

import com.syncshare.network.TcpServer;
import com.syncshare.network.TcpClient;

import java.nio.file.Path;
import java.util.Scanner;

public class App {
   
    public static void main(String[] args) {

        Scanner scanner = new Scanner(System.in);

        try {
            System.out.println("===== SyncShare =====");
            System.out.println("1. Receive File");
            System.out.println("2. Send File");
            System.out.print("Choose option: ");

            String choice = scanner.nextLine();

            if (choice.equals("1")) {

                TcpServer.start(5050);

            } else if (choice.equals("2")) {

                System.out.print("Receiver IP: ");
                String ip = scanner.nextLine();

                System.out.print("File Path: ");
                String filePath = scanner.nextLine();

                TcpClient.send(
                    ip,
                    5050,
                    Path.of(filePath)
                );

            } else {
                System.out.println("Invalid option!");
            }

        } catch (Exception e) {
            System.out.println(
                "Error: " + e.getMessage()
            );
        }
    }
}
