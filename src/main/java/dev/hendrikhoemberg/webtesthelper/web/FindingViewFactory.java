package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.checks.CheckDescriptor;
import dev.hendrikhoemberg.webtesthelper.checks.CheckRegistry;
import dev.hendrikhoemberg.webtesthelper.findings.Finding;
import dev.hendrikhoemberg.webtesthelper.findings.ReportSection;
import dev.hendrikhoemberg.webtesthelper.findings.RunDiff;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
        String message = messageSource.getMessage(finding.messageKey(), finding.messageArgs().toArray(), locale);

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
                finding.triage()
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
