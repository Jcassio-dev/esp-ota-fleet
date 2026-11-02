package dev.otafleet.core;

/**
 * Decide quando pausar um lançamento por excesso de rollbacks.
 *
 * <p>Só conta resultados finais (CONFIRMED e ROLLED_BACK). Falha de download fica de fora:
 * indica problema de rede, não de firmware.
 */
public record PausePolicy(int minSample, double maxRollbackRate) {

    public PausePolicy {
        if (minSample < 1) {
            throw new IllegalArgumentException("minSample deve ser pelo menos 1");
        }
        if (maxRollbackRate < 0 || maxRollbackRate > 1) {
            throw new IllegalArgumentException("maxRollbackRate deve estar entre 0 e 1");
        }
    }

    public boolean shouldPause(long confirmed, long rolledBack) {
        long sample = confirmed + rolledBack;
        if (sample < minSample) {
            return false;
        }
        return (double) rolledBack / sample > maxRollbackRate;
    }

    public String reason(long confirmed, long rolledBack) {
        long sample = confirmed + rolledBack;
        long percent = Math.round(100.0 * rolledBack / sample);
        return "Pausada automaticamente: %d de %d aparelhos reverteram (%d%%, limite %d%%)"
                .formatted(rolledBack, sample, percent, Math.round(maxRollbackRate * 100));
    }
}
