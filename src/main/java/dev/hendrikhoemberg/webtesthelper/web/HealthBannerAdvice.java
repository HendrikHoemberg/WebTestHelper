package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.catalog.AppSettings;
import dev.hendrikhoemberg.webtesthelper.reporting.OutboxService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Feeds the shared layout's two banners (spec 11.5, D35; spec 14): the mail-health one and the
 * scheduling-pause one. This runs on every request the application serves, the run detail's 3 s
 * progress poll included, so each attribute is a single cheap read — one {@code failedCount()}
 * and one {@code schedulingPaused()} — and never a second query that grows per request.
 */
@ControllerAdvice
public class HealthBannerAdvice {

    private final ObjectProvider<OutboxService> outboxServiceProvider;
    private final ObjectProvider<AppSettings> appSettingsProvider;

    public HealthBannerAdvice(ObjectProvider<OutboxService> outboxServiceProvider,
                              ObjectProvider<AppSettings> appSettingsProvider) {
        this.outboxServiceProvider = outboxServiceProvider;
        this.appSettingsProvider = appSettingsProvider;
    }

    @ModelAttribute
    public void mailHealth(Model model) {
        OutboxService service = outboxServiceProvider.getIfAvailable();
        int failures = service != null ? service.failedCount() : 0;

        model.addAttribute("mailFailures", failures);
        model.addAttribute("mailLastError", failures > 0 ? service.lastError().orElse(null) : null);

        AppSettings appSettings = appSettingsProvider.getIfAvailable();
        model.addAttribute("schedulingPaused", appSettings != null && appSettings.schedulingPaused());
    }
}
