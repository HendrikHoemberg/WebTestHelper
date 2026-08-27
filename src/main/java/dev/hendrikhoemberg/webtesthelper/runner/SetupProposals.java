package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.crawler.ProbeEvidence;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;

import java.util.ArrayList;
import java.util.List;

/**
 * The pure static mapping from {@link ProbeEvidence} to one {@link CheckProposal} per
 * {@link CheckType}. The probe only decides, per row, whether a check is suggested and which
 * sentence explains that; the screen renders the full catalog and the {@code ui.einrichtung.grund.*}
 * reasons verbatim.
 */
final class SetupProposals {

    private static final String REASON_BASIS = "ui.einrichtung.grund.basis";
    private static final String REASON_MEDIA = "ui.einrichtung.grund.media";
    private static final String REASON_MAPS = "ui.einrichtung.grund.karte";
    private static final String REASON_LANGUAGES = "ui.einrichtung.grund.sprachen";
    private static final String REASON_DOCUMENT = "ui.einrichtung.grund.dokument";
    private static final String REASON_SITEMAP = "ui.einrichtung.grund.sitemap";
    private static final String REASON_HTTPS = "ui.einrichtung.grund.https";
    private static final String REASON_STANDARD = "ui.einrichtung.grund.standard";

    private SetupProposals() {
    }

    static List<CheckProposal> of(ProbeEvidence evidence) {
        List<CheckProposal> checks = new ArrayList<>(CheckType.values().length);

        checks.add(suggested(CheckType.PAGE_STATUS, REASON_BASIS, List.of()));
        checks.add(suggested(CheckType.PAGE_UNREACHABLE, REASON_BASIS, List.of()));
        checks.add(suggested(CheckType.DEAD_LINK, REASON_BASIS, List.of()));
        checks.add(suggested(CheckType.REDIRECT_CHAIN, REASON_BASIS, List.of()));
        checks.add(suggested(CheckType.IMAGE_BROKEN, REASON_BASIS, List.of()));
        checks.add(suggested(CheckType.COOKIE_BANNER, REASON_BASIS, List.of()));

        checks.add(conditional(CheckType.MEDIA_PLAYABLE,
                evidence.reachable() && !evidence.mediaPages().isEmpty(),
                REASON_MEDIA, firstOf(evidence.mediaPages())));
        checks.add(conditional(CheckType.IFRAME_EMBED,
                evidence.reachable() && !evidence.mapPages().isEmpty(),
                REASON_MAPS, firstOf(evidence.mapPages())));
        checks.add(conditional(CheckType.HREFLANG,
                evidence.reachable() && evidence.languages().size() > 1,
                REASON_LANGUAGES, List.of(String.valueOf(evidence.languages().size()))));
        checks.add(conditional(CheckType.LANGUAGE_SWITCHER,
                evidence.reachable() && evidence.languages().size() > 1,
                REASON_LANGUAGES, List.of(String.valueOf(evidence.languages().size()))));
        checks.add(conditional(CheckType.FILE_DOWNLOAD,
                evidence.reachable() && !evidence.documentLinks().isEmpty(),
                REASON_DOCUMENT, firstOf(evidence.documentLinks())));
        checks.add(conditional(CheckType.SITEMAP_CONSISTENCY,
                evidence.reachable() && evidence.sitemapFound(),
                REASON_SITEMAP, List.of()));
        checks.add(conditional(CheckType.TLS_CERT,
                evidence.reachable() && evidence.secure(),
                REASON_HTTPS, List.of()));
        checks.add(conditional(CheckType.MIXED_CONTENT,
                evidence.reachable() && evidence.secure(),
                REASON_HTTPS, List.of()));

        // The other NOISY_BY_DEFAULT check: it ships off and no probe signal justifies turning it
        // on, so it is always present with its reason stated but never suggested.
        checks.add(new CheckProposal(CheckType.CONSOLE_ERRORS, false, REASON_STANDARD, List.of()));

        return List.copyOf(checks);
    }

    private static CheckProposal suggested(CheckType type, String reasonKey, List<String> reasonArgs) {
        return new CheckProposal(type, true, reasonKey, reasonArgs);
    }

    private static CheckProposal conditional(CheckType type, boolean suggested, String reasonKey,
            List<String> reasonArgs) {
        return new CheckProposal(type, suggested, reasonKey, reasonArgs);
    }

    private static List<String> firstOf(List<String> urls) {
        return urls.isEmpty() ? List.of() : List.of(urls.get(0));
    }
}
