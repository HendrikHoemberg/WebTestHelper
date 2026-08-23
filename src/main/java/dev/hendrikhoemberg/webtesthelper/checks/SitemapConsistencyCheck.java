package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.PageSnapshot;
import dev.hendrikhoemberg.webtesthelper.model.RunSnapshots;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;
import dev.hendrikhoemberg.webtesthelper.model.UrlStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sitemap vs. crawled pages (spec 7.1). Ships disabled, because most sites legitimately omit pages
 * from their sitemap and flagging every one would be noise. A present page that is reachable and
 * two-hundred is fine; a sitemap entry that is not, and a real page the sitemap forgot, are not.
 */
public final class SitemapConsistencyCheck implements SiteCheck {

    static final String DEAD_ENTRY = "finding.SITEMAP_CONSISTENCY.deadEntry";
    static final String MISSING_PAGE = "finding.SITEMAP_CONSISTENCY.missingPage";

    @Override
    public CheckType type() {
        return CheckType.SITEMAP_CONSISTENCY;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.WARN;
    }

    @Override
    public Set<String> messageKeys() {
        return Set.of(DEAD_ENTRY, MISSING_PAGE);
    }

    @Override
    public List<CheckFinding> evaluate(RunSnapshots snapshots, SiteContext site, CheckConfig config) {
        List<String> sitemapUrls = config.facts().sitemapUrls();
        if (sitemapUrls.isEmpty()) {
            return List.of();
        }
        Map<String, NormalizedUrl> sitemap = new LinkedHashMap<>();
        for (String raw : sitemapUrls) {
            UrlNormalizer.normalize(raw).ifPresent(url -> sitemap.put(url.value(), url));
        }

        Map<String, PageSnapshot> crawled = snapshots.byUrlIndex();
        List<CheckFinding> findings = new ArrayList<>();
        for (NormalizedUrl entry : sitemap.values()) {
            if (isDeadEntry(entry, crawled, config)) {
                findings.add(new CheckFinding(type(), config.severity(), entry.value(), null,
                        DEAD_ENTRY, List.of(entry.value()), Evidence.NONE));
            }
        }
        for (PageSnapshot page : snapshots.snapshots()) {
            if (isBroken(page, config)) {
                continue;
            }
            if (!sitemap.containsKey(page.url().value())) {
                findings.add(new CheckFinding(type(), config.severity(), page.url().value(),
                        page.url(), MISSING_PAGE, List.of(page.url().value()), Evidence.NONE));
            }
        }
        return findings;
    }

    private boolean isDeadEntry(NormalizedUrl entry, Map<String, PageSnapshot> crawled,
            CheckConfig config) {
        PageSnapshot page = crawled.get(entry.value());
        if (page != null) {
            return !page.reachable() || page.httpStatus() >= 400;
        }
        return config.facts().verifications().of(entry)
                .map(verification -> verification.status() == UrlStatus.DEAD)
                .orElse(false);
    }

    private boolean isBroken(PageSnapshot page, CheckConfig config) {
        if (!page.reachable() || page.httpStatus() >= 400) {
            return true;
        }
        return PageStatusCheck.looksLikeNotFound(page, config.facts().softNotFound(),
                config.option("maxDistance", PageStatusCheck.DEFAULT_MAX_DISTANCE));
    }
}
