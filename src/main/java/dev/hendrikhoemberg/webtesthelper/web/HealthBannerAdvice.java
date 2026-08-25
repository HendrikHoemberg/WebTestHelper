package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.reporting.OutboxService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Feeds the shared layout's mail-health banner (spec 11.5, D35). This runs on every request the
 * application serves, the run detail's 3 s progress poll included, so it asks the database for the
 * error text only once there is a failure to explain.
 */
@ControllerAdvice
public class HealthBannerAdvice {

    private final ObjectProvider<OutboxService> outboxServiceProvider;

    public HealthBannerAdvice(ObjectProvider<OutboxService> outboxServiceProvider) {
        this.outboxServiceProvider = outboxServiceProvider;
    }

    @ModelAttribute
    public void mailHealth(Model model) {
        OutboxService service = outboxServiceProvider.getIfAvailable();
        int failures = service != null ? service.failedCount() : 0;

        model.addAttribute("mailFailures", failures);
        model.addAttribute("mailLastError", failures > 0 ? service.lastError().orElse(null) : null);
    }
}
