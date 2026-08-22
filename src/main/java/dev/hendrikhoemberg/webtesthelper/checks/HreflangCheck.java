package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.AlternateRef;
import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.PageSnapshot;
import dev.hendrikhoemberg.webtesthelper.model.RunSnapshots;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.UrlStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Hreflang reciprocity (spec 7.1): every alternate language link must name a page that exists and
 * must be named back. Runs once over the whole crawl, because whether {@code /de} links to
 * {@code /en} can only be judged from {@code /en}'s own head.
 */
public final class HreflangCheck implements SiteCheck {

    static final String INVALID_LANGUAGE = "finding.HREFLANG.invalidLanguage";
    static final String DEAD_ALTERNATE = "finding.HREFLANG.deadAlternate";
    static final String NOT_RECIPROCATED = "finding.HREFLANG.notReciprocated";

    private static final Pattern HREFLANG = Pattern.compile(
            "^[A-Za-z]{2,3}(-[A-Za-z]{4})?(-([A-Za-z]{2}|[0-9]{3}))?$");

    @Override
    public CheckType type() {
        return CheckType.HREFLANG;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.WARN;
    }

    @Override
    public Set<String> messageKeys() {
        return Set.of(INVALID_LANGUAGE, DEAD_ALTERNATE, NOT_RECIPROCATED);
    }

    @Override
    public List<CheckFinding> evaluate(RunSnapshots snapshots, SiteContext site, CheckConfig config) {
        List<CheckFinding> findings = new ArrayList<>();
        for (PageSnapshot page : snapshots.snapshots()) {
            for (AlternateRef alternate : page.alternates()) {
                CheckFinding finding = checkAlternate(page, alternate, snapshots, config);
                if (finding != null) {
                    findings.add(finding);
                }
            }
        }
        return findings;
    }

    private CheckFinding checkAlternate(PageSnapshot page, AlternateRef alternate,
            RunSnapshots snapshots, CheckConfig config) {
        String hreflang = alternate.hreflang();
        NormalizedUrl target = alternate.target();

        if (!"x-default".equals(hreflang) && !HREFLANG.matcher(hreflang).matches()) {
            return finding(config, page.url().value(), INVALID_LANGUAGE,
                    List.of(hreflang, page.url().value()), page.url());
        }

        Optional<PageSnapshot> targetSnap = snapshots.byUrl(target.value());
        boolean dead = targetSnap.map(s -> !s.reachable() || s.httpStatus() >= 400).orElse(false);
        if (!dead) {
            dead = config.facts().verifications().of(target)
                    .map(verification -> verification.status() == UrlStatus.DEAD)
                    .orElse(false);
        }
        if (dead) {
            return finding(config, target.value(), DEAD_ALTERNATE,
                    List.of(target.value(), hreflang), page.url());
        }

        if (targetSnap.isPresent() && !target.equals(page.url()) && !"x-default".equals(hreflang)) {
            boolean reciprocated = targetSnap.get().alternates().stream()
                    .anyMatch(back -> back.target().equals(page.url()));
            if (!reciprocated) {
                return finding(config, target.value(), NOT_RECIPROCATED,
                        List.of(page.url().value(), target.value()), page.url());
            }
        }
        return null;
    }

    private CheckFinding finding(CheckConfig config, String subjectKey, String messageKey,
            List<String> args, NormalizedUrl observedOn) {
        return new CheckFinding(type(), config.severity(), subjectKey, observedOn, messageKey, args,
                Evidence.NONE);
    }
}
