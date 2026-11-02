package dev.otafleet.core;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Confere assinaturas ECDSA P-256 (DER) sobre o SHA-256 do arquivo inteiro. */
public final class SignatureVerifier {

    private static final String ALGORITHM = "SHA256withECDSA";

    private final PublicKey publicKey;

    private SignatureVerifier(PublicKey publicKey) {
        this.publicKey = publicKey;
    }

    /** Recebe a chave pública em PEM no formato SPKI ({@code -----BEGIN PUBLIC KEY-----}). */
    public static SignatureVerifier fromPem(String pem) {
        String base64 = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        try {
            byte[] der = Base64.getDecoder().decode(base64);
            PublicKey key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
            if (!(key instanceof ECPublicKey ec) || ec.getParams().getCurve().getField().getFieldSize() != 256) {
                throw new IllegalArgumentException("a chave pública precisa ser EC P-256");
            }
            return new SignatureVerifier(key);
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new IllegalArgumentException("chave pública inválida: " + e.getMessage(), e);
        }
    }

    /** Devolve {@code false} tanto para assinatura errada quanto para assinatura malformada. */
    public boolean verify(byte[] data, byte[] derSignature) {
        try {
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initVerify(publicKey);
            signature.update(data);
            return signature.verify(derSignature);
        } catch (SignatureException e) {
            return false;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("falha ao verificar assinatura", e);
        }
    }
}
