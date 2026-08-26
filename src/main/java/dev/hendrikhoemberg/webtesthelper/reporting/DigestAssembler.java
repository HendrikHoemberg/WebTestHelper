package dev.hendrikhoemberg.webtesthelper.reporting;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteSummary;
import dev.hendrikhoemberg.webtesthelper.findings.Finding;
import dev.hendrikhoemberg.webtesthelper.findings.FindingService;
import dev.hendrikhoemberg.webtesthelper.findings.ReportSection;
import dev.hendrikhoemberg.webtesthelper.findings.RunDiff;
import dev.hendrikhoemberg.webtesthelper.model.RunStatus;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.runner.RunSummary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Transforms a {@link DigestWindow} into a {@link Digest} containing {@link SiteDigest}s.
 */
@Component
public class DigestAssembler {

    private final FindingViewFactory findingViewFactory;
    private final FindingService findingService;
    private final SiteService siteService;
    private final ReportingProperties properties;

    public DigestAssembler(
            FindingViewFactory findingViewFactory,
            FindingService findingService,
            SiteService siteService,
            ReportingProperties properties
    ) {
        this.findingViewFactory = findingViewFactory;
        this.findingService = findingService;
        this.siteService = siteService;
        this.properties = properties;
    }

    public Digest assemble(DigestWindow window, Locale locale) {
        Objects.requireNonNull(window, "window must not be null");
        Objects.requireNonNull(locale, "locale must not be null");

        int cap = Math.max(1, properties.digestMaxFindings());
        List<SiteDigest> sites = new ArrayList<>();

        for (RunSummary run : window.runs()) {
            long siteId = run.siteId();
            SiteSummary siteSummary = siteService.summary(siteId);
            String siteName = siteSummary.name();

            if (run.status() == RunStatus.FAILED) {
                sites.add(new SiteDigest(
                        siteId,
                        siteName,
                        run.id(),
                        run.status(),
                        run.finishedAt(),
                        run.errorMessage(),
                        run.partialCoverage(),
                        new DigestSection(List.of(), 0),
                        new DigestSection(List.of(), 0),
                        0,
                        0,
                        0,
                        0
                ));
                continue;
            }

            RunDiff diff = findingService.diffOf(siteId, run.id());

            List<Finding> newFindings = diff.of(ReportSection.NEW);
            List<Finding> regressedFindings = diff.of(ReportSection.REGRESSED);

            List<FindingView> shownNews = newFindings.stream()
                    .filter(f -> f.severity() == Severity.ERROR || f.severity() == Severity.WARN)
                    .limit(cap)
                    .map(f -> findingViewFactory.of(f, locale))
                    .toList();
            DigestSection news = new DigestSection(shownNews, newFindings.size());

            List<FindingView> shownRegressions = regressedFindings.stream()
                    .filter(f -> f.severity() == Severity.ERROR || f.severity() == Severity.WARN)
                    .limit(cap)
                    .map(f -> findingViewFactory.of(f, locale))
                    .toList();
            DigestSection regressions = new DigestSection(shownRegressions, regressedFindings.size());

            long newErrors = newFindings.stream().filter(f -> f.severity() == Severity.ERROR).count();
            long regressedErrors = regressedFindings.stream().filter(f -> f.severity() == Severity.ERROR).count();
            int errorCount = (int) (newErrors + regressedErrors);

            int fixedCount = diff.count(ReportSection.FIXED);
            int stillOpenCount = diff.count(ReportSection.STILL_OPEN);
            int knownCount = diff.count(ReportSection.KNOWN);

            sites.add(new SiteDigest(
                    siteId,
                    siteName,
                    run.id(),
                    run.status(),
                    run.finishedAt(),
                    null,
                    run.partialCoverage(),
                    news,
                    regressions,
                    errorCount,
                    fixedCount,
                    stillOpenCount,
                    knownCount
            ));
        }

        return new Digest(window.scope(), window.closedAt(), sites);
    }
}
