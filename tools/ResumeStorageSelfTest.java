// tools/ResumeStorageSelfTest.java
import com.syncshare.events.TransferListener;
import com.syncshare.resume.ResumeStore;
import com.syncshare.transfer.FileReceiver;
import com.syncshare.transfer.FileSender;
import com.syncshare.transfer.HashUtils;

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class ResumeStorageSelfTest {

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static class Listener implements TransferListener {

        long initialOffset = -1;

        public void onStarted(String name, long size) {
        }

        public void onProgress(String name, long bytes, long size) {
            if (initialOffset < 0) {
                initialOffset = bytes;
            }
        }

        public void onCompleted(String name) {
        }

        public void onError(String name, Exception error) {
        }
    }

    public static void main(String[] args) throws Exception {

        if (args.length > 0 && args[0].equals("crash")) {
            Path root = Path.of(args[1]);
            Path source = root.resolve("source.bin");

            ResumeStore store = new ResumeStore(
                root.resolve("received"),
                "source.bin",
                Files.size(source),
                HashUtils.calculateSHA256(source)
            );

            store.append(
                Arrays.copyOf(Files.readAllBytes(source), 65536),
                65536
            );

            // Abruptly stop without closing the store.
            Runtime.getRuntime().halt(0);
        }

        Path root = Files.createTempDirectory("syncshare-resume-test-");

        String previousDirectory = System.getProperty(
            "syncshare.receiveDir"
        );

        try {
            byte[] original = new byte[200000];
            new Random(42).nextBytes(original);

            Path source = root.resolve("source.bin");
            Files.write(source, original);

            String hash = HashUtils.calculateSHA256(source);

            String javaCommand = Path.of(
                System.getProperty("java.home"),
                "bin",
                "java"
            ).toString();

            Process child = new ProcessBuilder(
                javaCommand,
                "-cp",
                System.getProperty("java.class.path"),
                "ResumeStorageSelfTest",
                "crash",
                root.toString()
            ).inheritIO().start();

            if (!child.waitFor(20, TimeUnit.SECONDS)) {
                child.destroyForcibly();
                child.waitFor();
                throw new AssertionError("Child process timed out");
            }

            check(child.exitValue() == 0, "Checkpoint setup failed");

            try (ResumeStore store = new ResumeStore(
                    root.resolve("received"),
                    "source.bin",
                    original.length,
                    hash
            )) {
                check(
                    store.offset() == 65536,
                    "Checkpoint did not survive JVM restart"
                );
            }

            System.out.println("PASS: checkpoint survives JVM restart");

            System.setProperty(
                "syncshare.receiveDir",
                root.resolve("received").toString()
            );

            Listener senderListener = new Listener();
            ExecutorService worker = Executors.newSingleThreadExecutor();

            try (ServerSocket server = new ServerSocket(0)) {
                server.setSoTimeout(10000);

                Future<?> receiver = worker.submit(() -> {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(10000);

                        FileReceiver.receive(
                            socket,
                            new Listener(),
                            (name, size, address) -> true
                        );

                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

                try (Socket socket = new Socket(
                        "127.0.0.1",
                        server.getLocalPort()
                )) {
                    socket.setSoTimeout(10000);
                    FileSender.send(socket, source, senderListener);
                }

                receiver.get(15, TimeUnit.SECONDS);

                check(
                    senderListener.initialOffset == 65536,
                    "Sender did not resume from saved offset"
                );

            } finally {
                worker.shutdownNow();
                worker.awaitTermination(15, TimeUnit.SECONDS);
            }

            try (var files = Files.list(root.resolve("received"))) {
                Path received = files
                    .filter(Files::isRegularFile)
                    .findFirst()
                    .orElseThrow();

                check(
                    Arrays.equals(original, Files.readAllBytes(received)),
                    "Received file differs from source"
                );
            }

            System.out.println("PASS: TCP resume and final file match");

            Path corruptFolder = root.resolve("corrupt");

            try (ResumeStore store = new ResumeStore(
                    corruptFolder,
                    "source.bin",
                    original.length,
                    hash
            )) {
                store.append(original, 65536);
            }

            Path partial;

            try (var files = Files.list(corruptFolder.resolve(".resume"))) {
                partial = files
                    .filter(path -> path.toString().endsWith(".part"))
                    .findFirst()
                    .orElseThrow();
            }

            byte[] damaged = Files.readAllBytes(partial);
            damaged[0] ^= 1;
            Files.write(partial, damaged);

            try (ResumeStore store = new ResumeStore(
                    corruptFolder,
                    "source.bin",
                    original.length,
                    hash
            )) {
                check(
                    store.offset() == 0,
                    "Corrupt partial file was not reset"
                );
            }

            System.out.println("PASS: corrupt partial file resets to zero");
            System.out.println("ALL RESUME TESTS PASSED");

        } finally {
            if (previousDirectory == null) {
                System.clearProperty("syncshare.receiveDir");
            } else {
                System.setProperty(
                    "syncshare.receiveDir",
                    previousDirectory
                );
            }

            try (var paths = Files.walk(root)) {
                for (Path path :
                        paths.sorted(Comparator.reverseOrder()).toList()) {

                    Files.deleteIfExists(path);
                }
            }
        }
    }
}