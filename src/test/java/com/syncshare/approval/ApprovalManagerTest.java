package com.syncshare.approval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.net.InetAddress;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ApprovalManagerTest {
    @Test
    @Timeout(5)
    void allowsApprovalFromSeparateThread() throws Exception {
        ApprovalManager manager = new ApprovalManager();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> result = pool.submit(() ->
                    manager.approve("hello.txt", 5L,
                            InetAddress.getLoopbackAddress()));

            // The request is registered asynchronously, so poll briefly for it.
            boolean decided = false;
            for (int attempt = 0; attempt < 100 && !decided; attempt++) {
                decided = manager.decide(1, true);
                if (!decided) Thread.sleep(10);
            }
            assertTrue(decided, "Approval request was not registered");
            assertTrue(result.get(3, TimeUnit.SECONDS));
        } finally {
            manager.rejectAll();
            pool.shutdownNow();
        }
    }
}
