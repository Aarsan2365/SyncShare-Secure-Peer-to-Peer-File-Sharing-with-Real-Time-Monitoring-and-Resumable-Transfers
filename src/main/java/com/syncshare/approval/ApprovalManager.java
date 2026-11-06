
package com.syncshare.approval;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ApprovalManager implements ApprovalHandler {

    private static final int TIMEOUT_SECONDS = 120;

    private final AtomicInteger nextId = new AtomicInteger(1);

    private final ConcurrentHashMap<Integer, PendingRequest> pending =
        new ConcurrentHashMap<>();

    private record PendingRequest(
        int id,
        String fileName,
        long fileSize,
        InetAddress sender,
        CompletableFuture<Boolean> decision
    ) {}

    @Override
    public boolean approve(
            String fileName,
            long fileSize,
            InetAddress sender
    ) throws IOException {

        int id = nextId.getAndIncrement();

        PendingRequest request = new PendingRequest(
            id,
            fileName,
            fileSize,
            sender,
            new CompletableFuture<>()
        );

        pending.put(id, request);

        System.out.println(
            "\n===== Incoming File Request #" + id + " ====="
        );
        System.out.println("Sender: " + sender);
        System.out.println("File: " + fileName);
        System.out.println("Size: " + fileSize + " bytes");
        System.out.println("Type: accept " + id);
        System.out.println("Or:   reject " + id);
        System.out.print("syncshare> ");

        try {
            return request.decision().get(
                TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            );

        } catch (TimeoutException e) {
            System.out.println(
                "\nApproval timed out for request #" + id
            );
            return false;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(
                "Approval interrupted", e
            );

        } catch (ExecutionException e) {
            throw new IOException(
                "Approval failed", e
            );

        } finally {
            pending.remove(id, request);
        }
    }

    public boolean decide(int id, boolean accepted) {

        PendingRequest request = pending.get(id);

        if (request == null) {
            return false;
        }

        boolean completed =
            request.decision().complete(accepted);

        if (completed) {
            pending.remove(id, request);
        }

        return completed;
    }

    public void showPending() {

        if (pending.isEmpty()) {
            System.out.println("No pending file requests.");
            return;
        }

        pending.values().stream()
            .sorted(Comparator.comparingInt(PendingRequest::id))
            .forEach(request -> {
                System.out.printf(
                    "#%d | %s | %d bytes | %s%n",
                    request.id(),
                    request.fileName(),
                    request.fileSize(),
                    request.sender()
                );
            });
    }

    public void rejectAll() {

        for (Map.Entry<Integer, PendingRequest> entry
                : pending.entrySet()) {

            entry.getValue().decision().complete(false);
        }

        pending.clear();
    }
}
