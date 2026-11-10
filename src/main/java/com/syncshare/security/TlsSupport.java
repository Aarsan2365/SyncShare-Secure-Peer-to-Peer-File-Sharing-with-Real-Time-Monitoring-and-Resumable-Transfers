package com.syncshare.security;

import javax.net.ssl.*;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HexFormat;

public final class TlsSupport {

    private static final int CONNECT_TIMEOUT = 10_000;
    private static final int HANDSHAKE_TIMEOUT = 10_000;

    private TlsSupport() {
    }

    private static SSLContext createContext() throws IOException {

        String passwordValue = System.getenv("SYNCSHARE_TLS_PASSWORD");

        if (passwordValue == null || passwordValue.isBlank()) {
            throw new IOException(
                "Set SYNCSHARE_TLS_PASSWORD before starting SyncShare"
            );
        }

        char[] password = passwordValue.toCharArray();

        Path keyPath = Path.of(
            System.getProperty(
                "syncshare.keyStore",
                "certs/identity.p12"
            )
        );

        Path trustPath = Path.of(
            System.getProperty(
                "syncshare.trustStore",
                "certs/trusted-peers.p12"
            )
        );

        try {
            KeyStore identity = KeyStore.getInstance("PKCS12");

            try (InputStream input = Files.newInputStream(keyPath)) {
                identity.load(input, password);
            }

            KeyStore trustedPeers = KeyStore.getInstance("PKCS12");

            try (InputStream input = Files.newInputStream(trustPath)) {
                trustedPeers.load(input, password);
            }

            KeyManagerFactory keys = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm()
            );

            keys.init(identity, password);

            TrustManagerFactory trust = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm()
            );

            trust.init(trustedPeers);

            SSLContext context = SSLContext.getInstance("TLS");

            context.init(
                keys.getKeyManagers(),
                trust.getTrustManagers(),
                null
            );

            return context;

        } catch (GeneralSecurityException e) {
            throw new IOException("Cannot configure TLS", e);

        } finally {
            Arrays.fill(password, '\0');
        }
    }

    public static SSLServerSocket listen(int port) throws IOException {

        SSLServerSocket server = (SSLServerSocket)
            createContext()
                .getServerSocketFactory()
                .createServerSocket(port);

        server.setEnabledProtocols(new String[] {"TLSv1.3"});
        server.setNeedClientAuth(true);

        return server;
    }

    public static SSLSocket connect(String host, int port)
            throws IOException {

        SSLSocket socket = (SSLSocket)
            createContext()
                .getSocketFactory()
                .createSocket();

        try {
            socket.setEnabledProtocols(new String[] {"TLSv1.3"});

            socket.connect(
                new InetSocketAddress(host, port),
                CONNECT_TIMEOUT
            );

            authenticate(socket);

            return socket;

        } catch (IOException | RuntimeException e) {
            try {
                socket.close();
            } catch (IOException closeError) {
                e.addSuppressed(closeError);
            }

            throw e;
        }
    }

    public static String authenticate(Socket connection)
            throws IOException {

        if (!(connection instanceof SSLSocket socket)) {
            throw new IOException("A TLS connection is required");
        }

        int previousTimeout = socket.getSoTimeout();

        try {
            socket.setSoTimeout(HANDSHAKE_TIMEOUT);
            socket.startHandshake();

            X509Certificate certificate = (X509Certificate)
                socket.getSession().getPeerCertificates()[0];

            certificate.checkValidity();

            byte[] fingerprint = MessageDigest
                .getInstance("SHA-256")
                .digest(certificate.getEncoded());

            return HexFormat.of().formatHex(fingerprint);

        } catch (GeneralSecurityException e) {
            throw new IOException("Peer certificate is invalid", e);

        } finally {
            socket.setSoTimeout(previousTimeout);
        }
    }
}