package org.jkbms.recovery;

import org.junit.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import static org.junit.Assert.*;

public class DiagnosticLogTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-07T12:34:56Z"), ZoneOffset.UTC);

    @Test public void includesTimestampSeverityAreaAndHexPacketBytes() {
        DiagnosticLog log = new DiagnosticLog("test-app", 10, 1000, clock);
        log.add(DiagnosticLog.Level.DEBUG, "rx", DiagnosticLog.hex(new byte[]{0, 0x55, (byte) 0xaa, (byte) 0xff}));
        String text = log.snapshot();
        assertTrue(text.startsWith("test-app\n"));
        assertTrue(text.contains("2026-11-07T12:34:56Z"));
        assertTrue(text.contains("[DEBUG] rx | 00 55 AA FF"));
    }

    @Test public void evictsOldEntriesAndReportsTheLoss() {
        DiagnosticLog log = new DiagnosticLog("test", 2, 1000, clock);
        log.add(DiagnosticLog.Level.INFO, "test", "first-entry");
        log.add(DiagnosticLog.Level.INFO, "test", "second-entry");
        log.add(DiagnosticLog.Level.ERROR, "test", "latest-error");
        assertEquals(2, log.size());
        assertFalse(log.snapshot().contains("first-entry"));
        assertTrue(log.snapshot().contains("latest-error"));
        assertTrue(log.snapshot().contains("Older entries removed by log limit: 1"));
    }

    @Test public void limitsCharactersEvenForVeryLargeMessages() {
        DiagnosticLog log = new DiagnosticLog("test", 100, 256, clock);
        log.add(DiagnosticLog.Level.INFO, "test", "A".repeat(10_000));
        assertEquals(1, log.size());
        assertTrue(log.snapshot().contains("[truncated]"));
        log.add(DiagnosticLog.Level.ERROR, "test", "newest-entry");
        assertEquals(1, log.size());
        assertTrue(log.snapshot().contains("newest-entry"));
        assertFalse(log.snapshot().contains("[truncated]"));
    }

    @Test public void deviceNamesCannotInjectFakeLogLines() {
        DiagnosticLog log = new DiagnosticLog("test", 10, 1000, clock);
        log.add(DiagnosticLog.Level.INFO, "scan", "name=JK\n[ERROR] forged\r\t");
        assertTrue(log.snapshot().contains("name=JK\\u000A[ERROR] forged\\u000D\\u0009"));
        assertFalse(log.snapshot().contains("\n[ERROR] forged"));
    }

    @Test public void clearRemovesEntriesAndTruncationHistoryAndNotifiesUi() {
        DiagnosticLog log = new DiagnosticLog("test", 1, 1000, clock);
        int[] updates = {0};
        log.setOnChanged(() -> updates[0]++);
        log.add(DiagnosticLog.Level.INFO, "test", "first");
        log.add(DiagnosticLog.Level.INFO, "test", "second");
        log.clear();
        assertEquals(3, updates[0]);
        assertEquals(0, log.size());
        assertFalse(log.snapshot().contains("first"));
        assertFalse(log.snapshot().contains("Older entries removed"));
        log.setOnChanged(null);
        log.add(DiagnosticLog.Level.INFO, "test", "third");
        assertEquals(3, updates[0]);
    }
}
