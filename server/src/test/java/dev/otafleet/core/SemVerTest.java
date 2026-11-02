package dev.otafleet.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SemVerTest {

    @Test
    void parsesValidVersion() {
        assertThat(SemVer.parse("1.10.3")).isEqualTo(new SemVer(1, 10, 3));
        assertThat(SemVer.parse("0.0.0").toString()).isEqualTo("0.0.0");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1", "1.2", "1.2.3.4", "v1.2.3", "01.2.3", "1.2.3-beta", "1.2.x", " 1.2.3", "-1.2.3"})
    void rejectsInvalidFormats(String text) {
        assertThat(SemVer.tryParse(text)).isEmpty();
        assertThatThrownBy(() -> SemVer.parse(text)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNull() {
        assertThat(SemVer.tryParse(null)).isEmpty();
    }

    @Test
    void comparesNumericallyNotLexically() {
        assertThat(SemVer.parse("1.10.0").isNewerThan(SemVer.parse("1.9.9"))).isTrue();
        assertThat(SemVer.parse("2.0.0").isNewerThan(SemVer.parse("1.99.99"))).isTrue();
        assertThat(SemVer.parse("1.0.1").isNewerThan(SemVer.parse("1.0.0"))).isTrue();
        assertThat(SemVer.parse("1.0.0").isNewerThan(SemVer.parse("1.0.0"))).isFalse();
        assertThat(SemVer.parse("1.0.0").isNewerThan(SemVer.parse("1.0.1"))).isFalse();
    }
}
