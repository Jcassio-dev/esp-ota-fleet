package dev.otafleet.core;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Escolhe qual versão oferecer a um aparelho no check-in. */
public record OfferPolicy(int minBatteryPercent, int maxDownloadRetries) {

    /** Uma versão ACTIVE candidata a ser oferecida. */
    public record Candidate(long releaseId, SemVer version, int rolloutPercent) {
    }

    /** O que já aconteceu entre este aparelho e uma versão. */
    public record AttemptSnapshot(AttemptState state, int failureCount) {
    }

    /**
     * @param battery  bateria informada pelo aparelho, ou {@code null} se desconhecida
     * @param attempts tentativas do aparelho, indexadas pelo id da versão
     */
    public Optional<Candidate> choose(String deviceId,
                                      SemVer currentVersion,
                                      Integer battery,
                                      List<Candidate> activeReleases,
                                      Map<Long, AttemptSnapshot> attempts) {
        if (battery != null && battery < minBatteryPercent) {
            return Optional.empty();
        }
        boolean midSwap = attempts.values().stream().anyMatch(a -> a.state() == AttemptState.INSTALLED);
        if (midSwap) {
            return Optional.empty();
        }
        return activeReleases.stream()
                .sorted(Comparator.comparing(Candidate::version).reversed())
                .filter(c -> c.version().isNewerThan(currentVersion))
                .filter(c -> RolloutBucket.isIncluded(deviceId, c.releaseId(), c.rolloutPercent()))
                .filter(c -> isEligible(attempts.get(c.releaseId())))
                .findFirst();
    }

    private boolean isEligible(AttemptSnapshot attempt) {
        if (attempt == null) {
            return true;
        }
        if (attempt.state().isFinal()) {
            return false;
        }
        return !(attempt.state() == AttemptState.FAILED && attempt.failureCount() >= maxDownloadRetries);
    }
}
