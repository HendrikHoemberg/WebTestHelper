package dev.hendrikhoemberg.webtesthelper.crawler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The screenshot filename algorithm: a 32-character SHA-256 prefix of the URL (plus optional
 * discriminator), plus {@code .png}. Shared by the producer ({@link PageNavigator}), the
 * interaction runner, and the probe-screenshot deleter ({@link CrawlService}) so the naming
 * never drifts.
 */
public final class ScreenshotNames {

    private ScreenshotNames() {
    }

    public static String screenshotName(String url) {
        return screenshotName(url, "");
    }

    public static String screenshotName(String url, String discriminator) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String input = (discriminator == null || discriminator.isEmpty())
                    ? url
                    : url + "#" + discriminator;
            return HexFormat.of().formatHex(
                    digest.digest(input.getBytes(StandardCharsets.UTF_8))).substring(0, 32) + ".png";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
