
package com.syncshare;

import com.syncshare.approval.ApprovalManager;
import com.syncshare.network.PeerServer;
import com.syncshare.transfer.FileSender;

import com.syncshare.security.TlsSupport;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class App {

    private static final int PORT = 5050;

    public static void main(String[] args) {

        ApprovalManager approvals = new ApprovalManager();

        ExecutorService sendWorkers =
            Executors.newFixedThreadPool(3);

        try (
            Scanner scanner = new Scanner(System.in);
            PeerServer server = new PeerServer(PORT, approvals)
        ) {

            server.start();

            System.out.println("\n========== SyncShare ==========");
            System.out.println("Background receiver running.");
            printHelp();

            boolean running = true;

            while (running) {

                System.out.print("\nsyncshare> ");

                if (!scanner.hasNextLine()) {
                    break;
                }

                String command = scanner.nextLine().trim();

                if (command.isEmpty()) {
                    continue;
                }

                String[] parts = command.split("\\s+", 3);

                switch (parts[0].toLowerCase()) {

                    case "send" -> {

                        if (parts.length < 3) {
                            System.out.println(
                                "Usage: send <IP> <FILE_PATH>"
                            );
                            break;
                        }

                        String ip = parts[1];
                        Path file = Path.of(parts[2]);

                        if (!Files.isRegularFile(file)) {
                            System.out.println(
                                "File not found: " + file
                            );
                            break;
                        }

                        sendWorkers.submit(() -> {

                            try (Socket socket =
                                TlsSupport.connect(ip, PORT))  {

                                socket.setSoTimeout(180_000);

                                System.out.println(
                                    "\nSending: " + file
                                );

                                FileSender.send(socket, file);

                            } catch (Exception e) {

                                System.err.println(
                                    "Send failed: " + e.getMessage()
                                );
                            }
                        });

                        System.out.println(
                            "Outgoing transfer task submitted."
                        );
                    }

                    case "pending" -> approvals.showPending();

                    case "accept" -> {

                        if (parts.length < 2) {
                            System.out.println(
                                "Usage: accept <ID>"
                            );
                            break;
                        }

                        try {
                            int id = Integer.parseInt(parts[1]);

                            if (approvals.decide(id, true)) {
                                System.out.println(
                                    "Accepted request #" + id
                                );
                            } else {
                                System.out.println(
                                    "Request not found or expired."
                                );
                            }

                        } catch (NumberFormatException e) {
                            System.out.println("Invalid request ID.");
                        }
                    }

                    case "reject" -> {

                        if (parts.length < 2) {
                            System.out.println(
                                "Usage: reject <ID>"
                            );
                            break;
                        }

                        try {
                            int id = Integer.parseInt(parts[1]);

                            if (approvals.decide(id, false)) {
                                System.out.println(
                                    "Rejected request #" + id
                                );
                            } else {
                                System.out.println(
                                    "Request not found or expired."
                                );
                            }

                        } catch (NumberFormatException e) {
                            System.out.println("Invalid request ID.");
                        }
                    }

                    case "status" -> System.out.println(
                        server.isRunning()
                            ? "TCP Receiver: Running"
                            : "TCP Receiver: Stopped"
                    );

                    case "help" -> printHelp();

                    case "exit" -> {
                        running = false;
                        approvals.rejectAll();
                        System.out.println("Exiting SyncShare...");
                    }

                    default -> System.out.println(
                        "Unknown command. Type help."
                    );
                }
            }

        } catch (Exception e) {

            System.err.println(
                "Application error: " + e.getMessage()
            );

        } finally {

            approvals.rejectAll();
            sendWorkers.shutdownNow();

            try {
                sendWorkers.awaitTermination(
                    5,
                    TimeUnit.SECONDS
                );
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void printHelp() {

        System.out.println("\nAvailable Commands:");
        System.out.println("send <IP> <FILE_PATH>");
        System.out.println("pending");
        System.out.println("accept <ID>");
        System.out.println("reject <ID>");
        System.out.println("status");
        System.out.println("help");
        System.out.println("exit");
    }
}
