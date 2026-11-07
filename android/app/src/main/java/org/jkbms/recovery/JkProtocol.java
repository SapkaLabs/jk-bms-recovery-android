package org.jkbms.recovery;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** The 300-byte device-information layout used by upstream jikong.py. */
public final class JkProtocol {
    public static final int FRAME_SIZE = 300;
    private static final byte[] HEADER = {0x55, (byte) 0xaa, (byte) 0xeb, (byte) 0x90};

    private JkProtocol() { }

    public enum Field {
        MODEL("Model", 6, 16, false),
        HARDWARE("Hardware", 22, 8, false),
        FIRMWARE("Firmware", 30, 8, false),
        DEVICE_NAME("Device name", 46, 16, false),
        DEVICE_PASSCODE("Device passcode", 62, 16, true),
        MANUFACTURED("Manufactured", 78, 8, false),
        SERIAL("Serial number", 86, 11, false),
        PASSCODE("Passcode", 97, 5, true),
        SETUP_PASSCODE("Setup passcode", 118, 16, true);

        public final String label;
        public final boolean secret;
        private final int offset;
        private final int width;

        Field(String label, int offset, int width, boolean secret) {
            this.label = label;
            this.offset = offset;
            this.width = width;
            this.secret = secret;
        }
    }

    public static final class DeviceInfo {
        private final Map<Field, String> values;

        private DeviceInfo(EnumMap<Field, String> values) {
            this.values = Collections.unmodifiableMap(values);
        }

        public String value(Field field) { return values.get(field); }
    }

    /** The only device command the app sends: request information (0x97). */
    public static byte[] deviceInfoRequest() {
        byte[] frame = new byte[20];
        frame[0] = (byte) 0xaa;
        frame[1] = 0x55;
        frame[2] = (byte) 0x90;
        frame[3] = (byte) 0xeb;
        frame[4] = (byte) 0x97;
        frame[19] = (byte) checksum(frame, 19);
        return frame;
    }

    public static int checksum(byte[] bytes, int length) {
        int sum = 0;
        for (int i = 0; i < length; i++) sum += bytes[i] & 0xff;
        return sum & 0xff;
    }

    public static DeviceInfo parseDeviceInfo(byte[] frame) {
        if (frame.length != FRAME_SIZE || !hasHeader(frame)
                || checksum(frame, 299) != (frame[299] & 0xff)) {
            throw new IllegalArgumentException("Invalid JK frame or checksum.");
        }
        if (frame[4] != 0x03) throw new IllegalArgumentException("Not a device-information response.");
        EnumMap<Field, String> values = new EnumMap<>(Field.class);
        for (Field field : Field.values()) {
            int end = field.offset + field.width;
            // Fixed-width C strings can contain unused bytes after their first NUL.
            for (int i = field.offset; i < end; i++) {
                if (frame[i] == 0) { end = i; break; }
            }
            try {
                CodingErrorAction malformedText = field.secret ? CodingErrorAction.REPORT : CodingErrorAction.REPLACE;
                String value = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(malformedText)
                        .onUnmappableCharacter(malformedText)
                        .decode(ByteBuffer.wrap(frame, field.offset, end - field.offset)).toString();
                StringBuilder displayed = new StringBuilder();
                for (int i = 0; i < value.length(); i++) {
                    char character = value.charAt(i);
                    if (Character.isISOControl(character)) {
                        if (field.secret) throw new IllegalArgumentException("Unexpected text in " + field.label + ".");
                        displayed.append(String.format(java.util.Locale.ROOT, "\\u%04X", (int) character));
                    } else displayed.append(character);
                }
                values.put(field, displayed.toString());
            } catch (CharacterCodingException e) {
                throw new IllegalArgumentException("Unsupported text encoding in " + field.label + ".", e);
            }
        }
        return new DeviceInfo(values);
    }

    private static boolean hasHeader(byte[] bytes) {
        for (int i = 0; i < HEADER.length; i++) if (bytes[i] != HEADER[i]) return false;
        return true;
    }

    /** Field boundaries and bytes, also useful when text decoding fails. Contains passcodes. */
    public static List<String> fieldDiagnostics(byte[] frame) {
        if (frame.length != FRAME_SIZE) throw new IllegalArgumentException("Expected a 300-byte frame.");
        List<String> details = new ArrayList<>();
        for (Field field : Field.values()) {
            int nul = -1;
            for (int i = field.offset; i < field.offset + field.width; i++) {
                if (frame[i] == 0) { nul = i - field.offset; break; }
            }
            details.add(field.label + " offset=" + field.offset + " width=" + field.width + " firstNul=" + nul
                    + " raw=" + DiagnosticLog.hex(Arrays.copyOfRange(frame, field.offset, field.offset + field.width)));
        }
        return details;
    }

    /** Bounded stream decoder; handles split headers, garbage and combined notifications. */
    public static final class Decoder {
        public interface Observer { void onRejected(byte[] frame, int calculated, int expected); }
        private final byte[] buffer = new byte[FRAME_SIZE];
        private final Observer observer;
        private int size;
        private int rejectedFrames;
        private int discardedBytes;

        public Decoder() { this((frame, calculated, expected) -> { }); }

        public Decoder(Observer observer) { this.observer = observer; }

        public List<byte[]> accept(byte[] notification) {
            List<byte[]> frames = new ArrayList<>();
            for (byte next : notification) {
                buffer[size++] = next;
                while (size >= 4 && !hasHeader(buffer)) discardFirstByte();
                if (size == FRAME_SIZE) {
                    if (checksum(buffer, 299) == (buffer[299] & 0xff)) {
                        frames.add(Arrays.copyOf(buffer, FRAME_SIZE));
                        size = 0;
                    } else {
                        rejectedFrames++;
                        observer.onRejected(Arrays.copyOf(buffer, FRAME_SIZE), checksum(buffer, 299), buffer[299] & 0xff);
                        discardFirstByte();
                        while (size >= 4 && !hasHeader(buffer)) discardFirstByte();
                    }
                }
            }
            return frames;
        }

        private void discardFirstByte() {
            discardedBytes++;
            System.arraycopy(buffer, 1, buffer, 0, --size);
        }

        public int rejectedFrames() { return rejectedFrames; }
        public int bufferedBytes() { return size; }
        public int discardedBytes() { return discardedBytes; }
    }

    /** Clearly labelled sample data, passed through the same parser as a live response. */
    public static DeviceInfo sample() {
        byte[] frame = new byte[FRAME_SIZE];
        System.arraycopy(HEADER, 0, frame, 0, HEADER.length);
        frame[4] = 3;
        EnumMap<Field, String> values = new EnumMap<>(Field.class);
        values.put(Field.MODEL, "JK_B2A24S15P");
        values.put(Field.HARDWARE, "11.XW");
        values.put(Field.FIRMWARE, "11.26");
        values.put(Field.DEVICE_NAME, "Demo battery");
        values.put(Field.DEVICE_PASSCODE, "1234");
        values.put(Field.MANUFACTURED, "230430");
        values.put(Field.SERIAL, "DEMO0000001");
        values.put(Field.PASSCODE, "0000");
        values.put(Field.SETUP_PASSCODE, "123456");
        values.forEach((field, value) -> {
            byte[] text = value.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(text, 0, frame, field.offset, text.length);
        });
        frame[299] = (byte) checksum(frame, 299);
        return parseDeviceInfo(frame);
    }
}
