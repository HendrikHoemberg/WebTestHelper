package dev.hendrikhoemberg.webtesthelper.checks;

import com.microsoft.playwright.Page;
import dev.hendrikhoemberg.webtesthelper.checks.CookieBanner.BannerOutcome;
import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.RunSnapshots;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;

import java.util.List;
import java.util.Set;

/**
 * Cookie banner interaction check (spec 7.2, D69, D77).
 *
 * <p>Detects a consent banner on the homepage and attempts to dismiss it. If a banner is present
 * but cannot be dismissed, visitors cannot reach the site's content; emits an {@link Severity#ERROR}
 * finding naming the banner container id.
 */
public final class CookieBannerCheck implements InteractionCheck {

    static final String UNDISMISSABLE = "finding.COOKIE_BANNER.undismissable";

    @Override
    public CheckType type() {
        return CheckType.COOKIE_BANNER;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.ERROR;
    }

    @Override
    public Set<String> messageKeys() {
        return Set.of(UNDISMISSABLE);
    }

    @Override
    public List<NormalizedUrl> targets(RunSnapshots snapshots, SiteContext site, int maxTargets) {
        return InteractionTargets.homepage(snapshots, site);
    }

    @Override
    public List<CheckFinding> evaluate(Page page, SiteContext site, CheckConfig config) {
        if (page == null) {
            return List.of();
        }

        BannerOutcome outcome = CookieBanner.accept(page, CookieBanner.DISMISSAL_WAIT);
        if (outcome.present() && !outcome.dismissed()) {
            String subjectKey = outcome.containerId() != null ? outcome.containerId() : "cookie-banner";
            // Never null: CheckFinding maps a null observedOn to the site-wide location key "*",
            // and RESOLVE_INTERACTION_SQL has no "*" branch by design (D75), so such a finding
            // would be unresolvable forever. If the page navigated somewhere unparseable, the
            // target we were pointed at is the honest location.
            NormalizedUrl fallback = site != null ? site.baseUrl() : null;
            NormalizedUrl observedOn = page.url() != null
                    ? UrlNormalizer.normalize(page.url()).orElse(fallback)
                    : fallback;

            return List.of(new CheckFinding(
                    type(),
                    config.severity(),
                    subjectKey,
                    observedOn,
                    UNDISMISSABLE,
                    List.of(subjectKey),
                    Evidence.NONE));
        }

        return List.of();
    }
}
