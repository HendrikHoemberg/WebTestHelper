package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.reporting.OutboxService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class HealthBannerAdvice {

    private final ObjectProvider<OutboxService> outboxServiceProvider;

    public HealthBannerAdvice(ObjectProvider<OutboxService> outboxServiceProvider) {
        this.outboxServiceProvider = outboxServiceProvider;
    }

    @ModelAttribute("mailFailures")
    public int mailFailures() {
        OutboxService service = outboxServiceProvider.getIfAvailable();
        return service != null ? service.failedCount() : 0;
    }

    @ModelAttribute("mailLastError")
    public String mailLastError() {
        OutboxService service = outboxServiceProvider.getIfAvailable();
        return service != null ? service.lastError().orElse(null) : null;
    }
}
