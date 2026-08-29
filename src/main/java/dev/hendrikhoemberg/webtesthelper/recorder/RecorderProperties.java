package dev.hendrikhoemberg.webtesthelper.recorder;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration for the journey recorder (§10.1, D109, D110).
 *
 * @param maxSessions    maximum concurrent recording sessions (spec 10.1; default 2)
 * @param idleTimeout    idle timeout before session is reaped (spec 10.1; default 15m)
 * @param frameQuality   CDP screencast frame JPEG quality 1..100 (default 60)
 * @param viewportWidth  recorder viewport width in CSS pixels (default 1280)
 * @param viewportHeight recorder viewport height in CSS pixels (default 720)
 * @param headless       whether Chromium runs headless (default true)
 * @param noSandbox      pass --no-sandbox to Chromium for container use (default false)
 * @param pumpInterval   how long each pump call holds the worker thread inside Playwright so CDP
 *                       events can be dispatched (default 100ms); see {@link ScreencastBridge}
 */
@ConfigurationProperties("webtesthelper.recorder")
public record RecorderProperties(
        int maxSessions,
        Duration idleTimeout,
        int frameQuality,
        int viewportWidth,
        int viewportHeight,
        boolean headless,
        boolean noSandbox,
        Duration pumpInterval
) {
    public RecorderProperties {
        if (maxSessions <= 0) {
            maxSessions = 2;
        }
        if (idleTimeout == null) {
            idleTimeout = Duration.ofMinutes(15);
        }
        if (frameQuality <= 0) {
            frameQuality = 60;
        }
        if (viewportWidth <= 0) {
            viewportWidth = 1280;
        }
        if (viewportHeight <= 0) {
            viewportHeight = 720;
        }
        if (pumpInterval == null || pumpInterval.isZero() || pumpInterval.isNegative()) {
            pumpInterval = Duration.ofMillis(100);
        }
    }
}
