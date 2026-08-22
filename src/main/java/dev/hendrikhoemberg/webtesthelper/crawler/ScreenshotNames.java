package dev.hendrikhoemberg.webtesthelper.crawler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The screenshot filename algorithm: a 32-character SHA-256 prefix of the URL, plus {@code .png}.
 * Shared by the producer ({@link PageNavigator}) and the probe-screenshot deleter
 * ({@link CrawlService}) so the two never drift apart.
 */
final class ScreenshotNames {

    private ScreenshotNames() {
    }

    static String screenshotName(String url) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(url.getBytes(StandardCharsets.UTF_8))).substring(0, 32) + ".png";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
