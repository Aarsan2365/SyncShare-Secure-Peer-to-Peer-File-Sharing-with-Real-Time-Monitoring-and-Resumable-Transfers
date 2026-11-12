package com.syncshare.history;

import com.syncshare.model.TransferDirection;
import com.syncshare.model.TransferStatus;
import com.syncshare.monitoring.TransferMonitorState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferRecordTest {

    private static final Instant SAMPLE_TIME = Instant.parse("2026-11-12T10:15:30Z");

    @Test
    void constructsValidRecordAndFormatsFields() {
        TransferRecord record = new TransferRecord(
            "tx-100",
            "report.pdf",
            2048L,
            TransferHistoryDirection.RECEIVE,
            "192.168.1.42:5050",
            SAMPLE_TIME,
            Duration.ofSeconds(65),
            TransferHistoryStatus.COMPLETED,
            "received_files",
            "SHA-256 verified"
        );

        assertEquals("tx-100", record.transferId());
        assertEquals("report.pdf", record.fileName());
        assertEquals(2048L, record.fileSize());
        assertEquals("2.0 KB", record.formattedSize());
        assertEquals(TransferHistoryDirection.RECEIVE, record.direction());
        assertEquals("192.168.1.42:5050", record.peerInfo());
        assertEquals(SAMPLE_TIME, record.transferredAt());
        assertEquals(Duration.ofSeconds(65), record.duration());
        assertEquals("1m 5s", record.formattedDuration());
        assertEquals(TransferHistoryStatus.COMPLETED, record.status());
        assertEquals("received_files", record.destination());
        assertTrue(record.hasDestination());
        assertEquals("SHA-256 verified", record.summaryNote());
        assertFalse(record.formattedDateTime().isBlank());
    }

    @Test
    void handlesZeroByteFilesAndUnknownFailureSize() {
        TransferRecord zeroByte = new TransferRecord(
            "tx-zero",
            "empty.txt",
            0L,
            TransferHistoryDirection.SEND,
            "10.0.0.5:5050",
            SAMPLE_TIME,
            Duration.ZERO,
            TransferHistoryStatus.COMPLETED,
            ""
        );
        assertEquals(0L, zeroByte.fileSize());
        assertEquals("0 B", zeroByte.formattedSize());
        assertEquals("< 1s", zeroByte.formattedDuration());
        assertFalse(zeroByte.hasDestination());
        assertEquals("—", zeroByte.formattedDestination());

        TransferRecord unknownSizeFailed = new TransferRecord(
            "tx-early-fail",
            "Incoming file",
            TransferRecord.UNKNOWN_SIZE,
            TransferHistoryDirection.RECEIVE,
            "10.0.0.9",
            SAMPLE_TIME,
            Duration.ofMillis(320),
            TransferHistoryStatus.FAILED,
            ""
        );
        assertEquals("Unknown", unknownSizeFailed.formattedSize());
        assertEquals("320 ms", unknownSizeFailed.formattedDuration());
    }

    @Test
    void sanitizesFullPathsAndRedactsSensitiveNotes() {
        TransferRecord record = new TransferRecord(
            "tx-sanitize",
            "C:\\Users\\alice\\Documents\\secret-archive.zip",
            4096L,
            TransferHistoryDirection.RECEIVE,
            "192.168.1.18:5050",
            SAMPLE_TIME,
            Duration.ofSeconds(2),
            TransferHistoryStatus.COMPLETED,
            "C:\\Users\\alice\\Downloads\\received_files",
            "auth token leaked"
        );

        assertEquals("secret-archive.zip", record.fileName());
        assertEquals("received_files", record.destination());
        assertFalse(record.destination().contains("Users"));
        assertFalse(record.destination().contains("alice"));
        assertEquals("Sensitive details redacted", record.summaryNote());
    }

    @Test
    void rejectsInvalidFieldsAndNonTerminalStates() {
        assertThrows(IllegalArgumentException.class, () -> new TransferRecord(
            " ", "a.txt", 10L, TransferHistoryDirection.SEND, "peer", SAMPLE_TIME, Duration.ZERO, TransferHistoryStatus.COMPLETED, ""
        ));
        assertThrows(IllegalArgumentException.class, () -> new TransferRecord(
            "id-1", "..", 10L, TransferHistoryDirection.SEND, "peer", SAMPLE_TIME, Duration.ZERO, TransferHistoryStatus.COMPLETED, ""
        ));
        assertThrows(IllegalArgumentException.class, () -> new TransferRecord(
            "id-1", "a.txt", -2L, TransferHistoryDirection.SEND, "peer", SAMPLE_TIME, Duration.ZERO, TransferHistoryStatus.COMPLETED, ""
        ));
        assertThrows(IllegalArgumentException.class, () -> new TransferRecord(
            "id-1", "a.txt", 10L, TransferHistoryDirection.SEND, " ", SAMPLE_TIME, Duration.ZERO, TransferHistoryStatus.COMPLETED, ""
        ));
        assertThrows(NullPointerException.class, () -> new TransferRecord(
            "id-1", "a.txt", 10L, null, "peer", SAMPLE_TIME, Duration.ZERO, TransferHistoryStatus.COMPLETED, ""
        ));
        assertThrows(NullPointerException.class, () -> new TransferRecord(
            "id-1", "a.txt", 10L, TransferHistoryDirection.SEND, "peer", null, Duration.ZERO, TransferHistoryStatus.COMPLETED, ""
        ));
        assertThrows(IllegalArgumentException.class, () -> new TransferRecord(
            "id-1", "a.txt", 10L, TransferHistoryDirection.SEND, "peer", SAMPLE_TIME, Duration.ofMillis(-1), TransferHistoryStatus.COMPLETED, ""
        ));
        assertThrows(NullPointerException.class, () -> new TransferRecord(
            "id-1", "a.txt", 10L, TransferHistoryDirection.SEND, "peer", SAMPLE_TIME, Duration.ZERO, null, ""
        ));
        assertThrows(IllegalArgumentException.class, () -> new TransferRecord(
            "id-1", "a.txt", 10L, TransferHistoryDirection.SEND, "password=123", SAMPLE_TIME, Duration.ZERO, TransferHistoryStatus.COMPLETED, ""
        ));

        assertEquals(TransferHistoryDirection.SEND, TransferHistoryDirection.from(TransferDirection.OUTGOING));
        assertEquals(TransferHistoryDirection.RECEIVE, TransferHistoryDirection.from(TransferDirection.INCOMING));
        assertEquals(TransferHistoryStatus.COMPLETED, TransferHistoryStatus.from(TransferMonitorState.COMPLETED));
        assertEquals(TransferHistoryStatus.FAILED, TransferHistoryStatus.from(TransferMonitorState.FAILED));
        assertEquals(TransferHistoryStatus.CANCELLED, TransferHistoryStatus.from(TransferMonitorState.CANCELLED));
        assertThrows(IllegalArgumentException.class, () -> TransferHistoryStatus.from(TransferMonitorState.QUEUED));
        assertThrows(IllegalArgumentException.class, () -> TransferHistoryStatus.from(TransferMonitorState.CONNECTING));
        assertThrows(IllegalArgumentException.class, () -> TransferHistoryStatus.from(TransferMonitorState.RUNNING));
        assertThrows(IllegalArgumentException.class, () -> TransferHistoryStatus.from(TransferStatus.TRANSFERRING));
    }
}
