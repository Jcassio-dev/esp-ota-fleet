package dev.otafleet.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RolloutBucketTest {

    @Test
    void isDeterministic() {
        int first = RolloutBucket.of("esp32-a1b2c3d4e5f6", 7);
        for (int i = 0; i < 10; i++) {
            assertThat(RolloutBucket.of("esp32-a1b2c3d4e5f6", 7)).isEqualTo(first);
        }
    }

    @Test
    void staysWithinRange() {
        for (int i = 0; i < 1000; i++) {
            assertThat(RolloutBucket.of("device-" + i, 1)).isBetween(0, 99);
        }
    }

    @Test
    void matchesKnownValue() {
        // SHA-256("device-1:1") começa com 0x006def97 = 7204759; 7204759 % 100 = 59.
        // Fixado para detectar mudança acidental no algoritmo, que reembaralharia a frota.
        assertThat(RolloutBucket.of("device-1", 1)).isEqualTo(59);
    }

    @Test
    void distributesReasonably() {
        int[] counts = new int[100];
        int devices = 20_000;
        for (int i = 0; i < devices; i++) {
            counts[RolloutBucket.of("esp32-" + Integer.toHexString(i), 3)]++;
        }
        for (int count : counts) {
            // esperado 200 por balde; tolerância generosa para não ficar instável
            assertThat(count).isBetween(130, 270);
        }
    }

    @Test
    void rolloutPercentIncludesRoughlyThatShare() {
        int included = 0;
        int devices = 10_000;
        for (int i = 0; i < devices; i++) {
            if (RolloutBucket.isIncluded("dev-" + i, 9, 20)) {
                included++;
            }
        }
        assertThat(included).isBetween(1800, 2200);
    }

    @Test
    void growingRolloutOnlyAddsDevices() {
        for (int i = 0; i < 500; i++) {
            String id = "dev-" + i;
            if (RolloutBucket.isIncluded(id, 4, 10)) {
                assertThat(RolloutBucket.isIncluded(id, 4, 30)).isTrue();
            }
        }
    }

    @Test
    void zeroPercentIncludesNobodyAndHundredIncludesEverybody() {
        for (int i = 0; i < 200; i++) {
            assertThat(RolloutBucket.isIncluded("d" + i, 1, 0)).isFalse();
            assertThat(RolloutBucket.isIncluded("d" + i, 1, 100)).isTrue();
        }
    }
}
