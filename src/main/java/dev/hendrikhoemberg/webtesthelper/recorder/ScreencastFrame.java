package dev.hendrikhoemberg.webtesthelper.recorder;

import java.util.Objects;

/**
 * A single screencast frame delivered from Chromium over CDP (§10.1, D110).
 *
 * @param data      base64-encoded JPEG image data
 * @param metadata  screencast viewport and scroll metadata
 * @param sessionId CDP visual frame identifier for acknowledgment (0 for on-attach screenshots)
 */
public record ScreencastFrame(
        String data,
        ScreencastMetadata metadata,
        int sessionId
) {
    public ScreencastFrame {
        Objects.requireNonNull(data, "data must not be null");
        Objects.requireNonNull(metadata, "metadata must not be null");
    }

    /**
     * Returns whether this frame requires a CDP {@code Page.screencastFrameAck}.
     */
    public boolean requiresAck() {
        return sessionId > 0;
    }
}
