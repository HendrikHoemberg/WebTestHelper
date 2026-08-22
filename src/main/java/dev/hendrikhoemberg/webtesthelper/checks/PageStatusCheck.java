package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.PageSnapshot;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.model.SimHash;
import dev.hendrikhoemberg.webtesthelper.model.SoftNotFoundProbe;

import java.util.List;
import java.util.Set;

/**
 * 2xx, plus soft-404 detection (spec 7.1).
 *
 * <p>The soft-404 rule compares the page's text fingerprint against the {@code {baseUrl}/{uuid}}
 * probe taken at crawl start: a random path cannot be a real page, so whatever answers for it
 * is the site's not-found page. The cutoff is <strong>16</strong> bits of a 64-bit SimHash, and
 * it was measured rather than guessed. Against the fixture site with a real browser: an exact
 * clone of the not-found page scores 0, a not-found page that echoes the requested path 8–20,
 * and the closest unrelated real page 27 (then 31, 33, 35, 37). Sixteen sits in that gap with
 * margin on both sides.
 *
 * <p>Note that the echo ceiling (20) can already exceed the cutoff: the cutoff is calibrated
 * against the closest <em>real</em> page (27), so a site whose not-found page echoes the
 * requested path more fiercely should be re-measured with a real browser rather than prompting
 * an unmeasured lowering of the cutoff.
 *
 * <p>Override per site with {@code {"maxDistance": 20}} — and re-measure before doing so, since
 * a cutoff that eats real pages is worse than no check at all (spec 8).
 */
public final class PageStatusCheck implements PageCheck {

    static final String HTTP_ERROR = "finding.PAGE_STATUS.httpError";
    static final String SOFT_404 = "finding.PAGE_STATUS.soft404";
    static final int DEFAULT_MAX_DISTANCE = 16;

    @Override
    public CheckType type() {
        return CheckType.PAGE_STATUS;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.ERROR;
    }

    @Override
    public Set<String> messageKeys() {
        return Set.of(HTTP_ERROR, SOFT_404);
    }

    @Override
    public List<CheckFinding> evaluate(PageSnapshot snapshot, CheckConfig config) {
        if (!snapshot.reachable()) {
            return List.of();                       // PAGE_UNREACHABLE owns a page that never answered
        }
        if (snapshot.httpStatus() >= 400) {
            return List.of(finding(snapshot, config, HTTP_ERROR,
                    List.of(String.valueOf(snapshot.httpStatus()))));
        }
        SoftNotFoundProbe probe = config.facts().softNotFound();
        if (snapshot.httpStatus() != 200 || !probe.usable() || snapshot.textContent().isBlank()) {
            return List.of();
        }
        int distance = SimHash.hammingDistance(snapshot.textSimhash(), probe.simhash());
        if (distance > config.option("maxDistance", DEFAULT_MAX_DISTANCE)) {
            return List.of();
        }
        return List.of(finding(snapshot, config, SOFT_404, List.of()));
    }

    private CheckFinding finding(PageSnapshot snapshot, CheckConfig config, String messageKey,
            List<String> args) {
        return new CheckFinding(type(), config.severity(), snapshot.url().value(), snapshot.url(),
                messageKey, args, Evidence.ofPage(snapshot));
    }
}