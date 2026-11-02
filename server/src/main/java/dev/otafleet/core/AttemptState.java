package dev.otafleet.core;

/** Estado de uma tentativa de atualização (um aparelho × uma versão). */
public enum AttemptState {
    OFFERED,
    DOWNLOADING,
    INSTALLED,
    CONFIRMED,
    ROLLED_BACK,
    FAILED;

    public boolean isFinal() {
        return this == CONFIRMED || this == ROLLED_BACK;
    }
}
