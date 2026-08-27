package dev.hendrikhoemberg.webtesthelper.checks;

import com.microsoft.playwright.Page;
import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.RunSnapshots;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;

import java.time.Duration;
import java.util.List;

/**
 * A check that drives a live browser page after the crawl (spec 5.2, 7.2).
 *
 * <p>Deviation D72: interaction checks take a Playwright {@link Page} directly. Unlike
 * {@link PageCheck} and {@link SiteCheck}, which operate purely over immutable data structures,
 * an interaction check needs to click, wait, and observe DOM mutations in real time.
 */
public interface InteractionCheck extends CheckDescriptor {

    /**
     * Evaluates the check against a live browser page.
     *
     * <p>Deviation D88: an interaction check may navigate away while performing its checks, but
     * it must restore the page to the initial URL it was handed before returning. The runner
     * captures a screenshot after evaluation completes, so failing to navigate back would attach
     * evidence of the wrong page to any emitted findings.
     */
    List<CheckFinding> evaluate(Page page, SiteContext site, CheckConfig config);

    default List<NormalizedUrl> targets(RunSnapshots snapshots, SiteContext site, int maxTargets) {
        return InteractionTargets.homepage(snapshots, site);
    }

    /**
     * Optional execution timeout override for checks that perform external or long-running verification
     * (e.g. mail verification, spec 7.2, D92).
     *
     * <p>Returning {@code null} means the runner will apply the pass default timeout ({@code interactionProperties.timeout()}).
     * When a check exceeds its timeout, the runner discards its findings and excludes the check type
     * from {@code drivenTypes}.
     */
    default Duration timeout() {
        return null;
    }
}
