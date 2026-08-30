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
public final class SetupProposals {

    private static final String REASON_BASIS = "ui.einrichtung.grund.basis";
    private static final String REASON_MEDIA = "ui.einrichtung.grund.media";
    private static final String REASON_MAPS = "ui.einrichtung.grund.karte";
    private static final String REASON_LANGUAGES = "ui.einrichtung.grund.sprachen";
    private static final String REASON_DOCUMENT = "ui.einrichtung.grund.dokument";
    private static final String REASON_SITEMAP = "ui.einrichtung.grund.sitemap";
    private static final String REASON_HTTPS = "ui.einrichtung.grund.https";
    private static final String REASON_STANDARD = "ui.einrichtung.grund.standard";
    private static final String REASON_KLICKT = "ui.einrichtung.grund.klickt";
    private static final String REASON_FORMULAR = "ui.einrichtung.grund.formular";
    private static final String REASON_FORMULAR_KEIN = "ui.einrichtung.grund.formular.kein";
    private static final String REASON_MEDIA_KEIN = "ui.einrichtung.grund.media.kein";
    private static final String REASON_MAPS_KEIN = "ui.einrichtung.grund.karte.kein";
    private static final String REASON_LANGUAGES_KEIN = "ui.einrichtung.grund.sprachen.kein";
    private static final String REASON_DOCUMENT_KEIN = "ui.einrichtung.grund.dokument.kein";
    private static final String REASON_SITEMAP_KEIN = "ui.einrichtung.grund.sitemap.kein";
    private static final String REASON_HTTPS_NICHT = "ui.einrichtung.grund.https.nicht";

    private static final List<String> KEINE_ARGS = List.of();

    private SetupProposals() {
    }

    public static List<CheckProposal> of(ProbeEvidence evidence) {
        List<CheckProposal> checks = new ArrayList<>(CheckType.values().length);

        checks.add(suggested(CheckType.PAGE_STATUS, REASON_BASIS, KEINE_ARGS));
        checks.add(suggested(CheckType.PAGE_UNREACHABLE, REASON_BASIS, KEINE_ARGS));
        checks.add(suggested(CheckType.DEAD_LINK, REASON_BASIS, KEINE_ARGS));
        checks.add(suggested(CheckType.REDIRECT_CHAIN, REASON_BASIS, KEINE_ARGS));
        checks.add(suggested(CheckType.IMAGE_BROKEN, REASON_BASIS, KEINE_ARGS));
        checks.add(suggested(CheckType.COOKIE_BANNER, REASON_BASIS, KEINE_ARGS));

        boolean formular = evidence.reachable() && !evidence.formPages().isEmpty();
        checks.add(conditional(CheckType.CONTACT_FORM, formular,
                formular ? REASON_FORMULAR : REASON_FORMULAR_KEIN,
                formular ? firstOf(evidence.formPages()) : KEINE_ARGS));

        boolean medien = evidence.reachable() && !evidence.mediaPages().isEmpty();
        checks.add(conditional(CheckType.MEDIA_PLAYABLE, medien,
                medien ? REASON_MEDIA : REASON_MEDIA_KEIN,
                medien ? firstOf(evidence.mediaPages()) : KEINE_ARGS));

        boolean karten = evidence.reachable() && !evidence.mapPages().isEmpty();
        checks.add(conditional(CheckType.IFRAME_EMBED, karten,
                karten ? REASON_MAPS : REASON_MAPS_KEIN,
                karten ? firstOf(evidence.mapPages()) : KEINE_ARGS));

        boolean mehrsprachig = evidence.reachable() && evidence.languages().size() > 1;
        List<String> sprachArgs = mehrsprachig
                ? List.of(String.valueOf(evidence.languages().size())) : KEINE_ARGS;
        checks.add(conditional(CheckType.HREFLANG, mehrsprachig,
                mehrsprachig ? REASON_LANGUAGES : REASON_LANGUAGES_KEIN, sprachArgs));
        checks.add(conditional(CheckType.LANGUAGE_SWITCHER, mehrsprachig,
                mehrsprachig ? REASON_LANGUAGES : REASON_LANGUAGES_KEIN, sprachArgs));

        boolean dokument = evidence.reachable() && !evidence.documentLinks().isEmpty();
        checks.add(conditional(CheckType.FILE_DOWNLOAD, dokument,
                dokument ? REASON_DOCUMENT : REASON_DOCUMENT_KEIN,
                dokument ? firstOf(evidence.documentLinks()) : KEINE_ARGS));

        boolean sitemap = evidence.reachable() && evidence.sitemapFound();
        checks.add(conditional(CheckType.SITEMAP_CONSISTENCY, sitemap,
                sitemap ? REASON_SITEMAP : REASON_SITEMAP_KEIN, KEINE_ARGS));

        boolean https = evidence.reachable() && evidence.secure();
        checks.add(conditional(CheckType.TLS_CERT, https,
                https ? REASON_HTTPS : REASON_HTTPS_NICHT, KEINE_ARGS));
        checks.add(conditional(CheckType.MIXED_CONTENT, https,
                https ? REASON_HTTPS : REASON_HTTPS_NICHT, KEINE_ARGS));

        // The other NOISY_BY_DEFAULT check: it ships off and no probe signal justifies turning it
        // on, so it is always present with its reason stated but never suggested.
        checks.add(new CheckProposal(CheckType.CONSOLE_ERRORS, false, REASON_STANDARD, KEINE_ARGS));
        checks.add(new CheckProposal(CheckType.BUTTON_REACHABILITY, false, REASON_KLICKT, KEINE_ARGS));

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
