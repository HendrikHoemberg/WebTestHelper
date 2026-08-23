package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.DocumentTypes;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.LinkRef;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.PageSnapshot;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.model.UrlStatus;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class FileDownloadCheck implements PageCheck {

    static final String WRONG_TYPE = "finding.FILE_DOWNLOAD.wrongType";
    static final String NOT_A_PDF = "finding.FILE_DOWNLOAD.notAPdf";
    static final String TOO_SMALL = "finding.FILE_DOWNLOAD.tooSmall";

    static final long MIN_LENGTH = 1024;

    @Override
    public CheckType type() {
        return CheckType.FILE_DOWNLOAD;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.ERROR;
    }

    @Override
    public Set<String> messageKeys() {
        return Set.of(WRONG_TYPE, NOT_A_PDF, TOO_SMALL);
    }

    @Override
    public List<CheckFinding> evaluate(PageSnapshot snapshot, CheckConfig config) {
        if (!snapshot.reachable()) {
            return List.of();
        }
        Set<String> seen = new HashSet<>();
        List<CheckFinding> findings = new ArrayList<>();
        for (LinkRef link : snapshot.links()) {
            NormalizedUrl target = link.target();
            if (target == null || !seen.add(target.value()) || !DocumentTypes.isDocument(target)) {
                continue;
            }
            config.facts().verifications().of(target).ifPresent(verification -> {
                if (verification.status() == UrlStatus.DEAD || !verification.hasBody()) {
                    return;
                }
                if (isHtml(verification.contentType())) {
                    findings.add(new CheckFinding(type(), config.severity(), target.value(),
                            snapshot.url(), WRONG_TYPE,
                            List.of(target.value(), verification.contentType()),
                            new Evidence(snapshot.screenshotPath(), verification.httpStatus(),
                                    null, verification.contentType(), List.of())));
                    return;
                }
                if (DocumentTypes.isPdf(target) && !isPdfMagic(verification.bodyPrefix())) {
                    findings.add(new CheckFinding(type(), config.severity(), target.value(),
                            snapshot.url(), NOT_A_PDF, List.of(target.value()),
                            new Evidence(snapshot.screenshotPath(), verification.httpStatus(),
                                    null, verification.bodyPrefix(), List.of())));
                    return;
                }
                if (verification.contentLength() > 0 && verification.contentLength() < MIN_LENGTH) {
                    findings.add(new CheckFinding(type(), config.severity(), target.value(),
                            snapshot.url(), TOO_SMALL,
                            List.of(target.value(), String.valueOf(verification.contentLength())),
                            new Evidence(snapshot.screenshotPath(), verification.httpStatus(),
                                    null, String.valueOf(verification.contentLength()),
                                    List.of())));
                }
            });
        }
        return findings;
    }

    private static boolean isHtml(String contentType) {
        return contentType != null
                && contentType.toLowerCase(Locale.ROOT).contains("text/html");
    }

    private static boolean isPdfMagic(String prefix) {
        return prefix != null && prefix.startsWith("%PDF");
    }
}
