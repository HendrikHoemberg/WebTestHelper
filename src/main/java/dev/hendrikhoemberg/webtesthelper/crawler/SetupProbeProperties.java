package dev.hendrikhoemberg.webtesthelper.crawler;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param probePages   total pages to visit, homepage included (default 8 = homepage + 7 admitted
 *                     internal links), enough to surface a contact page, a media page,
 *                     a Maps embed, a form and the sitemap signal
 * @param probeTimeout budget for one probe, checked between pages and never inside a navigation;
 *                     waiting for a busy {@link BrowserPool} is not counted
 */
@ConfigurationProperties("webtesthelper.setup")
public record SetupProbeProperties(int probePages, Duration probeTimeout) {
}
