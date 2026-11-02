package dev.otafleet.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * Imagem assinada pela CLI em Node ({@code cli/scripts/interop-fixture.js}) precisa ser
 * aceita pelo Java: mesmo formato de chave (SPKI), de assinatura (DER) e de hash.
 */
class CliInteropTest {

    @Test
    void javaVerifiesImageSignedByCli() throws Exception {
        byte[] image = resource("firmware.bin");
        byte[] signature = Base64.getDecoder().decode(text("firmware.sig"));
        SignatureVerifier verifier = SignatureVerifier.fromPem(text("public.pem"));

        assertThat(verifier.verify(image, signature)).isTrue();

        image[image.length - 1] ^= 0x01;
        assertThat(verifier.verify(image, signature)).isFalse();
    }

    @Test
    void javaReadsSameDescriptorAndHashAsCli() throws Exception {
        byte[] image = resource("firmware.bin");
        assertThat(FirmwareImage.readDescriptor(image))
                .isEqualTo(new FirmwareImage.AppDescriptor("1.2.3", "interop"));
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        assertThat(sha).isEqualTo(text("firmware.sha256"));
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = CliInteropTest.class.getResourceAsStream("/interop/" + name)) {
            if (in == null) {
                throw new IllegalStateException("fixture ausente; rode: node cli/scripts/interop-fixture.js");
            }
            return in.readAllBytes();
        }
    }

    private static String text(String name) throws IOException {
        return new String(resource(name), StandardCharsets.UTF_8).trim();
    }
}
