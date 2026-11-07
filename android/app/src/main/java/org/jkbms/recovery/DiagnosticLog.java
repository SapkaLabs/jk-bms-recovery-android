package org.jkbms.recovery;

import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Process-local, bounded log. No disk writes, Logcat, or network transport. Main-thread owned. */
public final class DiagnosticLog {
    public enum Level { DEBUG, INFO, WARN, ERROR }

    private final ArrayDeque<String> entries = new ArrayDeque<>();
    private final String heading;
    private final int maxEntries;
    private final int maxCharacters;
    private final Clock clock;
    private final long started = System.nanoTime();
    private int characters;
    private int dropped;
    private Runnable onChanged;

    public DiagnosticLog(String heading) { this(heading, 1500, 300_000, Clock.systemUTC()); }

    DiagnosticLog(String heading, int maxEntries, int maxCharacters, Clock clock) {
        this.heading = heading;
        this.maxEntries = maxEntries;
        this.maxCharacters = maxCharacters;
        this.clock = clock;
    }

    public void setOnChanged(Runnable listener) { onChanged = listener; }

    public void add(Level level, String area, String message) {
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        String entry = DateTimeFormatter.ISO_INSTANT.format(clock.instant()) + " +" + elapsed
                + "ms [" + level + "] " + area + " | " + escapeLines(message) + "\n";
        if (entry.length() > maxCharacters) entry = entry.substring(0, maxCharacters - 16) + " [truncated]\n";
        entries.addLast(entry);
        characters += entry.length();
        while (entries.size() > maxEntries || characters > maxCharacters) {
            characters -= entries.removeFirst().length();
            dropped++;
        }
        if (onChanged != null) onChanged.run();
    }

    private static String escapeLines(String value) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (Character.isISOControl(character)) result.append(String.format(Locale.ROOT, "\\u%04X", (int) character));
            else result.append(character);
        }
        return result.toString();
    }

    public int size() { return entries.size(); }

    public String snapshot() {
        StringBuilder text = new StringBuilder(heading).append('\n')
                .append("UTC timestamps; elapsed milliseconds since this log started.\n")
                .append("Raw BLE responses may contain passcodes.\n");
        if (dropped > 0) text.append("Older entries removed by log limit: ").append(dropped).append('\n');
        for (String entry : entries) text.append(entry);
        return text.toString();
    }

    public void clear() {
        entries.clear();
        characters = 0;
        dropped = 0;
        if (onChanged != null) onChanged.run();
    }

    public static String hex(byte[] bytes) {
        char[] digits = "0123456789ABCDEF".toCharArray();
        StringBuilder text = new StringBuilder(bytes.length * 3);
        for (byte value : bytes) {
            if (text.length() > 0) text.append(' ');
            text.append(digits[(value & 0xff) >>> 4]).append(digits[value & 0x0f]);
        }
        return text.toString();
    }
}
