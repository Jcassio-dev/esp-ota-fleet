package dev.otafleet.core;

import static dev.otafleet.core.AttemptState.CONFIRMED;
import static dev.otafleet.core.AttemptState.DOWNLOADING;
import static dev.otafleet.core.AttemptState.FAILED;
import static dev.otafleet.core.AttemptState.INSTALLED;
import static dev.otafleet.core.AttemptState.OFFERED;
import static dev.otafleet.core.AttemptState.ROLLED_BACK;
import static dev.otafleet.core.AttemptStateMachine.Decision.APPLY;
import static dev.otafleet.core.AttemptStateMachine.Decision.DUPLICATE;
import static dev.otafleet.core.AttemptStateMachine.Decision.REJECT;
import static org.assertj.core.api.Assertions.assertThat;

import dev.otafleet.core.AttemptStateMachine.Decision;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AttemptStateMachineTest {

    /** Tabela completa: linha = estado atual, coluna = estado reportado. */
    private static final Map<AttemptState, Map<AttemptState, Decision>> TABLE = new EnumMap<>(AttemptState.class);

    static {
        //       reportado:  OFFERED    DOWNLOADING INSTALLED  CONFIRMED  ROLLED_BACK FAILED
        row(OFFERED,         DUPLICATE, APPLY,      APPLY,     APPLY,     APPLY,      APPLY);
        row(DOWNLOADING,     REJECT,    DUPLICATE,  APPLY,     APPLY,     APPLY,      APPLY);
        row(INSTALLED,       REJECT,    REJECT,     DUPLICATE, APPLY,     APPLY,      REJECT);
        row(FAILED,          REJECT,    REJECT,     REJECT,    APPLY,     APPLY,      DUPLICATE);
        row(CONFIRMED,       REJECT,    REJECT,     REJECT,    DUPLICATE, REJECT,     REJECT);
        row(ROLLED_BACK,     REJECT,    REJECT,     REJECT,    REJECT,    DUPLICATE,  REJECT);
    }

    private static void row(AttemptState current, Decision... decisions) {
        Map<AttemptState, Decision> row = new EnumMap<>(AttemptState.class);
        AttemptState[] reported = {OFFERED, DOWNLOADING, INSTALLED, CONFIRMED, ROLLED_BACK, FAILED};
        for (int i = 0; i < reported.length; i++) {
            row.put(reported[i], decisions[i]);
        }
        TABLE.put(current, row);
    }

    @Test
    void coversEveryTransition() {
        assertThat(TABLE).hasSize(AttemptState.values().length);
        for (AttemptState current : AttemptState.values()) {
            for (AttemptState reported : AttemptState.values()) {
                assertThat(AttemptStateMachine.decide(current, reported))
                        .as("%s -> %s", current, reported)
                        .isEqualTo(TABLE.get(current).get(reported));
            }
        }
    }

    @Test
    void onlyConfirmedAndRolledBackAreFinal() {
        assertThat(CONFIRMED.isFinal()).isTrue();
        assertThat(ROLLED_BACK.isFinal()).isTrue();
        assertThat(OFFERED.isFinal()).isFalse();
        assertThat(DOWNLOADING.isFinal()).isFalse();
        assertThat(INSTALLED.isFinal()).isFalse();
        assertThat(FAILED.isFinal()).isFalse();
    }
}
