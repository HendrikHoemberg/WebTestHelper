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
 * Embedded video and audio load their metadata and have a duration (spec 7.1). Either
 * condition alone lies: a source that 404s still leaves an element on the page, and an element
 * that reports a readyState still plays nothing when its duration is zero.
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
        for (MediaRef media : snapshot.media()) {
            if (media.playable()) {
                continue;
            }
            // An element with no source at all is still broken; name the page, not a blank.
            String subject = media.sources().isEmpty()
                    ? snapshot.url().value()
                    : media.sources().getFirst().value();
            if (!reported.add(subject)) {
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