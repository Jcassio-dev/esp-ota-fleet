package dev.otafleet.core;

import static org.assertj.core.api.Assertions.assertThat;

import dev.otafleet.core.OfferPolicy.AttemptSnapshot;
import dev.otafleet.core.OfferPolicy.Candidate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OfferPolicyTest {

    private static final String DEVICE = "esp32-aabbccddeeff";
    private static final SemVer CURRENT = SemVer.parse("1.0.0");

    private final OfferPolicy policy = new OfferPolicy(25, 3);

    private static Candidate release(long id, String version) {
        return new Candidate(id, SemVer.parse(version), 100);
    }

    @Test
    void offersNewestEligibleVersion() {
        var releases = List.of(release(1, "1.1.0"), release(2, "1.3.0"), release(3, "1.2.0"));
        assertThat(policy.choose(DEVICE, CURRENT, 80, releases, Map.of()))
                .get().extracting(Candidate::releaseId).isEqualTo(2L);
    }

    @Test
    void skipsVersionsNotNewer() {
        var releases = List.of(release(1, "1.0.0"), release(2, "0.9.0"));
        assertThat(policy.choose(DEVICE, CURRENT, 80, releases, Map.of())).isEmpty();
    }

    @Test
    void noOfferWhenBatteryLow() {
        assertThat(policy.choose(DEVICE, CURRENT, 24, List.of(release(1, "1.1.0")), Map.of())).isEmpty();
        assertThat(policy.choose(DEVICE, CURRENT, 25, List.of(release(1, "1.1.0")), Map.of())).isPresent();
    }

    @Test
    void unknownBatteryDoesNotBlock() {
        assertThat(policy.choose(DEVICE, CURRENT, null, List.of(release(1, "1.1.0")), Map.of())).isPresent();
    }

    @Test
    void respectsRolloutBucket() {
        int bucket = RolloutBucket.of(DEVICE, 1);
        var outside = new Candidate(1, SemVer.parse("1.1.0"), bucket);
        var inside = new Candidate(1, SemVer.parse("1.1.0"), bucket + 1);
        assertThat(policy.choose(DEVICE, CURRENT, null, List.of(outside), Map.of())).isEmpty();
        assertThat(policy.choose(DEVICE, CURRENT, null, List.of(inside), Map.of())).isPresent();
    }

    @Test
    void neverReoffersRolledBackOrConfirmed() {
        var releases = List.of(release(1, "1.1.0"), release(2, "1.2.0"));
        var attempts = Map.of(2L, new AttemptSnapshot(AttemptState.ROLLED_BACK, 0));
        assertThat(policy.choose(DEVICE, CURRENT, null, releases, attempts))
                .get().extracting(Candidate::releaseId).isEqualTo(1L);

        var confirmed = Map.of(1L, new AttemptSnapshot(AttemptState.CONFIRMED, 0));
        assertThat(policy.choose(DEVICE, CURRENT, null, List.of(release(1, "1.1.0")), confirmed)).isEmpty();
    }

    @Test
    void stopsRetryingAfterTooManyFailures() {
        var releases = List.of(release(1, "1.1.0"));
        assertThat(policy.choose(DEVICE, CURRENT, null, releases,
                Map.of(1L, new AttemptSnapshot(AttemptState.FAILED, 2)))).isPresent();
        assertThat(policy.choose(DEVICE, CURRENT, null, releases,
                Map.of(1L, new AttemptSnapshot(AttemptState.FAILED, 3)))).isEmpty();
    }

    @Test
    void offersNothingWhileSwapInProgress() {
        var releases = List.of(release(1, "1.1.0"), release(2, "1.2.0"));
        var attempts = Map.of(1L, new AttemptSnapshot(AttemptState.INSTALLED, 0));
        assertThat(policy.choose(DEVICE, CURRENT, null, releases, attempts)).isEmpty();
    }

    @Test
    void reoffersInterruptedDownload() {
        var attempts = Map.of(1L, new AttemptSnapshot(AttemptState.DOWNLOADING, 0));
        assertThat(policy.choose(DEVICE, CURRENT, null, List.of(release(1, "1.1.0")), attempts)).isPresent();
    }
}
