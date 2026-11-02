package dev.otafleet.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PausePolicyTest {

    private final PausePolicy policy = new PausePolicy(5, 0.2);

    @Test
    void waitsForMinimumSample() {
        assertThat(policy.shouldPause(0, 4)).isFalse();
    }

    @Test
    void pausesWhenRateExceedsLimit() {
        assertThat(policy.shouldPause(3, 2)).isTrue();
        assertThat(policy.shouldPause(0, 5)).isTrue();
    }

    @Test
    void doesNotPauseAtExactlyTheLimit() {
        assertThat(policy.shouldPause(4, 1)).isFalse();
        assertThat(policy.shouldPause(8, 2)).isFalse();
    }

    @Test
    void doesNotPauseWhenHealthy() {
        assertThat(policy.shouldPause(100, 3)).isFalse();
    }

    @Test
    void explainsReason() {
        assertThat(policy.reason(3, 2)).isEqualTo("Pausada automaticamente: 2 de 5 aparelhos reverteram (40%, limite 20%)");
    }

    @Test
    void validatesParameters() {
        assertThatThrownBy(() -> new PausePolicy(0, 0.2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PausePolicy(5, 1.5)).isInstanceOf(IllegalArgumentException.class);
    }
}
