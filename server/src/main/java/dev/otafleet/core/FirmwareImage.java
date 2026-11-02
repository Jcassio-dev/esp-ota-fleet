package dev.otafleet.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Lê o {@code esp_app_desc_t} de dentro de uma imagem de aplicação do ESP32.
 *
 * <pre>
 * offset 0   esp_image_header_t (24 bytes), byte 0 = 0xE9
 * offset 24  esp_image_segment_header_t (8 bytes)
 * offset 32  magic_word = 0xABCD5432 (little-endian)
 * offset 48  version[32], terminada em \0
 * offset 80  project_name[32], terminada em \0
 * </pre>
 */
public final class FirmwareImage {

    public static final int IMAGE_MAGIC = 0xE9;
    public static final int APP_DESC_MAGIC = 0xABCD5432;
    public static final int MIN_LENGTH = 112;

    private static final int MAGIC_OFFSET = 32;
    private static final int VERSION_OFFSET = 48;
    private static final int PROJECT_OFFSET = 80;
    private static final int FIELD_LENGTH = 32;

    public record AppDescriptor(String version, String projectName) {
    }

    public static class InvalidImageException extends RuntimeException {
        public InvalidImageException(String message) {
            super(message);
        }
    }

    private FirmwareImage() {
    }

    public static AppDescriptor readDescriptor(byte[] image) {
        if (image == null || image.length < MIN_LENGTH) {
            throw new InvalidImageException("arquivo pequeno demais para ser uma imagem do ESP32");
        }
        if ((image[0] & 0xFF) != IMAGE_MAGIC) {
            throw new InvalidImageException("cabeçalho da imagem não começa com 0xE9");
        }
        int magic = ByteBuffer.wrap(image, MAGIC_OFFSET, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (magic != APP_DESC_MAGIC) {
            throw new InvalidImageException("esp_app_desc_t não encontrado (magic_word inválido)");
        }
        String version = readCString(image, VERSION_OFFSET, "version");
        String project = readCString(image, PROJECT_OFFSET, "project_name");
        if (version.isEmpty()) {
            throw new InvalidImageException("versão vazia dentro da imagem");
        }
        return new AppDescriptor(version, project);
    }

    private static String readCString(byte[] image, int offset, String field) {
        for (int i = 0; i < FIELD_LENGTH; i++) {
            if (image[offset + i] == 0) {
                return new String(image, offset, i, StandardCharsets.UTF_8);
            }
        }
        throw new InvalidImageException("campo " + field + " sem terminador \\0");
    }
}
