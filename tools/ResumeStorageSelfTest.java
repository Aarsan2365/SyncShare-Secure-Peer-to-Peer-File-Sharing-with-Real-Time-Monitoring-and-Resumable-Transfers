import com.syncshare.events.TransferListener;
import com.syncshare.resume.ResumeStore;
import com.syncshare.security.TlsSupport;
import com.syncshare.transfer.FileReceiver;
import com.syncshare.transfer.FileSender;
import com.syncshare.transfer.HashUtils;

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class ResumeStorageSelfTest {

    private static final int CHECKPOINT_BYTES = 65536;

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static class Listener implements TransferListener {
        long initialOffset = -1;

        public void onStarted(String name, long size) {}

        public void onProgress(String name, long bytes, long size) {
            if (initialOffset < 0) {
                initialOffset = bytes;
            }
        }

        public void onCompleted(String name) {}

        public void onError(String name, Exception error) {}
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("crash")) {
            Path root = Path.of(args[1]);
            Path source = root.resolve("source.bin");
            Path peerFolder = root.resolve("received").resolve(args[2]);

            ResumeStore store = new ResumeStore(
                    peerFolder, "source.bin", Files.size(source),
                    HashUtils.calculateSHA256(source));

            byte[] prefix = Arrays.copyOf(Files.readAllBytes(source), CHECKPOINT_BYTES);
            store.append(prefix, prefix.length);

            // Stop this child JVM abruptly to test checkpoint recovery.
            Runtime.getRuntime().halt(0);
        }

        if (args.length != 1) {
            throw new IllegalArgumentException("Run this test using tools/test-resume.ps1");
        }

        byte[] certificate = Files.readAllBytes(Path.of(args[0]));
        String peerId = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(certificate));

        Path root = Files.createTempDirectory("syncshare-resume-test-");
        String previousDirectory = System.getProperty("syncshare.receiveDir");

        try {
            byte[] original = new byte[200000];
            new Random(42).nextBytes(original);

            Path source = root.resolve("source.bin");
            Files.write(source, original);
            String hash = HashUtils.calculateSHA256(source);

            Path receiveRoot = root.resolve("received");
            Path peerFolder = receiveRoot.resolve(peerId);
            System.setProperty("syncshare.receiveDir", receiveRoot.toString());

            String javaCommand = Path.of(
                    System.getProperty("java.home"), "bin", "java").toString();

            Process child = new ProcessBuilder(
                    javaCommand, "-cp", System.getProperty("java.class.path"),
                    "ResumeStorageSelfTest", "crash", root.toString(), peerId)
                    .inheritIO()
                    .start();

            if (!child.waitFor(20, TimeUnit.SECONDS)) {
                child.destroyForcibly();
                child.waitFor();
                throw new AssertionError("Checkpoint setup timed out");
            }
            check(child.exitValue() == 0, "Checkpoint setup failed");

            try (ResumeStore store = new ResumeStore(
                    peerFolder, "source.bin", original.length, hash)) {
                check(store.offset() == CHECKPOINT_BYTES,
                        "Checkpoint did not survive JVM restart");
            }
            System.out.println("PASS: checkpoint survives JVM restart");

            Listener senderListener = new Listener();
            ExecutorService worker = Executors.newSingleThreadExecutor();

            try (ServerSocket server = TlsSupport.listen(0)) {
                server.setSoTimeout(10000);

                Future<Void> receiver = worker.submit(() -> {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(10000);
                        FileReceiver.receive(socket, new Listener(),
                                (name, size, address) -> true);
                    }
                    return null;
                });

                try (Socket socket = TlsSupport.connect(
                        "127.0.0.1", server.getLocalPort())) {
                    socket.setSoTimeout(10000);
                    FileSender.send(socket, source, senderListener);
                }

                receiver.get(15, TimeUnit.SECONDS);
                check(senderListener.initialOffset == CHECKPOINT_BYTES,
                        "Sender did not resume from the saved offset");
            } finally {
                worker.shutdownNow();
                worker.awaitTermination(15, TimeUnit.SECONDS);
            }

            try (var files = Files.list(peerFolder)) {
                Path received = files.filter(Files::isRegularFile)
                        .findFirst().orElseThrow();
                check(Arrays.equals(original, Files.readAllBytes(received)),
                        "Received file differs from source");
            }
            System.out.println("PASS: TLS resumed at 65536 bytes and final file matches");

            Path corruptFolder = root.resolve("corrupt");
            try (ResumeStore store = new ResumeStore(
                    corruptFolder, "source.bin", original.length, hash)) {
                store.append(original, CHECKPOINT_BYTES);
            }

            Path partial;
            try (var files = Files.list(corruptFolder.resolve(".resume"))) {
                partial = files.filter(path -> path.toString().endsWith(".part"))
                        .findFirst().orElseThrow();
            }

            byte[] damaged = Files.readAllBytes(partial);
            damaged[0] ^= 1;
            Files.write(partial, damaged);

            try (ResumeStore store = new ResumeStore(
                    corruptFolder, "source.bin", original.length, hash)) {
                check(store.offset() == 0, "Corrupt partial file was not reset");
            }

            System.out.println("PASS: corrupt partial file resets to zero");
            System.out.println("ALL RESUME TESTS PASSED");
        } finally {
            if (previousDirectory == null) {
                System.clearProperty("syncshare.receiveDir");
            } else {
                System.setProperty("syncshare.receiveDir", previousDirectory);
            }

            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }
}