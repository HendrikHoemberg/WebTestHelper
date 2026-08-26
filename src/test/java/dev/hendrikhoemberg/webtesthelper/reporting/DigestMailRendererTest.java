package dev.hendrikhoemberg.webtesthelper.reporting;

import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunStatus;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.model.TriageStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DigestMailRendererTest {

    private DigestMailRenderer renderer;

    @BeforeEach
    void setUp() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");

        ClassLoaderTemplateResolver htmlResolver = new ClassLoaderTemplateResolver();
        htmlResolver.setPrefix("templates/");
        htmlResolver.setTemplateMode(TemplateMode.HTML);
        htmlResolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        htmlResolver.setResolvablePatterns(Set.of("*.html", "mail/*.html"));

        ClassLoaderTemplateResolver textResolver = new ClassLoaderTemplateResolver();
        textResolver.setPrefix("templates/");
        textResolver.setTemplateMode(TemplateMode.TEXT);
        textResolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        textResolver.setResolvablePatterns(Set.of("*.txt", "mail/*.txt"));

        SpringTemplateEngine templateEngine = new SpringTemplateEngine();
        templateEngine.addTemplateResolver(htmlResolver);
        templateEngine.addTemplateResolver(textResolver);
        templateEngine.setMessageSource(messageSource);

        renderer = new DigestMailRenderer(templateEngine, messageSource);
    }

    @Test
    void subjectOfTwoErrorPulseDigest() {
        FindingView finding1 = new FindingView(
                1L, "Tote Links", "HTTP 404 auf https://example.com/kontakt",
                "Linkziel prüfen", "/kontakt", false, 1,
                Severity.ERROR, TriageStatus.UNTRIAGED
        );
        FindingView finding2 = new FindingView(
                2L, "Fehlendes Bild", "Bild nicht ladbar",
                "Bildpfad prüfen", "/ueber-uns", false, 1,
                Severity.ERROR, TriageStatus.UNTRIAGED
        );

        SiteDigest site = new SiteDigest(
                10L, "Kunde Müller", 101L, RunStatus.COMPLETED, Instant.now(), null, false,
                new DigestSection(List.of(finding1, finding2), 2),
                new DigestSection(List.of(), 0),
                2, 0, 0, 0
        );

        Digest digest = new Digest(RunScope.PULSE, Instant.now(), List.of(site));

        OutboundMail mail = renderer.render("admin@example.com", digest, "https://wth.example", Locale.GERMAN);

        assertThat(mail.subject()).isEqualTo("WebTestHelper – Puls-Prüfung: 2 neue oder wiederkehrende Fehler");
    }

    @Test
    void subjectOfOneErrorDigestUsesSingular() {
        FindingView finding1 = new FindingView(
                1L, "Tote Links", "HTTP 404 auf https://example.com/kontakt",
                "Linkziel prüfen", "/kontakt", false, 1,
                Severity.ERROR, TriageStatus.UNTRIAGED
        );

        SiteDigest site = new SiteDigest(
                10L, "Kunde Müller", 101L, RunStatus.COMPLETED, Instant.now(), null, false,
                new DigestSection(List.of(finding1), 1),
                new DigestSection(List.of(), 0),
                1, 0, 0, 0
        );

        Digest digest = new Digest(RunScope.PULSE, Instant.now(), List.of(site));

        OutboundMail mail = renderer.render("admin@example.com", digest, "https://wth.example", Locale.GERMAN);

        assertThat(mail.subject()).isEqualTo("WebTestHelper – Puls-Prüfung: 1 neuer oder wiederkehrender Fehler");
    }

    @Test
    void subjectOfDeepAllClearEndsWithAllesInOrdnung() {
        SiteDigest site = new SiteDigest(
                10L, "Kunde Müller", 101L, RunStatus.COMPLETED, Instant.now(), null, false,
                new DigestSection(List.of(), 0),
                new DigestSection(List.of(), 0),
                0, 0, 0, 0
        );

        Digest digest = new Digest(RunScope.DEEP, Instant.now(), List.of(site));

        OutboundMail mail = renderer.render("admin@example.com", digest, "https://wth.example", Locale.GERMAN);

        assertThat(mail.subject()).isEqualTo("WebTestHelper – Tiefenprüfung: alles in Ordnung");
        assertThat(mail.subject()).endsWith("alles in Ordnung");
    }

    @Test
    void subjectOfFailedRunsSingularAndPluralAndCombined() {
        SiteDigest failedSite1 = new SiteDigest(
                10L, "Kunde Müller", 101L, RunStatus.FAILED, Instant.now(), "Timeout beim Laden", false,
                new DigestSection(List.of(), 0),
                new DigestSection(List.of(), 0),
                0, 0, 0, 0
        );
        SiteDigest failedSite2 = new SiteDigest(
                20L, "Kunde Meier", 102L, RunStatus.FAILED, Instant.now(), "Verbindung abgelehnt", false,
                new DigestSection(List.of(), 0),
                new DigestSection(List.of(), 0),
                0, 0, 0, 0
        );

        Digest singleFailed = new Digest(RunScope.FULL, Instant.now(), List.of(failedSite1));
        OutboundMail mail1 = renderer.render("admin@example.com", singleFailed, "https://wth.example", Locale.GERMAN);
        assertThat(mail1.subject()).isEqualTo("WebTestHelper – Vollständige Prüfung: 1 Prüflauf fehlgeschlagen");

        Digest doubleFailed = new Digest(RunScope.FULL, Instant.now(), List.of(failedSite1, failedSite2));
        OutboundMail mail2 = renderer.render("admin@example.com", doubleFailed, "https://wth.example", Locale.GERMAN);
        assertThat(mail2.subject()).isEqualTo("WebTestHelper – Vollständige Prüfung: 2 Prüfläufe fehlgeschlagen");

        FindingView finding = new FindingView(
                1L, "Tote Links", "HTTP 404", "Linkziel prüfen", "/kontakt", false, 1,
                Severity.ERROR, TriageStatus.UNTRIAGED
        );
        SiteDigest errorSite = new SiteDigest(
                30L, "Kunde Schmidt", 103L, RunStatus.COMPLETED, Instant.now(), null, false,
                new DigestSection(List.of(finding), 1),
                new DigestSection(List.of(), 0),
                1, 0, 0, 0
        );
        Digest combined = new Digest(RunScope.PULSE, Instant.now(), List.of(failedSite1, errorSite));
        OutboundMail mail3 = renderer.render("admin@example.com", combined, "https://wth.example", Locale.GERMAN);
        assertThat(mail3.subject()).isEqualTo("WebTestHelper – Puls-Prüfung: 1 neuer oder wiederkehrender Fehler, 1 Prüflauf fehlgeschlagen");
    }

    @Test
    void rendersHtmlAndTextWithAllDetailsAndNoRawEnumsOrUnresolvedKeys() {
        FindingView newFinding = new FindingView(
                201L, "Tote Links", "HTTP 404 auf https://example.com/kontakt",
                "Linkziel im Quelltext anpassen", "/kontakt", false, 1,
                Severity.ERROR, TriageStatus.UNTRIAGED
        );
        FindingView regressedFinding = new FindingView(
                202L, "Gemischte Inhalte", "Unsicheres Bild geladen über http://",
                "Auf HTTPS umstellen", "/impressum", false, 1,
                Severity.WARN, TriageStatus.UNTRIAGED
        );

        SiteDigest siteCompleted = new SiteDigest(
                10L, "Kunde Müller", 501L, RunStatus.COMPLETED, Instant.now(), null, true,
                new DigestSection(List.of(newFinding), 3),
                new DigestSection(List.of(regressedFinding), 1),
                1, 2, 4, 3
        );

        SiteDigest siteFailed = new SiteDigest(
                20L, "Kunde Meier", 502L, RunStatus.FAILED, Instant.now(), "DNS-Auflösung fehlgeschlagen", false,
                new DigestSection(List.of(), 0),
                new DigestSection(List.of(), 0),
                0, 0, 0, 0
        );

        Digest digest = new Digest(RunScope.FULL, Instant.now(), List.of(siteCompleted, siteFailed));
        String baseUrl = "https://wth.example";

        OutboundMail mail = renderer.render("admin@example.com", digest, baseUrl, Locale.GERMAN);

        assertThat(mail.recipient()).isEqualTo("admin@example.com");
        assertThat(mail.subject()).isNotBlank();
        assertThat(mail.html()).isNotBlank();
        assertThat(mail.text()).isNotBlank();

        // HTML assertions
        assertThat(mail.html())
                .contains("Kunde Müller")
                .contains("Kunde Meier")
                .contains("Tote Links")
                .contains("HTTP 404 auf https://example.com/kontakt")
                .contains("Linkziel im Quelltext anpassen")
                .contains("/kontakt")
                .contains("Gemischte Inhalte")
                .contains("Unsicheres Bild geladen über http://")
                .contains("https://wth.example/laeufe/501")
                .contains("https://wth.example/laeufe/502")
                .contains("https://wth.example/befunde/201")
                .contains("https://wth.example/befunde/202")
                .contains("Fehler")
                .contains("Warnung")
                .contains("Neu")
                .contains("Wieder aufgetreten")
                .contains("und 2 weitere")
                .contains("DNS-Auflösung fehlgeschlagen")
                .contains("Der Prüflauf ist fehlgeschlagen und hat nichts geprüft.")
                .contains("Der Lauf hat sein Budget erreicht und nicht die ganze Website geprüft.")
                .contains("2 behoben")
                .contains("4 weiterhin offen")
                .contains("3 bereits bewertet")
                .doesNotContain("??")
                .doesNotContain("NEW")
                .doesNotContain("REGRESSED")
                .doesNotContain("ERROR")
                .doesNotContain("UNTRIAGED")
                .doesNotContain("Exception");

        // Text assertions
        assertThat(mail.text())
                .contains("Kunde Müller")
                .contains("Kunde Meier")
                .contains("Tote Links")
                .contains("HTTP 404 auf https://example.com/kontakt")
                .contains("Linkziel im Quelltext anpassen")
                .contains("Gemischte Inhalte")
                .contains("https://wth.example/laeufe/501")
                .contains("https://wth.example/laeufe/502")
                .contains("https://wth.example/befunde/201")
                .contains("https://wth.example/befunde/202")
                .contains("Fehler")
                .contains("Warnung")
                .contains("Neu")
                .contains("Wieder aufgetreten")
                .contains("und 2 weitere")
                .contains("DNS-Auflösung fehlgeschlagen")
                .contains("Der Prüflauf ist fehlgeschlagen und hat nichts geprüft.")
                .contains("Der Lauf hat sein Budget erreicht und nicht die ganze Website geprüft.")
                .contains("2 behoben")
                .contains("4 weiterhin offen")
                .contains("3 bereits bewertet")
                .doesNotContain("<a ")
                .doesNotContain("<html")
                .doesNotContain("??")
                .doesNotContain("NEW")
                .doesNotContain("REGRESSED")
                .doesNotContain("ERROR")
                .doesNotContain("UNTRIAGED")
                .doesNotContain("Exception");
    }

    @Test
    void rendersAllClearDigest() {
        SiteDigest site = new SiteDigest(
                10L, "Kunde Müller", 501L, RunStatus.COMPLETED, Instant.now(), null, false,
                new DigestSection(List.of(), 0),
                new DigestSection(List.of(), 0),
                0, 0, 0, 0
        );

        Digest digest = new Digest(RunScope.DEEP, Instant.now(), List.of(site));
        String baseUrl = "https://wth.example";

        OutboundMail mail = renderer.render("admin@example.com", digest, baseUrl, Locale.GERMAN);

        assertThat(mail.html())
                .contains("Auf allen geprüften Websites ist alles in Ordnung.")
                .doesNotContain("??");

        assertThat(mail.text())
                .contains("Auf allen geprüften Websites ist alles in Ordnung.")
                .doesNotContain("??");
    }
}
