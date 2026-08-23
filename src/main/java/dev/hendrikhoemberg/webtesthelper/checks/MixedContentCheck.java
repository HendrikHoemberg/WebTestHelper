package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.PageSnapshot;
import dev.hendrikhoemberg.webtesthelper.model.Severity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * No http subresources on an https page (spec 7.1). The browser either blocks them, which
 * leaves a visible hole, or downgrades the padlock — both are defects a client notices.
 *
 * <p>Scripts and stylesheets are the pair that matters most, and the pair a browser does not
 * merely downgrade but <em>refuses outright</em>: an http {@code <script>} on an https page never
 * runs and an http stylesheet never applies, so the page arrives unstyled or inert. Images, media
 * and frames are the passive half — usually blocked too, always at least a broken padlock.
 *
 * <p>Links are deliberately not subresources. A link is a destination; nothing is loaded into
 * this page and the padlock survives, so reporting one would be a false positive on every
 * partner link that has not moved to https yet.
 *
 * <p>Deviation D6: the fixture site is plain HTTP, so this check is proven from hand-built
 * snapshots. That costs nothing precisely because a page check is a pure function (spec 5.2).
 */
public final class MixedContentCheck implements PageCheck {

    static final String INSECURE_SUBRESOURCE = "finding.MIXED_CONTENT.insecureSubresource";

    @Override
    public CheckType type() {
        return CheckType.MIXED_CONTENT;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.WARN;
    }

    @Override
    public Set<String> messageKeys() {
        return Set.of(INSECURE_SUBRESOURCE);
    }

    @Override
    public List<CheckFinding> evaluate(PageSnapshot snapshot, CheckConfig config) {
        if (!snapshot.reachable() || !snapshot.isSecure()) {
            return List.of();
        }
        Set<String> insecure = new LinkedHashSet<>();
        snapshot.images().forEach(image -> collect(image.target(), insecure));
        snapshot.media().forEach(media -> media.sources().forEach(source -> collect(source, insecure)));
        snapshot.frames().forEach(frame -> collect(frame.src(), insecure));
        snapshot.subresources().forEach(subresource -> collect(subresource.target(), insecure));

        List<CheckFinding> findings = new ArrayList<>();
        for (String subject : insecure) {
            findings.add(new CheckFinding(type(), config.severity(), subject, snapshot.url(),
                    INSECURE_SUBRESOURCE, List.of(subject), Evidence.ofPage(snapshot)));
        }
        return findings;
    }

    private static void collect(NormalizedUrl url, Set<String> insecure) {
        if (url != null && !url.isSecure()) {
            insecure.add(url.value());
        }
    }
}