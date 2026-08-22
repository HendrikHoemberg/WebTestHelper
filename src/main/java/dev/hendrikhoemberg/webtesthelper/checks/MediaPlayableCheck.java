package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.MediaKind;
import dev.hendrikhoemberg.webtesthelper.model.MediaRef;
import dev.hendrikhoemberg.webtesthelper.model.PageSnapshot;
import dev.hendrikhoemberg.webtesthelper.model.Severity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Embedded video and audio load their metadata, have a duration and play without an error
 * (spec 7.1) — readyState &ge; 1, duration &gt; 0 and a null error code, exactly the three
 * clauses of {@link MediaRef#playable()}. Each condition alone lies: a source that 404s still
 * leaves an element on the page, an element that reports a readyState still plays nothing when
 * its duration is zero, and a nonzero duration still plays nothing once the element reports an
 * error.
 *
 * <p>The kind selects the message key rather than being interpolated into it, because
 * {@code VIDEO} is an internal identifier and spec 13.1 says none of those reach the screen.
 */
public final class MediaPlayableCheck implements PageCheck {

    static final String VIDEO = "finding.MEDIA_PLAYABLE.video";
    static final String AUDIO = "finding.MEDIA_PLAYABLE.audio";

    @Override
    public CheckType type() {
        return CheckType.MEDIA_PLAYABLE;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.ERROR;
    }

    @Override
    public Set<String> messageKeys() {
        return Set.of(VIDEO, AUDIO);
    }

    @Override
    public List<CheckFinding> evaluate(PageSnapshot snapshot, CheckConfig config) {
        if (!snapshot.reachable()) {
            return List.of();
        }
        List<CheckFinding> findings = new ArrayList<>();
        Set<String> reported = new HashSet<>();
        int anonymous = 0;
        for (MediaRef media : snapshot.media()) {
            if (media.playable()) {
                continue;
            }
            // An element with no source at all is still broken; name the page, not a blank.
            boolean hasSource = !media.sources().isEmpty();
            String subject = hasSource ? media.sources().getFirst().value() : snapshot.url().value();
            // Source-less elements all fall back to the page URL, so the dedupe key cannot be the
            // subject: a NUL-prefixed counter keeps each element its own finding while never
            // colliding with a real URL.
            String dedupe = hasSource ? subject : "\u0000no-source-" + anonymous++;
            if (!reported.add(dedupe)) {
                continue;
            }
            findings.add(new CheckFinding(type(), config.severity(), subject, snapshot.url(),
                    media.kind() == MediaKind.VIDEO ? VIDEO : AUDIO, List.of(subject),
                    new Evidence(snapshot.screenshotPath(), null, null, media.errorCode(),
                            List.of())));
        }
        return findings;
    }
}