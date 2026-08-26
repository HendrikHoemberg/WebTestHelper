package dev.hendrikhoemberg.webtesthelper.reporting;

import dev.hendrikhoemberg.webtesthelper.checks.CheckDescriptor;
import dev.hendrikhoemberg.webtesthelper.checks.CheckRegistry;
import dev.hendrikhoemberg.webtesthelper.findings.Finding;
import dev.hendrikhoemberg.webtesthelper.findings.FindingOccurrence;
import dev.hendrikhoemberg.webtesthelper.findings.ReportSection;
import dev.hendrikhoemberg.webtesthelper.findings.RunDiff;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
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
                .orElseThrow(() -> new IllegalStateException("Unbekannter Prüfungs-Typ: " + finding.type()));

        String title = messageSource.getMessage(descriptor.titleKey(), null, locale);
        String remediation = messageSource.getMessage(descriptor.remediationKey(), null, locale);
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
                finding.triageReason()
        );
    }

    public FindingDetailView detailOf(Finding finding, List<FindingOccurrence> occurrences, Locale locale) {
        FindingView summary = of(finding, locale);
        CheckDescriptor descriptor = checkRegistry.all().stream()
                .filter(check -> check.type() == finding.type())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unbekannter Prüfungs-Typ: " + finding.type()));

        String description = messageSource.getMessage(descriptor.descriptionKey(), null, locale);

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
