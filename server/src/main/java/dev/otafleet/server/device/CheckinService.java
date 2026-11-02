package dev.otafleet.server.device;

import dev.otafleet.core.OfferPolicy;
import dev.otafleet.core.OfferPolicy.AttemptSnapshot;
import dev.otafleet.core.OfferPolicy.Candidate;
import dev.otafleet.core.SemVer;
import dev.otafleet.server.attempt.UpdateAttempt;
import dev.otafleet.server.attempt.UpdateAttemptRepository;
import dev.otafleet.server.config.OtaFleetProperties;
import dev.otafleet.server.release.Release;
import dev.otafleet.server.release.ReleaseRepository;
import dev.otafleet.server.release.ReleaseStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CheckinService {

    private static final Logger log = LoggerFactory.getLogger(CheckinService.class);

    public record Offer(long releaseId, String version, long size, String sha256, String signature,
                        String url, int chunkSize) {
    }

    private final DeviceRepository devices;
    private final ReleaseRepository releases;
    private final UpdateAttemptRepository attempts;
    private final OfferPolicy policy;
    private final OtaFleetProperties props;
    private final Clock clock;

    public CheckinService(DeviceRepository devices, ReleaseRepository releases, UpdateAttemptRepository attempts,
                          OfferPolicy policy, OtaFleetProperties props, Clock clock) {
        this.devices = devices;
        this.releases = releases;
        this.attempts = attempts;
        this.policy = policy;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public Optional<Offer> checkin(String deviceId, SemVer version, Integer battery) {
        Instant now = clock.instant();
        Device device = devices.findById(deviceId)
                .orElseGet(() -> devices.save(new Device(deviceId, version.toString(), now)));
        device.recordCheckin(version.toString(), battery, now);

        Map<Long, Release> active = releases.findByStatus(ReleaseStatus.ACTIVE).stream()
                .collect(Collectors.toMap(Release::getId, Function.identity()));
        List<Candidate> candidates = active.values().stream()
                .map(r -> new Candidate(r.getId(), r.semVer(), r.getRolloutPercent()))
                .toList();
        Map<Long, UpdateAttempt> byRelease = attempts.findByDeviceId(deviceId).stream()
                .collect(Collectors.toMap(UpdateAttempt::getReleaseId, Function.identity()));
        Map<Long, AttemptSnapshot> snapshots = byRelease.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        e -> new AttemptSnapshot(e.getValue().getState(), e.getValue().getFailureCount())));

        return policy.choose(deviceId, version, battery, candidates, snapshots).map(chosen -> {
            Release release = active.get(chosen.releaseId());
            UpdateAttempt attempt = byRelease.get(release.getId());
            if (attempt == null) {
                attempts.save(UpdateAttempt.offered(deviceId, release.getId(), now));
            } else {
                attempt.reoffer(now);
            }
            log.info("Oferecendo {} para {} (atual {})", release.getVersion(), deviceId, version);
            return new Offer(release.getId(), release.getVersion(), release.getSize(), release.getSha256(),
                    release.getSignature(), "/api/releases/" + release.getId() + "/firmware", props.chunkSize());
        });
    }
}
