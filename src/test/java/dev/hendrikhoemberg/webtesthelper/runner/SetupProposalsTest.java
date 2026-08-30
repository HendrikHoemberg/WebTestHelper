package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.crawler.ProbeEvidence;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SetupProposalsTest {

    private static final ProbeEvidence RECHBAR_LEER = new ProbeEvidence(true, null,
            List.of("https://acme.example.com/"),
            List.of(), List.of(), List.of(), Set.of(), List.of(), false, false);

    @Test
    void emptyEvidenceCarriesParameterFreeNegativeReasons() {
        List<CheckProposal> checks = SetupProposals.of(RECHBAR_LEER);

        assertThat(keyOf(checks, CheckType.CONTACT_FORM)).isEqualTo("ui.einrichtung.grund.formular.kein");
        assertThat(keyOf(checks, CheckType.MEDIA_PLAYABLE)).isEqualTo("ui.einrichtung.grund.media.kein");
        assertThat(keyOf(checks, CheckType.IFRAME_EMBED)).isEqualTo("ui.einrichtung.grund.karte.kein");
        assertThat(keyOf(checks, CheckType.HREFLANG)).isEqualTo("ui.einrichtung.grund.sprachen.kein");
        assertThat(keyOf(checks, CheckType.LANGUAGE_SWITCHER)).isEqualTo("ui.einrichtung.grund.sprachen.kein");
        assertThat(keyOf(checks, CheckType.FILE_DOWNLOAD)).isEqualTo("ui.einrichtung.grund.dokument.kein");
        assertThat(keyOf(checks, CheckType.SITEMAP_CONSISTENCY)).isEqualTo("ui.einrichtung.grund.sitemap.kein");
        assertThat(keyOf(checks, CheckType.TLS_CERT)).isEqualTo("ui.einrichtung.grund.https.nicht");
        assertThat(keyOf(checks, CheckType.MIXED_CONTENT)).isEqualTo("ui.einrichtung.grund.https.nicht");
    }

    @Test
    void negativeReasonKeysNeverCarryArguments() {
        List<CheckProposal> checks = SetupProposals.of(RECHBAR_LEER);

        for (CheckProposal check : checks) {
            if (check.reasonKey().endsWith(".kein") || check.reasonKey().endsWith(".nicht")) {
                assertThat(check.reasonArgs()).isEmpty();
            }
        }
    }

    @Test
    void richEvidenceKeepsTheOriginalSuggestedReasons() {
        ProbeEvidence evidence = new ProbeEvidence(true, null,
                List.of("https://acme.example.com/"),
                List.of("https://acme.example.com/kontakt"),
                List.of("https://acme.example.com/medien"),
                List.of("https://acme.example.com/karte"),
                Set.of("de", "en"),
                List.of("https://acme.example.com/preisliste.pdf"),
                true, true);

        List<CheckProposal> checks = SetupProposals.of(evidence);

        assertThat(keyOf(checks, CheckType.CONTACT_FORM)).isEqualTo("ui.einrichtung.grund.formular");
        assertThat(keyOf(checks, CheckType.MEDIA_PLAYABLE)).isEqualTo("ui.einrichtung.grund.media");
        assertThat(keyOf(checks, CheckType.IFRAME_EMBED)).isEqualTo("ui.einrichtung.grund.karte");
        assertThat(keyOf(checks, CheckType.HREFLANG)).isEqualTo("ui.einrichtung.grund.sprachen");
        assertThat(keyOf(checks, CheckType.FILE_DOWNLOAD)).isEqualTo("ui.einrichtung.grund.dokument");
        assertThat(keyOf(checks, CheckType.SITEMAP_CONSISTENCY)).isEqualTo("ui.einrichtung.grund.sitemap");
        assertThat(keyOf(checks, CheckType.TLS_CERT)).isEqualTo("ui.einrichtung.grund.https");
        assertThat(keyOf(checks, CheckType.MIXED_CONTENT)).isEqualTo("ui.einrichtung.grund.https");
    }

    private static String keyOf(List<CheckProposal> checks, CheckType type) {
        return checks.stream()
                .filter(c -> c.type() == type)
                .findFirst()
                .map(CheckProposal::reasonKey)
                .orElseThrow(() -> new AssertionError("Kein Vorschlag für " + type));
    }
}
