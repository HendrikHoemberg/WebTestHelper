package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.ImageRef;
import dev.hendrikhoemberg.webtesthelper.model.PageSnapshot;
import dev.hendrikhoemberg.webtesthelper.model.Severity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Every image the page references actually renders (spec 7.1) — {@code naturalWidth > 0}, not
 * merely a 200 response. A server can return bytes that no decoder accepts, and the extraction
 * script already measures the srcset candidates and CSS backgrounds that the page itself never
 * decodes, so all three origins are answerable here.
 *
 * <p>One finding per broken file per page: the same missing logo in the header and the footer
 * is one broken thing. Counting it across pages, and promoting it to a site-wide finding, is
 * materialisation's job (spec 6.2).
 */
public final class ImageBrokenCheck implements PageCheck {

    static final String NOT_RENDERED = "finding.IMAGE_BROKEN.notRendered";

    @Override
    public CheckType type() {
        return CheckType.IMAGE_BROKEN;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.ERROR;
    }

    @Override
    public Set<String> messageKeys() {
        return Set.of(NOT_RENDERED);
    }

    @Override
    public List<CheckFinding> evaluate(PageSnapshot snapshot, CheckConfig config) {
        if (!snapshot.reachable()) {
            return List.of();
        }
        List<CheckFinding> findings = new ArrayList<>();
        Set<String> reported = new HashSet<>();
        for (ImageRef image : snapshot.images()) {
            String subject = image.target().value();
            if (image.rendered() || !reported.add(subject)) {
                continue;
            }
            findings.add(new CheckFinding(type(), config.severity(), subject, snapshot.url(),
                    NOT_RENDERED, List.of(subject), Evidence.ofPage(snapshot)));
        }
        return findings;
    }
}