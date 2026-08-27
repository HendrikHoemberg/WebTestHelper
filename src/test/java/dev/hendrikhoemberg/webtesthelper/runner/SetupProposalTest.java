package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.crawler.ProbeEvidence;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The proposal mapping: one entry per {@link CheckType}, with {@code suggested} and the reason
 * decided purely by the evidence — no Spring, no browser. Each row of the plan's table is one
 * test here.
 */
class SetupProposalTest {

    private static final List<CheckType> ALWAYS_ON = List.of(
            CheckType.PAGE_STATUS,
            CheckType.PAGE_UNREACHABLE,
            CheckType.DEAD_LINK,
            CheckType.REDIRECT_CHAIN,
            CheckType.IMAGE_BROKEN,
            CheckType.COOKIE_BANNER);

    @Test
    void everyCheckTypeAppearsExactlyOnceWithAReason() {
        List<CheckProposal> proposals = SetupProposals.of(allSignals());

        assertThat(proposals).hasSize(CheckType.values().length);
        assertThat(proposals).extracting(CheckProposal::type)
                .containsExactlyInAnyOrder(CheckType.values())
                .doesNotHaveDuplicates();
        assertThat(proposals).allMatch(p -> p.reasonKey().startsWith("ui.einrichtung.grund."));
    }

    @Test
    void anUnreachableSiteProposesOnlyTheAlwaysOnBaseline() {
        List<CheckProposal> proposals = SetupProposals.of(
                ProbeEvidence.unreachable("Verbindung abgelehnt"));

        assertThat(suggested(proposals))
                .containsExactlyInAnyOrderElementsOf(ALWAYS_ON)
                .hasSize(ALWAYS_ON.size());
    }

    @Test
    void formPagesSuggestContactForm() {
        List<CheckProposal> proposals = SetupProposals.of(reachable(
                List.of("https://example.com/kontakt"), List.of(), List.of(),
                Set.of(), List.of(), false, false));

        assertThat(suggested(proposals)).contains(CheckType.CONTACT_FORM);
        CheckProposal contact = proposalFor(proposals, CheckType.CONTACT_FORM);
        assertThat(contact.suggested()).isTrue();
        assertThat(contact.reasonArgs()).containsExactly("https://example.com/kontakt");
    }

    @Test
    void noFormPagesDoesNotSuggestContactForm() {
        List<CheckProposal> proposals = SetupProposals.of(reachable(
                List.of(), List.of(), List.of(),
                Set.of(), List.of(), false, false));

        assertThat(suggested(proposals)).doesNotContain(CheckType.CONTACT_FORM);
    }

    @Test
    void mediaPagesSuggestMediaPlayable() {
        List<CheckProposal> proposals = SetupProposals.of(reachable(
                List.of(), List.of("https://example.com/medien"), List.of(),
                Set.of(), List.of(), false, false));

        assertThat(suggested(proposals)).contains(CheckType.MEDIA_PLAYABLE);
        CheckProposal media = proposalFor(proposals, CheckType.MEDIA_PLAYABLE);
        assertThat(media.suggested()).isTrue();
        assertThat(media.reasonArgs()).containsExactly("https://example.com/medien");
    }

    @Test
    void mapPagesSuggestIframeEmbed() {
        List<CheckProposal> proposals = SetupProposals.of(reachable(
                List.of(), List.of(), List.of("https://example.com/kontakt"),
                Set.of(), List.of(), false, false));

        assertThat(suggested(proposals)).contains(CheckType.IFRAME_EMBED);
        assertThat(proposalFor(proposals, CheckType.IFRAME_EMBED).reasonArgs())
                .containsExactly("https://example.com/kontakt");
    }

    @Test
    void moreThanOneLanguageSuggestsHreflang() {
        List<CheckProposal> proposals = SetupProposals.of(reachable(
                List.of(), List.of(), List.of(),
                Set.of("de", "en"), List.of(), false, false));

        assertThat(suggested(proposals)).contains(CheckType.HREFLANG, CheckType.LANGUAGE_SWITCHER);
        assertThat(proposalFor(proposals, CheckType.HREFLANG).reasonArgs())
                .containsExactly("2");
        assertThat(proposalFor(proposals, CheckType.LANGUAGE_SWITCHER).reasonArgs())
                .containsExactly("2");
    }

