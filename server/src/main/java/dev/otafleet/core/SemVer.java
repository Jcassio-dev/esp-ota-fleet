package dev.otafleet.core;

import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Versão no formato estrito MAJOR.MINOR.PATCH, sem pré-release nem metadados. */
public record SemVer(int major, int minor, int patch) implements Comparable<SemVer> {

    private static final Pattern FORMAT = Pattern.compile("^(0|[1-9]\\d{0,8})\\.(0|[1-9]\\d{0,8})\\.(0|[1-9]\\d{0,8})$");

    private static final Comparator<SemVer> ORDER = Comparator
            .comparingInt(SemVer::major)
            .thenComparingInt(SemVer::minor)
            .thenComparingInt(SemVer::patch);

    public SemVer {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("componentes da versão não podem ser negativos");
        }
    }

    public static SemVer parse(String text) {
        return tryParse(text).orElseThrow(
                () -> new IllegalArgumentException("versão fora do formato MAJOR.MINOR.PATCH: " + text));
    }

    public static Optional<SemVer> tryParse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = FORMAT.matcher(text);
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(new SemVer(
                Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3))));
    }

    public boolean isNewerThan(SemVer other) {
        return compareTo(other) > 0;
    }

    @Override
    public int compareTo(SemVer other) {
        return ORDER.compare(this, other);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
