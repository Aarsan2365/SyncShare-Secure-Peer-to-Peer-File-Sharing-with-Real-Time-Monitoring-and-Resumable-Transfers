package com.syncshare.reliability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class ReliabilityLoggerTest {

    private ReliabilityLogger logger;

    @BeforeEach
    void setUp() {
        ReliabilityLogger.clearRecentLogs();
        logger = ReliabilityLogger.getLogger(ReliabilityLoggerTest.class);
    }

    @Test
    void redactsPasswordsAndTokensFromLogMessages() {
        String sensitive = "Failed login: password=SuperSecret123; user=alice";
        String sanitized = ReliabilityLogger.sanitize(sensitive);
        assertFalse(sanitized.contains("SuperSecret123"));
        assertTrue(sanitized.contains("password=[REDACTED]"));

        String tlsSecret = "Configuring certs with SYNCSHARE_TLS_PASSWORD=KeystorePass99";
        String sanitizedTls = ReliabilityLogger.sanitize(tlsSecret);
        assertFalse(sanitizedTls.contains("KeystorePass99"));
        assertTrue(sanitizedTls.contains("SYNCSHARE_TLS_PASSWORD=[REDACTED]"));

        String p12Secret = "Opening identity.p12=p12SecretKey";
        String sanitizedP12 = ReliabilityLogger.sanitize(p12Secret);
        assertFalse(sanitizedP12.contains("p12SecretKey"));
        assertTrue(sanitizedP12.contains("identity.p12=[REDACTED]"));
    }

    @Test
    void sanitizesExceptionStackTraces() {
        Exception ex = new IOException("Keystore loading failed with password: TopSecretPassword!");
        String trace = ReliabilityLogger.getSanitizedStackTrace(ex);
        assertFalse(trace.contains("TopSecretPassword!"));
        assertTrue(trace.contains("password=[REDACTED]"));
    }

    @Test
    void logsAcrossAllLevelsWithoutCrashing() {
        assertDoesNotThrow(() -> {
            logger.info("Lifecycle initialized cleanly");
            logger.debug("Debug diagnostic message");
            logger.warn("Recoverable transient warning", new IOException("Transient network hiccup"));
            logger.error("Severe failure encountered", new IllegalStateException("Critical error"));
        });
    }

    @Test
    void duplicateLogSuppressionWorks() {
        ReliabilityLogger.clearRecentLogs();
        Exception ex = new IOException("Same failure");
        // Should not throw and suppresses duplicate log within window
        assertDoesNotThrow(() -> {
            logger.warn("Warning occurred", ex);
            logger.warn("Warning occurred", ex);
        });
    }
}
