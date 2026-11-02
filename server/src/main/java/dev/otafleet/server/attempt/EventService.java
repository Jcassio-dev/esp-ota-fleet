package dev.otafleet.server.attempt;

import dev.otafleet.core.AttemptState;
import dev.otafleet.core.AttemptStateMachine;
import dev.otafleet.core.AttemptStateMachine.Decision;
import dev.otafleet.core.PausePolicy;
import dev.otafleet.server.device.DeviceRepository;
import dev.otafleet.server.release.Release;
import dev.otafleet.server.release.ReleaseRepository;
import dev.otafleet.server.release.ReleaseStatus;
import dev.otafleet.server.web.ApiException;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Aplica os eventos que o aparelho reporta sobre uma tentativa de atualização. */
@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final UpdateAttemptRepository attempts;
    private final ReleaseRepository releases;
    private final DeviceRepository devices;
    private final PausePolicy pausePolicy;
    private final Clock clock;

    public EventService(UpdateAttemptRepository attempts, ReleaseRepository releases, DeviceRepository devices,
                        PausePolicy pausePolicy, Clock clock) {
        this.attempts = attempts;
        this.releases = releases;
        this.devices = devices;
        this.pausePolicy = pausePolicy;
        this.clock = clock;
    }

    @Transactional
    public Decision apply(String deviceId, long releaseId, AttemptState reported, String detail) {
        UpdateAttempt attempt = attempts.findByDeviceIdAndReleaseId(deviceId, releaseId)
                .orElseThrow(() -> ApiException.notFound(
                        "Nenhuma tentativa da versão " + releaseId + " para o aparelho " + deviceId));

        Decision decision = AttemptStateMachine.decide(attempt.getState(), reported);
        switch (decision) {
            case DUPLICATE -> {
                return decision;
            }
            case REJECT -> throw ApiException.conflict(
                    "Transição inválida: " + attempt.getState() + " → " + reported);
            case APPLY -> attempt.apply(reported, detail, clock.instant());
        }
        log.info("Aparelho {} versão {}: {}{}", deviceId, releaseId, reported,
                detail == null ? "" : " (" + detail + ")");

        if (reported == AttemptState.CONFIRMED) {
            Release release = releases.getReferenceById(releaseId);
            devices.findById(deviceId).ifPresent(d -> d.confirmVersion(release.getVersion()));
        }
        if (reported == AttemptState.CONFIRMED || reported == AttemptState.ROLLED_BACK) {
            attempts.flush();
            evaluatePause(releaseId);
        }
        return decision;
    }

    private void evaluatePause(long releaseId) {
        Release release = releases.getReferenceById(releaseId);
        if (release.getStatus() != ReleaseStatus.ACTIVE) {
            return;
        }
        long confirmed = attempts.countByReleaseIdAndState(releaseId, AttemptState.CONFIRMED);
        long rolledBack = attempts.countByReleaseIdAndState(releaseId, AttemptState.ROLLED_BACK);
        if (pausePolicy.shouldPause(confirmed, rolledBack)) {
            release.pause(pausePolicy.reason(confirmed, rolledBack));
            log.warn("Versão {} pausada: {}", release.getVersion(), release.getPauseReason());
        }
    }
}
