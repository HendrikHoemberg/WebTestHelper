package dev.hendrikhoemberg.webtesthelper.reporting;

import dev.hendrikhoemberg.webtesthelper.checks.CheckDescriptor;
import dev.hendrikhoemberg.webtesthelper.checks.CheckRegistry;
import dev.hendrikhoemberg.webtesthelper.findings.Finding;
import dev.hendrikhoemberg.webtesthelper.findings.FindingOccurrence;
import dev.hendrikhoemberg.webtesthelper.findings.ReportSection;
import dev.hendrikhoemberg.webtesthelper.findings.RunDiff;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Map;

/**
 * Transforms domain {@link Finding} records and {@link RunDiff} aggregates into {@link FindingView}
 * view models, resolving human-readable German titles, remediations, and messages.
 */
@Component
public class FindingViewFactory {

    private final MessageSource messageSource;
    private final CheckRegistry checkRegistry;

    public FindingViewFactory(MessageSource messageSource, CheckRegistry checkRegistry) {
        this.messageSource = messageSource;
        this.checkRegistry = checkRegistry;
    }

    public FindingView of(Finding finding, Locale locale) {
        CheckDescriptor descriptor = checkRegistry.all().stream()
                .filter(check -> check.type() == finding.type())
                .findFirst()
                .orElse(null);

        String titleKey = descriptor != null ? descriptor.titleKey() : "check." + finding.type().name() + ".title";
        String remediationKey = descriptor != null ? descriptor.remediationKey() : "check." + finding.type().name() + ".remediation";
        String title = messageSource.getMessage(titleKey, null, locale);
        String remediation = messageSource.getMessage(remediationKey, null, locale);
        Object[] formattedArgs = finding.messageArgs().stream()
                .map(arg -> TechnicalText.humanise(arg, messageSource, locale))
                .toArray();
        String message = messageSource.getMessage(finding.messageKey(), formattedArgs, locale);

        boolean siteWide = "*".equals(finding.locationKey());
        String locationText;
        if (siteWide) {
            locationText = messageSource.getMessage("ui.befund.siteweite",
                    new Object[]{finding.pageCount()},
                    "auf " + finding.pageCount() + " Seiten",
                    locale);
        } else {
            locationText = finding.locationKey();
        }

        return new FindingView(
                finding.id(),
                title,
                message,
                remediation,
                locationText,
                siteWide,
                finding.pageCount(),
                finding.severity(),
                finding.triage(),
                finding.mutedUntil(),
                finding.muteExpiredAt(),
                finding.triageReason(),
                subjectUrlOf(finding.subjectKey())
        );
    }

    /**
     * The finding's subject as a clickable URL, or {@code null} when the subject is no absolute
     * http(s) address (a {@code *} location, a relative path, an ID, …).
     */
    private static String subjectUrlOf(String raw) {
        return UrlNormalizer.normalize(raw).map(NormalizedUrl::value).orElse(null);
    }

    public FindingDetailView detailOf(Finding finding, List<FindingOccurrence> occurrences, Locale locale) {
        FindingView summary = of(finding, locale);
        CheckDescriptor descriptor = checkRegistry.all().stream()
                .filter(check -> check.type() == finding.type())
                .findFirst()
                .orElse(null);

        String descriptionKey = descriptor != null ? descriptor.descriptionKey() : "check." + finding.type().name() + ".description";
        String description = messageSource.getMessage(descriptionKey, null, locale);

        String rawTechnicalDetail = finding.messageArgs().stream()
                .filter(TechnicalText::isTechnical)
                .findFirst()
                .orElse(null);
        String technicalDetail = rawTechnicalDetail != null
                ? TechnicalText.humanise(rawTechnicalDetail, messageSource, locale)
                : null;

        Evidence evidence = finding.evidence();
        String screenshotUrl = null;
        if (evidence.screenshotPath() != null && !evidence.screenshotPath().isBlank()) {
            screenshotUrl = "/artefakte/" + finding.lastSeenRun() + "/" + evidence.screenshotPath();
        }

        List<String> pages = occurrences.stream()
                .map(FindingOccurrence::pageUrl)
                .filter(Objects::nonNull)
                .toList();

        return new FindingDetailView(
                summary,
                description,
                technicalDetail,
                rawTechnicalDetail,
                evidence.httpStatus(),
                evidence.requestDetail(),
                evidence.responseDetail(),
                evidence.consoleExcerpt(),
                screenshotUrl,
                pages,
                finding.pageCount(),
                finding.firstSeenAt(),
                finding.lastSeenAt(),
                finding.triageReason()
        );
    }

    public Map<ReportSection, List<FindingView>> of(RunDiff diff, Locale locale) {
        Map<ReportSection, List<FindingView>> result = new LinkedHashMap<>();
        for (ReportSection section : ReportSection.values()) {
            if (diff.bySection().containsKey(section)) {
                List<FindingView> views = diff.of(section).stream()
                        .map(finding -> of(finding, locale))
                        .toList();
                result.put(section, views);
            }
        }
        return result;
    }
}
