package dev.otafleet.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/** Monta imagens falsas com o mesmo layout de uma imagem real do ESP32. */
public final class TestImages {

    private TestImages() {
    }

    public static byte[] build(String version) {
        return build(version, "ota-fleet-device", 4096);
    }

    public static byte[] build(String version, String project, int size) {
        byte[] image = new byte[size];
        new Random(version.hashCode()).nextBytes(image);
        image[0] = (byte) 0xE9;
        ByteBuffer.wrap(image, 32, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(0xABCD5432);
        writeCString(image, 48, version);
        writeCString(image, 80, project);
        return image;
    }

    private static void writeCString(byte[] image, int offset, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 32; i++) {
            image[offset + i] = i < bytes.length ? bytes[i] : 0;
        }
    }
}
