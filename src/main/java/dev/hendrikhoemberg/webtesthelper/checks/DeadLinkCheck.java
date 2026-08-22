package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.FrameRef;
import dev.hendrikhoemberg.webtesthelper.model.LinkRef;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.PageSnapshot;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.model.UrlStatus;
import dev.hendrikhoemberg.webtesthelper.model.UrlVerification;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class DeadLinkCheck implements PageCheck {

    static final String DEAD = "finding.DEAD_LINK.dead";
    static final String UNVERIFIABLE = "finding.DEAD_LINK.unverifiable";

    @Override
    public CheckType type() {
        return CheckType.DEAD_LINK;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.ERROR;
    }

    @Override
    public Set<String> messageKeys() {
        return Set.of(DEAD, UNVERIFIABLE);
    }

    @Override
    public List<CheckFinding> evaluate(PageSnapshot snapshot, CheckConfig config) {
        if (!snapshot.reachable()) {
            return List.of();
        }
        Set<String> seen = new HashSet<>();
        List<CheckFinding> findings = new ArrayList<>();
        for (LinkRef link : snapshot.links()) {
            addFindings(snapshot, config, link.target(), seen, findings);
        }
        for (FrameRef frame : snapshot.frames()) {
            addFindings(snapshot, config, frame.src(), seen, findings);
        }
        return findings;
    }

    private void addFindings(PageSnapshot snapshot, CheckConfig config, NormalizedUrl target,
            Set<String> seen, List<CheckFinding> findings) {
        if (target == null || !seen.add(target.value())) {
            return;
        }
        config.facts().verifications().of(target).ifPresent(verification -> {
            if (verification.status() == UrlStatus.DEAD) {
                String detail = verification.httpStatus() != 0
                        ? String.valueOf(verification.httpStatus())
                        : verification.failureText() == null ? "" : verification.failureText();
                findings.add(new CheckFinding(type(), config.severity(), target.value(),
                        snapshot.url(), DEAD, List.of(target.value(), detail),
                        new Evidence(snapshot.screenshotPath(), verification.httpStatus(),
                                null, verification.failureText(), List.of())));
            } else if (verification.status() == UrlStatus.UNVERIFIABLE) {
                String detail = verification.failureText() == null ? "" : verification.failureText();
                findings.add(new CheckFinding(type(), Severity.INFO, target.value(),
                        snapshot.url(), UNVERIFIABLE, List.of(target.value(), detail),
                        new Evidence(snapshot.screenshotPath(), verification.httpStatus(),
                                null, verification.failureText(), List.of())));
            }
        });
    }
}
