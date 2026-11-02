package dev.otafleet.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Distribui aparelhos em 100 baldes de forma determinística por versão.
 *
 * <p>O mesmo aparelho cai sempre no mesmo balde para a mesma versão, então aumentar o
 * rollout de 10% para 20% só acrescenta aparelhos, nunca troca quem já recebeu.
 * Incluir o id da versão evita que os mesmos aparelhos sejam sempre os primeiros.
 */
public final class RolloutBucket {

    private RolloutBucket() {
    }

    public static int of(String deviceId, long releaseId) {
        byte[] hash = sha256((deviceId + ":" + releaseId).getBytes(StandardCharsets.UTF_8));
        long first4 = ((hash[0] & 0xFFL) << 24)
                | ((hash[1] & 0xFFL) << 16)
                | ((hash[2] & 0xFFL) << 8)
                | (hash[3] & 0xFFL);
        return (int) (first4 % 100);
    }

    public static boolean isIncluded(String deviceId, long releaseId, int rolloutPercent) {
        return of(deviceId, releaseId) < rolloutPercent;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível na JVM", e);
        }
    }
}
