package dev.otafleet.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SignatureVerifierTest {

    private static KeyPair keys;
    private static SignatureVerifier verifier;
    private static final byte[] IMAGE = TestImages.build("1.2.3");

    @BeforeAll
    static void setUp() throws Exception {
        keys = generateP256();
        verifier = SignatureVerifier.fromPem(toPem(keys));
    }

    @Test
    void acceptsValidSignature() throws Exception {
        assertThat(verifier.verify(IMAGE, sign(IMAGE, keys))).isTrue();
    }

    @Test
    void rejectsTamperedImage() throws Exception {
        byte[] signature = sign(IMAGE, keys);
        byte[] tampered = IMAGE.clone();
        tampered[tampered.length - 1] ^= 0x01;
        assertThat(verifier.verify(tampered, signature)).isFalse();
    }

    @Test
    void rejectsSignatureFromAnotherKey() throws Exception {
        assertThat(verifier.verify(IMAGE, sign(IMAGE, generateP256()))).isFalse();
    }

    @Test
    void rejectsMalformedSignature() {
        assertThat(verifier.verify(IMAGE, new byte[] {0x30, 0x02, 0x01})).isFalse();
        assertThat(verifier.verify(IMAGE, new byte[0])).isFalse();
    }

    @Test
    void rejectsInvalidPem() {
        assertThatThrownBy(() -> SignatureVerifier.fromPem("-----BEGIN PUBLIC KEY-----\nnão é base64\n-----END PUBLIC KEY-----"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonP256Key() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp384r1"));
        assertThatThrownBy(() -> SignatureVerifier.fromPem(toPem(gen.generateKeyPair())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("P-256");
    }

    static KeyPair generateP256() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        return gen.generateKeyPair();
    }

    static String toPem(KeyPair pair) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
    }

    static byte[] sign(byte[] data, KeyPair pair) throws Exception {
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(pair.getPrivate());
        signature.update(data);
        return signature.sign();
    }
}
