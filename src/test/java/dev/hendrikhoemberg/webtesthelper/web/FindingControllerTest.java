package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.checks.CheckRegistry;
import dev.hendrikhoemberg.webtesthelper.findings.Finding;
import dev.hendrikhoemberg.webtesthelper.findings.FindingOccurrence;
import dev.hendrikhoemberg.webtesthelper.findings.FindingService;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.ObservedStatus;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.model.TriageStatus;
import dev.hendrikhoemberg.webtesthelper.reporting.FindingViewFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@org.junit.jupiter.api.parallel.ResourceLock("spring-context")
@WebMvcTest(FindingController.class)
@Import({FindingViewFactory.class, FindingControllerTest.TestConfig.class})
class FindingControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    FindingService findingService;

    @MockitoBean
    AppUserService appUserService;

    @TestConfiguration
    static class TestConfig {
        @Bean
        public CheckRegistry checkRegistry() {
            return CheckRegistry.standard();
        }
    }

    @Test
    @WithMockUser(roles = "USER")
    void pageRendersThreeParagraphsInOrder() throws Exception {
        long findingId = 1L;
        Finding finding = new Finding(
                findingId, 42L, "fp-1",
                CheckType.DEAD_LINK,
                "https://example.com/dead",
                "https://example.com/source",
                Severity.ERROR,
                "finding.DEAD_LINK.dead",
                List.of("https://example.com/dead", "404 Not Found"),
                Evidence.NONE,
                ObservedStatus.ACTIVE,
                TriageStatus.UNTRIAGED,
                null,
                10L, 10L, null, null,
                1, 1,
                Instant.parse("2026-08-25T10:00:00Z"),
                Instant.parse("2026-08-25T10:00:00Z")
        );

        when(findingService.byId(findingId)).thenReturn(Optional.of(finding));
        when(findingService.occurrencesOfLastRun(findingId, 50)).thenReturn(List.of());

        MvcResult result = mvc.perform(get("/befunde/" + findingId))
                .andExpect(status().isOk())
                .andExpect(view().name("befunde/detail"))
                .andReturn();

        String html = result.getResponse().getContentAsString();

        // 1. Was wir geprüft haben (description)
        // 2. Was wir gefunden haben (message)
        // 3. Was zu tun ist (remediation)
        int posDescHeading = html.indexOf("Was wir geprüft haben");
        int posDescContent = html.indexOf("Prüft, ob jeder Verweis noch zu einer Seite oder Datei führt");
        int posMsgHeading = html.indexOf("Was wir gefunden haben");
        int posMsgContent = html.indexOf("Der Verweis führt ins Leere (404 Not Found).");
        int posRemHeading = html.indexOf("Was zu tun ist");
        int posRemContent = html.indexOf("Verweis auf die richtige Adresse korrigieren");

        assertThat(posDescHeading).as("'Was wir geprüft haben' heading must be present").isGreaterThanOrEqualTo(0);
        assertThat(posDescContent).as("Description content must be present").isGreaterThanOrEqualTo(0);
        assertThat(posMsgHeading).as("'Was wir gefunden haben' heading must be present").isGreaterThan(posDescHeading);
        assertThat(posMsgContent).as("Message content must be present").isGreaterThan(posMsgHeading);
        assertThat(posRemHeading).as("'Was zu tun ist' heading must be present").isGreaterThan(posMsgContent);
        assertThat(posRemContent).as("Remediation content must be present").isGreaterThan(posRemHeading);

        // The dead link is clickable and copyable on the detail page, not plain message text
        assertThat(html).contains("href=\"https://example.com/dead\"");
        assertThat(html).contains("target=\"_blank\"");
        assertThat(html).contains("data-url=\"https://example.com/dead\"");
        assertThat(html).doesNotContain("Der Verweis auf https://example.com/dead führt ins Leere");
    }

    @Test
    @WithMockUser(roles = "USER")
    void detailRendersHumanizedDatesInsteadOfRawInstants() throws Exception {
        long findingId = 1L;
        Finding finding = new Finding(
                findingId, 42L, "fp-1",
                CheckType.DEAD_LINK,
                "https://example.com/dead",
                "https://example.com/source",
                Severity.ERROR,
                "finding.DEAD_LINK.dead",
                List.of("https://example.com/dead", "404 Not Found"),
                Evidence.NONE,
                ObservedStatus.ACTIVE,
                TriageStatus.UNTRIAGED,
                null,
                10L, 10L, null, null,
                1, 1,
                Instant.parse("2026-08-25T10:00:00Z"),
                Instant.parse("2026-08-25T10:00:00Z")
        );
        when(findingService.byId(findingId)).thenReturn(Optional.of(finding));
        when(findingService.occurrencesOfLastRun(findingId, 50)).thenReturn(List.of());

        mvc.perform(get("/befunde/" + findingId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("25.08.2026")))
                .andExpect(content().string(not(containsString("2026-08-25T10:00:00Z"))));
    }

    @Test
    @WithMockUser(roles = "USER")
    void rawTechnicalStringAppearsOnlyInsideTechnicalDetailsBlockAndNeverInThreeParagraphs() throws Exception {
        long findingId = 2L;
        String rawTechnical = "net::ERR_TOO_MANY_REDIRECTS";
        Finding finding = new Finding(
                findingId, 42L, "fp-2",
                CheckType.PAGE_UNREACHABLE,
                "https://example.com/loop",
                "https://example.com/loop",
                Severity.ERROR,
                "finding.PAGE_UNREACHABLE.navigation",
                List.of(rawTechnical),
                Evidence.NONE,
                ObservedStatus.ACTIVE,
                TriageStatus.UNTRIAGED,
                null,
                10L, 10L, null, null,
                1, 1,
                Instant.parse("2026-08-25T10:00:00Z"),
                Instant.parse("2026-08-25T10:00:00Z")
        );

        when(findingService.byId(findingId)).thenReturn(Optional.of(finding));
        when(findingService.occurrencesOfLastRun(findingId, 50)).thenReturn(List.of());

        MvcResult result = mvc.perform(get("/befunde/" + findingId))
                .andExpect(status().isOk())
                .andReturn();

        String html = result.getResponse().getContentAsString();

        int blockStart = html.indexOf("technische-details");
        assertThat(blockStart).as("technische-details block must exist in HTML").isGreaterThanOrEqualTo(0);

        int rawIndex = html.indexOf(rawTechnical);
        assertThat(rawIndex).as("Raw technical string '%s' must appear in HTML", rawTechnical).isGreaterThanOrEqualTo(0);
        assertThat(rawIndex)
                .as("Raw technical string '%s' must appear AFTER the start of the technische-details block", rawTechnical)
                .isGreaterThan(blockStart);

        // Assert it does not appear anywhere before the technische-details block (i.e. not in the 3 paragraphs)
        String beforeBlock = html.substring(0, blockStart);
        assertThat(beforeBlock)
                .as("Raw technical string '%s' must not appear before technische-details block", rawTechnical)
                .doesNotContain(rawTechnical);

        // Assert it appears exactly once in the entire document
        int secondOccurrence = html.indexOf(rawTechnical, rawIndex + rawTechnical.length());
        assertThat(secondOccurrence)
                .as("Raw technical string '%s' must not appear a second time", rawTechnical)
                .isEqualTo(-1);
    }

    @Test
    @WithMockUser(roles = "USER")
    void siteWideFindingRendersLocationCountAndOccurrencesCappedAt50WithTrueTotal() throws Exception {
        long findingId = 3L;
        Finding finding = new Finding(
                findingId, 42L, "fp-3",
                CheckType.TLS_CERT,
                "cert",
                "*",
                Severity.WARN,
                "finding.TLS_CERT.expiringSoon",
                List.of("example.com", "14"),
                Evidence.NONE,
                ObservedStatus.ACTIVE,
                TriageStatus.UNTRIAGED,
                null,
                10L, 10L, null, null,
                312, 312,
                Instant.parse("2026-08-25T10:00:00Z"),
                Instant.parse("2026-08-25T10:00:00Z")
        );

        List<FindingOccurrence> occurrences = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            occurrences.add(new FindingOccurrence(
                    "https://example.com/seite-" + i,
                    Severity.WARN,
                    "finding.TLS_CERT.expiringSoon",
                    List.of("example.com", "14"),
                    Evidence.NONE
            ));
        }

        when(findingService.byId(findingId)).thenReturn(Optional.of(finding));
        when(findingService.occurrencesOfLastRun(findingId, 50)).thenReturn(occurrences);

        MvcResult result = mvc.perform(get("/befunde/" + findingId))
                .andExpect(status().isOk())
                .andReturn();

        String html = result.getResponse().getContentAsString();

        assertThat(html).contains("auf 312 Seiten");
        assertThat(html).contains("50 von 312 Seiten angezeigt");
        assertThat(html).contains("https://example.com/seite-1");
        assertThat(html).contains("https://example.com/seite-50");
    }

    @Test
    @WithMockUser(roles = "USER")
    void findingWithoutScreenshotRendersNoImgTag() throws Exception {
        long findingId = 4L;
        Finding finding = new Finding(
                findingId, 42L, "fp-4",
                CheckType.DEAD_LINK,
                "https://example.com/dead",
                "https://example.com/page",
                Severity.ERROR,
                "finding.DEAD_LINK.dead",
                List.of("https://example.com/dead", "404 Not Found"),
                Evidence.NONE,
                ObservedStatus.ACTIVE,
                TriageStatus.UNTRIAGED,
                null,
                10L, 10L, null, null,
                1, 1,
                Instant.parse("2026-08-25T10:00:00Z"),
                Instant.parse("2026-08-25T10:00:00Z")
        );

        when(findingService.byId(findingId)).thenReturn(Optional.of(finding));
        when(findingService.occurrencesOfLastRun(findingId, 50)).thenReturn(List.of());

        MvcResult result = mvc.perform(get("/befunde/" + findingId))
                .andExpect(status().isOk())
                .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).doesNotContain("<img");
    }

    @Test
    @WithMockUser(roles = "USER")
    void unknownFindingIdReturns404() throws Exception {
        when(findingService.byId(999L)).thenReturn(Optional.empty());

        mvc.perform(get("/befunde/999"))
                .andExpect(status().isNotFound());
    }
}
