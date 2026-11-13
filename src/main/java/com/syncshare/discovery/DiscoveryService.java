package com.syncshare.discovery;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class DiscoveryService implements DeviceDiscoveryService {

    private static final int DISCOVERY_PORT = 5051;
    private static final int ANNOUNCE_SECONDS = 3;
    private static final long EXPIRY_MILLIS = 15_000;
    private static final String PROTOCOL = "SYNCSHARE-DISCOVERY-1";

    private final UUID instanceId = UUID.randomUUID();
    private final ConcurrentHashMap<UUID, PeerInfo> peers =
            new ConcurrentHashMap<>();

    private final DatagramSocket socket;
    private final ScheduledExecutorService announcer;
    private final byte[] announcement;

    public DiscoveryService(String computerName, int transferPort)
            throws IOException {
        if (transferPort < 1 || transferPort > 65535) {
            throw new IllegalArgumentException("Invalid transfer port");
        }

        String name = computerName.replaceAll("[\\p{Cntrl}|]", "_").strip();
        if (name.isEmpty()) {
            name = "SyncShare-PC";
        }
        if (name.length() > 64) {
            name = name.substring(0, 64);
        }

        String message = String.join("|", PROTOCOL, instanceId.toString(),
                name, Integer.toString(transferPort));
        announcement = message.getBytes(StandardCharsets.UTF_8);

        DatagramSocket s = new DatagramSocket(null);
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(DISCOVERY_PORT));
        socket = s;
        try {
            socket.setBroadcast(true);
        } catch (IOException e) {
            socket.close();
            throw e;
        }

        announcer = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "syncshare-discovery-announcer");
            thread.setDaemon(true);
            return thread;
        });

        Thread listener = new Thread(this::receiveLoop, "syncshare-discovery-listener");
        listener.setDaemon(true);
        listener.start();

        announcer.scheduleWithFixedDelay(
                this::announce, 0, ANNOUNCE_SECONDS, TimeUnit.SECONDS);
    }

    public List<PeerInfo> listPeers() {
        removeExpiredPeers();
        return peers.values().stream()
                .sorted(Comparator.comparing(PeerInfo::name)
                        .thenComparing(PeerInfo::address))
                .toList();
    }

    private void removeExpiredPeers() {
        long cutoff = System.currentTimeMillis() - EXPIRY_MILLIS;
        peers.entrySet().removeIf(entry ->
                entry.getValue().lastSeenMillis() < cutoff);
    }

    private void announce() {
        removeExpiredPeers();

        try {
            for (InetAddress address : broadcastAddresses()) {
                DatagramPacket packet = new DatagramPacket(
                        announcement, announcement.length, address, DISCOVERY_PORT);

                try {
                    socket.send(packet);
                } catch (IOException e) {
                    if (!socket.isClosed()) {
                        System.err.println("Discovery broadcast to "
                                + address.getHostAddress() + " failed: " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            if (!socket.isClosed()) {
                System.err.println("Could not read network interfaces: " + e.getMessage());
            }
        }
    }

    private static Set<InetAddress> broadcastAddresses() throws IOException {
        Set<InetAddress> addresses = new HashSet<>();
        Enumeration<NetworkInterface> interfaces =
                NetworkInterface.getNetworkInterfaces();

        while (interfaces != null && interfaces.hasMoreElements()) {
            NetworkInterface network = interfaces.nextElement();
            if (!network.isUp() || network.isLoopback()) {
                continue;
            }

            for (InterfaceAddress binding : network.getInterfaceAddresses()) {
                InetAddress broadcast = binding.getBroadcast();
                if (broadcast != null) {
                    addresses.add(broadcast);
                }
            }
        }

        if (addresses.isEmpty()) {
            addresses.add(InetAddress.getByName("255.255.255.255"));
        }
        return addresses;
    }

    private void receiveLoop() {
        byte[] buffer = new byte[512];

        while (!socket.isClosed()) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

            try {
                socket.receive(packet);
                if (packet.getLength() < buffer.length) {
                    recordPeer(packet);
                }
            } catch (IOException e) {
                if (!socket.isClosed()) {
                    System.err.println("Discovery listener stopped: " + e.getMessage());
                }
                return;
            }
        }
    }

    private void recordPeer(DatagramPacket packet) {
        String message = new String(packet.getData(), packet.getOffset(),
                packet.getLength(), StandardCharsets.UTF_8);
        String[] fields = message.split("\\|", -1);

        if (fields.length != 4 || !PROTOCOL.equals(fields[0])) {
            return;
        }

        String name = fields[2];
        if (name.isBlank() || name.length() > 64
                || name.chars().anyMatch(Character::isISOControl)) {
            return;
        }

        try {
            UUID peerId = UUID.fromString(fields[1]);
            int transferPort = Integer.parseInt(fields[3]);

            if (instanceId.equals(peerId) || transferPort < 1 || transferPort > 65535) {
                return;
            }

            String address = packet.getAddress().getHostAddress();
            peers.put(peerId, new PeerInfo(
                    name, address, transferPort, System.currentTimeMillis()));
        } catch (IllegalArgumentException ignored) {
            // Ignore announcements with an invalid peer ID or port.
        }
    }

    @Override
    public boolean isRunning() {
        return socket != null && !socket.isClosed();
    }

    @Override
    public void close() {
        announcer.shutdownNow();
        socket.close();
    }
}