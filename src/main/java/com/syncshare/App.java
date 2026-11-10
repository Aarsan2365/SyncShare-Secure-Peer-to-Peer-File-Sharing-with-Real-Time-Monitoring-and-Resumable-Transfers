package com.syncshare;

import com.syncshare.approval.ApprovalManager;
import com.syncshare.discovery.DiscoveryService;
import com.syncshare.discovery.PeerInfo;
import com.syncshare.network.PeerServer;
import com.syncshare.security.TlsSupport;
import com.syncshare.transfer.FileSender;

import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class App {

    private static final int PORT = 5050;

    public static void main(String[] args) {
        ApprovalManager approvals = new ApprovalManager();
        ExecutorService sendWorkers = Executors.newFixedThreadPool(3);

        try (
            Scanner scanner = new Scanner(System.in);
            PeerServer server = new PeerServer(PORT, approvals)
        ) {
            server.start();

            try (DiscoveryService discovery = startDiscovery()) {
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

                    switch (parts[0].toLowerCase(Locale.ROOT)) {
                        case "peers" -> printPeers(discovery);

                        case "send" -> {
                            if (parts.length < 3) {
                                System.out.println("Usage: send <IP> <FILE_PATH>");
                                break;
                            }

                            String ip = parts[1];
                            Path file = Path.of(parts[2]);

                            if (!Files.isRegularFile(file)) {
                                System.out.println("File not found: " + file);
                                break;
                            }

                            sendWorkers.submit(() -> {
                                try (Socket socket = TlsSupport.connect(ip, PORT)) {
                                    socket.setSoTimeout(180_000);
                                    System.out.println("\nSending: " + file);
                                    FileSender.send(socket, file);
                                } catch (Exception e) {
                                    System.err.println("Send failed: " + e.getMessage());
                                }
                            });

                            System.out.println("Outgoing transfer task submitted.");
                        }

                        case "pending" -> approvals.showPending();

                        case "accept", "reject" -> {
                            boolean accept = parts[0].equalsIgnoreCase("accept");

                            if (parts.length < 2) {
                                System.out.println(accept
                                        ? "Usage: accept <ID>"
                                        : "Usage: reject <ID>");
                                break;
                            }

                            try {
                                int id = Integer.parseInt(parts[1]);

                                if (approvals.decide(id, accept)) {
                                    System.out.println((accept
                                            ? "Accepted request #"
                                            : "Rejected request #") + id);
                                } else {
                                    System.out.println("Request not found or expired.");
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

                        default -> System.out.println("Unknown command. Type help.");
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Application error: " + e.getMessage());
        } finally {
            approvals.rejectAll();
            sendWorkers.shutdownNow();

            try {
                sendWorkers.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static DiscoveryService startDiscovery() {
        String computerName = System.getenv("COMPUTERNAME");
        if (computerName == null || computerName.isBlank()) {
            computerName = "SyncShare-PC";
        }

        try {
            return new DiscoveryService(computerName, PORT);
        } catch (IOException e) {
            System.err.println("Peer discovery could not start: " + e.getMessage());
            return null;
        }
    }

    private static void printPeers(DiscoveryService discovery) {
        if (discovery == null) {
            System.out.println("Discovery is unavailable. Use send with the receiver's IP.");
            return;
        }

        var peers = discovery.listPeers();

        if (peers.isEmpty()) {
            System.out.println("No peers discovered yet. Try again in a few seconds.");
            return;
        }

        for (PeerInfo peer : peers) {
            System.out.printf("%s  %s:%d%n",
                    peer.name(), peer.address(), peer.transferPort());
        }
    }

    private static void printHelp() {
        System.out.println("\nAvailable Commands:");
        System.out.println("peers                  Show nearby SyncShare computers");
        System.out.println("send <IP> <FILE_PATH>");
        System.out.println("pending");
        System.out.println("accept <ID>");
        System.out.println("reject <ID>");
        System.out.println("status");
        System.out.println("help");
        System.out.println("exit");
    }
}
