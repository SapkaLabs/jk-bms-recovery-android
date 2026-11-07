package org.jkbms.recovery;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.*;

public class JkProtocolTest {
    /** Independently laid out fixture matching upstream's sequential field widths. */
    private byte[] infoFrame() {
        byte[] frame = new byte[300];
        byte[] header = {0x55, (byte) 0xaa, (byte) 0xeb, (byte) 0x90, 3, 42};
        System.arraycopy(header, 0, frame, 0, header.length);
        put(frame, 6, "JK_B2A24S15P");
        put(frame, 22, "11.XW");
        put(frame, 30, "11.26");
        put(frame, 46, "My battery");
        put(frame, 62, "1234");
        put(frame, 78, "230430");
        put(frame, 86, "12345678901");
        put(frame, 97, "0000");
        Arrays.fill(frame, 102, 118, (byte) 'X'); // User data must be skipped.
        put(frame, 118, "654321");
        sign(frame);
        return frame;
    }

    private void put(byte[] frame, int offset, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, frame, offset, bytes.length);
    }

    private void sign(byte[] frame) {
        int sum = 0;
        for (int i = 0; i < 299; i++) sum += Byte.toUnsignedInt(frame[i]);
        frame[299] = (byte) (sum % 256);
    }

    private byte[] concat(byte[]... arrays) {
        int size = 0;
        for (byte[] array : arrays) size += array.length;
        byte[] output = new byte[size];
        int offset = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, output, offset, array.length);
            offset += array.length;
        }
        return output;
    }

    @Test public void requestMatchesUpstreamGoldenBytes() {
        assertArrayEquals(new byte[]{(byte) 0xaa, 0x55, (byte) 0x90, (byte) 0xeb, (byte) 0x97,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x11}, JkProtocol.deviceInfoRequest());
    }

    @Test public void parsesEveryDocumentedFieldAndPreservesLeadingZeros() {
        JkProtocol.DeviceInfo info = JkProtocol.parseDeviceInfo(infoFrame());
        assertEquals("JK_B2A24S15P", info.value(JkProtocol.Field.MODEL));
        assertEquals("11.XW", info.value(JkProtocol.Field.HARDWARE));
        assertEquals("11.26", info.value(JkProtocol.Field.FIRMWARE));
        assertEquals("My battery", info.value(JkProtocol.Field.DEVICE_NAME));
        assertEquals("1234", info.value(JkProtocol.Field.DEVICE_PASSCODE));
        assertEquals("230430", info.value(JkProtocol.Field.MANUFACTURED));
        assertEquals("12345678901", info.value(JkProtocol.Field.SERIAL));
        assertEquals("0000", info.value(JkProtocol.Field.PASSCODE));
        assertEquals("654321", info.value(JkProtocol.Field.SETUP_PASSCODE));
    }

    @Test public void handlesEveryPossibleTwoPartSplitIncludingHeader() {
        byte[] frame = infoFrame();
        for (int split = 0; split <= frame.length; split++) {
            JkProtocol.Decoder decoder = new JkProtocol.Decoder();
            List<byte[]> frames = new ArrayList<>();
            frames.addAll(decoder.accept(Arrays.copyOfRange(frame, 0, split)));
            frames.addAll(decoder.accept(Arrays.copyOfRange(frame, split, frame.length)));
            assertEquals("split " + split, 1, frames.size());
            assertArrayEquals(frame, frames.get(0));
        }
    }

    @Test public void handlesTypicalTwentyByteBleNotifications() {
        JkProtocol.Decoder decoder = new JkProtocol.Decoder();
        byte[] frame = infoFrame();
        for (int offset = 0; offset < 280; offset += 20) {
            assertTrue(decoder.accept(Arrays.copyOfRange(frame, offset, offset + 20)).isEmpty());
        }
        assertArrayEquals(frame, decoder.accept(Arrays.copyOfRange(frame, 280, 300)).get(0));
    }

    @Test public void handlesRandomNotificationSizesAndMultipleFrames() {
        byte[] stream = concat(infoFrame(), infoFrame(), infoFrame());
        Random random = new Random(17);
        JkProtocol.Decoder decoder = new JkProtocol.Decoder();
        List<byte[]> frames = new ArrayList<>();
        for (int offset = 0; offset < stream.length;) {
            int end = Math.min(stream.length, offset + 1 + random.nextInt(240));
            frames.addAll(decoder.accept(Arrays.copyOfRange(stream, offset, end)));
            offset = end;
        }
        assertEquals(3, frames.size());
        for (byte[] frame : frames) assertArrayEquals(infoFrame(), frame);
    }

    @Test public void resynchronizesAfterNoiseAndSplitHeader() {
        JkProtocol.Decoder decoder = new JkProtocol.Decoder();
        assertTrue(decoder.accept(new byte[]{1, 2, 0x55}).isEmpty());
        byte[] frame = infoFrame();
        assertArrayEquals(frame, decoder.accept(Arrays.copyOfRange(frame, 1, 300)).get(0));
    }

    @Test public void rejectsCorruptionThenRecoversNextFrame() {
        JkProtocol.Decoder decoder = new JkProtocol.Decoder();
        byte[] corrupt = infoFrame();
        corrupt[100] ^= 1;
        List<byte[]> frames = decoder.accept(concat(corrupt, infoFrame()));
        assertEquals(1, frames.size());
        assertArrayEquals(infoFrame(), frames.get(0));
        assertEquals(1, decoder.rejectedFrames());
    }

    @Test public void findsNewFrameAfterTruncatedResponse() {
        JkProtocol.Decoder decoder = new JkProtocol.Decoder();
        byte[] partial = Arrays.copyOf(infoFrame(), 100);
        List<byte[]> frames = decoder.accept(concat(partial, infoFrame()));
        assertEquals(1, frames.size());
        assertArrayEquals(infoFrame(), frames.get(0));
    }

    @Test public void acceptsCombinedFramesAndTwentyPaddingBytes() {
        JkProtocol.Decoder decoder = new JkProtocol.Decoder();
        List<byte[]> frames = decoder.accept(concat(infoFrame(), new byte[20], infoFrame()));
        assertEquals(2, frames.size());
    }

    @Test public void keepsMemoryBoundedWithLargeGarbageInput() {
        JkProtocol.Decoder decoder = new JkProtocol.Decoder();
        byte[] noise = new byte[100_000];
        Arrays.fill(noise, (byte) 0x55);
        assertTrue(decoder.accept(noise).isEmpty());
        assertEquals(1, decoder.accept(infoFrame()).size());
    }

    @Test public void emptySecretsStayEmptyInsteadOfInventingDefaults() {
        byte[] frame = infoFrame();
        Arrays.fill(frame, 62, 78, (byte) 0);
        Arrays.fill(frame, 97, 102, (byte) 0);
        Arrays.fill(frame, 118, 134, (byte) 0);
        sign(frame);
        JkProtocol.DeviceInfo info = JkProtocol.parseDeviceInfo(frame);
        assertEquals("", info.value(JkProtocol.Field.DEVICE_PASSCODE));
        assertEquals("", info.value(JkProtocol.Field.PASSCODE));
        assertEquals("", info.value(JkProtocol.Field.SETUP_PASSCODE));
    }

    @Test public void preservesFullWidthPasscodeWithoutNullTerminator() {
        byte[] frame = infoFrame();
        put(frame, 62, "1234567890123456");
        sign(frame);
        assertEquals("1234567890123456", JkProtocol.parseDeviceInfo(frame).value(JkProtocol.Field.DEVICE_PASSCODE));
    }

    @Test public void rejectsShortLongBadChecksumAndWrongResponseType() {
        assertThrows(IllegalArgumentException.class, () -> JkProtocol.parseDeviceInfo(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> JkProtocol.parseDeviceInfo(Arrays.copyOf(infoFrame(), 299)));
        assertThrows(IllegalArgumentException.class, () -> JkProtocol.parseDeviceInfo(Arrays.copyOf(infoFrame(), 320)));
        byte[] corrupt = infoFrame();
        corrupt[299]++;
        assertThrows(IllegalArgumentException.class, () -> JkProtocol.parseDeviceInfo(corrupt));
        byte[] wrongType = infoFrame();
        wrongType[4] = 2;
        sign(wrongType);
        assertThrows(IllegalArgumentException.class, () -> JkProtocol.parseDeviceInfo(wrongType));
    }

    @Test public void refusesMalformedTextRatherThanShowingIncorrectPasscodes() {
        byte[] frame = infoFrame();
        frame[62] = (byte) 0xff;
        sign(frame);
        assertThrows(IllegalArgumentException.class, () -> JkProtocol.parseDeviceInfo(frame));
    }

    @Test public void readsNullTerminatedDeviceNameWithoutRejectingUnusedBytes() {
        byte[] frame = infoFrame();
        Arrays.fill(frame, 46, 62, (byte) 'X');
        frame[46] = 'J';
        frame[47] = 'K';
        frame[48] = 0;
        sign(frame);
        JkProtocol.DeviceInfo info = JkProtocol.parseDeviceInfo(frame);
        assertEquals("JK", info.value(JkProtocol.Field.DEVICE_NAME));
        assertEquals("1234", info.value(JkProtocol.Field.DEVICE_PASSCODE));
    }

    @Test public void controlByteInDeviceNameDoesNotBlockPasscodeRecovery() {
        byte[] frame = infoFrame();
        frame[48] = 1;
        sign(frame);
        JkProtocol.DeviceInfo info = JkProtocol.parseDeviceInfo(frame);
        assertEquals("My\\u0001battery", info.value(JkProtocol.Field.DEVICE_NAME));
        assertEquals("1234", info.value(JkProtocol.Field.DEVICE_PASSCODE));
        assertEquals("0000", info.value(JkProtocol.Field.PASSCODE));
        assertEquals("654321", info.value(JkProtocol.Field.SETUP_PASSCODE));
    }

    @Test public void malformedDeviceNameDoesNotBlockPasscodeRecovery() {
        byte[] frame = infoFrame();
        frame[48] = (byte) 0xff;
        sign(frame);
        JkProtocol.DeviceInfo info = JkProtocol.parseDeviceInfo(frame);
        assertEquals("My\ufffdbattery", info.value(JkProtocol.Field.DEVICE_NAME));
        assertEquals("1234", info.value(JkProtocol.Field.DEVICE_PASSCODE));
    }

    @Test public void terminatesPasscodeAtFirstNullWithoutReadingStaleBytes() {
        byte[] frame = infoFrame();
        Arrays.fill(frame, 66, 78, (byte) 0xff);
        frame[66] = 0;
        sign(frame);
        assertEquals("1234", JkProtocol.parseDeviceInfo(frame).value(JkProtocol.Field.DEVICE_PASSCODE));
    }

    @Test public void preservesUnicodeDeviceName() {
        byte[] frame = infoFrame();
        Arrays.fill(frame, 46, 62, (byte) 0);
        put(frame, 46, "Батарея");
        sign(frame);
        assertEquals("Батарея", JkProtocol.parseDeviceInfo(frame).value(JkProtocol.Field.DEVICE_NAME));
    }

    @Test public void refusesNonNullControlCharactersInPasscodes() {
        byte[] frame = infoFrame();
        frame[63] = 1;
        sign(frame);
        assertThrows(IllegalArgumentException.class, () -> JkProtocol.parseDeviceInfo(frame));
    }

    @Test public void checksumDiagnosticsCaptureRejectedFrameWithoutChangingRecovery() {
        List<byte[]> rejected = new ArrayList<>();
        JkProtocol.Decoder decoder = new JkProtocol.Decoder((frame, calculated, expected) -> {
            rejected.add(frame);
            assertNotEquals(calculated, expected);
        });
        byte[] corrupt = infoFrame();
        corrupt[100] ^= 1;
        List<byte[]> good = decoder.accept(concat(corrupt, infoFrame()));
        assertEquals(1, rejected.size());
        assertArrayEquals(corrupt, rejected.get(0));
        assertEquals(1, good.size());
        assertEquals(0, decoder.bufferedBytes());
        assertEquals(300, decoder.discardedBytes());
    }

    @Test public void fieldDiagnosticsStillWorkWhenTextDecodingFails() {
        byte[] frame = infoFrame();
        frame[62] = (byte) 0xff;
        sign(frame);
        assertThrows(IllegalArgumentException.class, () -> JkProtocol.parseDeviceInfo(frame));
        List<String> details = JkProtocol.fieldDiagnostics(frame);
        assertEquals(9, details.size());
        assertTrue(details.get(3).contains("Device name offset=46 width=16 firstNul=10"));
        assertTrue(details.get(4).contains("Device passcode offset=62 width=16 firstNul=4 raw=FF"));
    }
}
