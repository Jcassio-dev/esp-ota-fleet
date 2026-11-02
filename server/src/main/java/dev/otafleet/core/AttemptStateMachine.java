package dev.otafleet.core;

import static dev.otafleet.core.AttemptState.DOWNLOADING;
import static dev.otafleet.core.AttemptState.OFFERED;

/**
 * Decide o que fazer com um evento reportado pelo aparelho.
 *
 * <p>CONFIRMED e ROLLED_BACK valem a partir de qualquer estado não final, porque os
 * eventos intermediários podem se perder no reboot da troca de partição.
 */
public final class AttemptStateMachine {

    public enum Decision { APPLY, DUPLICATE, REJECT }

    private AttemptStateMachine() {
    }

    public static Decision decide(AttemptState current, AttemptState reported) {
        if (reported == current) {
            return Decision.DUPLICATE;
        }
        if (current.isFinal() || reported == OFFERED) {
            return Decision.REJECT;
        }
        boolean allowed = switch (reported) {
            case DOWNLOADING -> current == OFFERED;
            case INSTALLED, FAILED -> current == OFFERED || current == DOWNLOADING;
            case CONFIRMED, ROLLED_BACK -> true;
            case OFFERED -> false;
        };
        return allowed ? Decision.APPLY : Decision.REJECT;
    }
}