    @Test
    void aSingleLanguageDoesNotSuggestHreflang() {
        List<CheckProposal> proposals = SetupProposals.of(reachable(
                List.of(), List.of(), List.of(),
                Set.of("de"), List.of(), false, false));

        assertThat(suggested(proposals)).doesNotContain(CheckType.HREFLANG, CheckType.LANGUAGE_SWITCHER);
    }

    @Test
    void documentLinksSuggestFileDownload() {
        List<CheckProposal> proposals = SetupProposals.of(reachable(
                List.of(), List.of(), List.of(),
                Set.of(), List.of("https://example.com/dateien/handbuch.pdf"), false, false));

        assertThat(suggested(proposals)).contains(CheckType.FILE_DOWNLOAD);
        assertThat(proposalFor(proposals, CheckType.FILE_DOWNLOAD).reasonArgs())
                .containsExactly("https://example.com/dateien/handbuch.pdf");
    }

    @Test
    void aSitemapFoundTurnsSitemapConsistencyOn() {
        List<CheckProposal> proposals = SetupProposals.of(reachable(
                List.of(), List.of(), List.of(),
                Set.of(), List.of(), true, false));

        assertThat(suggested(proposals)).contains(CheckType.SITEMAP_CONSISTENCY);
        assertThat(proposalFor(proposals, CheckType.SITEMAP_CONSISTENCY).reasonArgs()).isEmpty();
    }

    @Test
    void aSecureSiteSuggestsTlsCertAndMixedContent() {
        List<CheckProposal> proposals = SetupProposals.of(reachable(
                List.of(), List.of(), List.of(),
                Set.of(), List.of(), false, true));

        assertThat(suggested(proposals)).contains(CheckType.TLS_CERT, CheckType.MIXED_CONTENT);
        assertThat(proposalFor(proposals, CheckType.TLS_CERT).reasonArgs()).isEmpty();
        assertThat(proposalFor(proposals, CheckType.MIXED_CONTENT).reasonArgs()).isEmpty();
    }

    @Test
    void consoleErrorsIsPresentButNeverSuggested() {
        List<CheckProposal> proposals = SetupProposals.of(allSignals());

        CheckProposal console = proposalFor(proposals, CheckType.CONSOLE_ERRORS);
        assertThat(console.suggested()).isFalse();
        assertThat(console.reasonKey()).startsWith("ui.einrichtung.grund.");
    }

    @Test
    void buttonReachabilityIsPresentWithConsequencesStatedButNeverSuggested() {
        List<CheckProposal> proposals = SetupProposals.of(allSignals());

        CheckProposal button = proposalFor(proposals, CheckType.BUTTON_REACHABILITY);
        assertThat(button.suggested()).isFalse();
        assertThat(button.reasonKey()).isEqualTo("ui.einrichtung.grund.klickt");
        assertThat(button.reasonArgs()).isEmpty();
    }

    private static List<CheckType> suggested(List<CheckProposal> proposals) {
        return proposals.stream().filter(CheckProposal::suggested).map(CheckProposal::type).toList();
    }

    private static CheckProposal proposalFor(List<CheckProposal> proposals, CheckType type) {
        return proposals.stream().filter(p -> p.type() == type).findFirst().orElseThrow();
    }

    private static ProbeEvidence allSignals() {
        return new ProbeEvidence(true, null,
                List.of("https://example.com/"),
                List.of("https://example.com/kontakt"),
                List.of("https://example.com/medien"),
                List.of("https://example.com/kontakt"),
                Set.of("de", "en"),
                List.of("https://example.com/dateien/handbuch.pdf"),
                true, true);
    }

    private static ProbeEvidence reachable(List<String> formPages, List<String> mediaPages,
            List<String> mapPages, Set<String> languages, List<String> documentLinks,
            boolean sitemapFound, boolean secure) {
        return new ProbeEvidence(true, null,
                List.of("https://example.com/"), formPages, mediaPages, mapPages,
                languages, documentLinks, sitemapFound, secure);
    }
}
