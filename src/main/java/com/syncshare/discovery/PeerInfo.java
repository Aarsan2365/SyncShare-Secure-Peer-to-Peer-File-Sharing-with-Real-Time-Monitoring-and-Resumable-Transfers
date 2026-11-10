package com.syncshare.discovery;

public record PeerInfo(
        String name,
        String address,
        int transferPort,
        long lastSeenMillis
) {
}