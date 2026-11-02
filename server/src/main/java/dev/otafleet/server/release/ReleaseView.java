package dev.otafleet.server.release;

import dev.otafleet.core.AttemptState;
import java.time.Instant;
import java.util.Map;

/** Versão publicada, com a contagem de tentativas por estado. */
public record ReleaseView(
        Long id,
        String version,
        String projectName,
        long size,
        String sha256,
        int rolloutPercent,
        ReleaseStatus status,
        String pauseReason,
        Instant createdAt,
        Map<AttemptState, Long> counts) {

    static ReleaseView of(Release r, Map<AttemptState, Long> counts) {
        return new ReleaseView(r.getId(), r.getVersion(), r.getProjectName(), r.getSize(), r.getSha256(),
                r.getRolloutPercent(), r.getStatus(), r.getPauseReason(), r.getCreatedAt(), counts);
    }
}
