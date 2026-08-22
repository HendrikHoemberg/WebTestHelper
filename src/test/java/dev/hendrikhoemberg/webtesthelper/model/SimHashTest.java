package dev.hendrikhoemberg.webtesthelper.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SimHashTest {

    private static final String NOT_FOUND = """
            Seite nicht gefunden. Die gewünschte Seite existiert leider nicht.
            Bitte prüfen Sie die Adresse oder kehren Sie zur Startseite zurück.
            """;

    @Test
    void identicalTextHashesIdentically() {
        assertThat(SimHash.of(NOT_FOUND)).isEqualTo(SimHash.of(NOT_FOUND));
    }

    @Test
    void aNotFoundPageEchoingADifferentPathStaysNear() {
        long probe = SimHash.of(NOT_FOUND + " Angefordert: /1f4c-9a2b");
        long other = SimHash.of(NOT_FOUND + " Angefordert: /leistungen-alt");
        assertThat(SimHash.hammingDistance(probe, other)).isLessThanOrEqualTo(12);
    }

    @Test
    void anUnrelatedPageIsFar() {
        String real = """
                Leistungen. Wir beraten mittelständische Unternehmen bei der Digitalisierung
                ihrer Vertriebsprozesse und begleiten die Einführung neuer Systeme.
                """;
        assertThat(SimHash.hammingDistance(SimHash.of(NOT_FOUND), SimHash.of(real)))
                .isGreaterThan(15);
    }

    @Test
    void emptyAndBlankTextHashToZero() {
        assertThat(SimHash.of("")).isZero();
        assertThat(SimHash.of("   \n ")).isZero();
        assertThat(SimHash.of(null)).isZero();
    }

    @Test
    void textShorterThanOneTrigramStillHashesDistinctly() {
        assertThat(SimHash.of("Fehler")).isNotZero();
        assertThat(SimHash.of("Fehler")).isNotEqualTo(SimHash.of("Erfolg"));
    }
}