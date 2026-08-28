package dev.hendrikhoemberg.webtesthelper.recorder;

/**
 * Consumer interface receiving screencast frames (§10.1, D110).
 *
 * <p>A single-method functional interface allowing tests and WebSocket handlers
 * to collect frames uniformly.
 */
@FunctionalInterface
public interface FrameSink {

    /**
     * Called when a new screencast frame is available.
     *
     * @param frame the delivered screencast frame
     */
    void onFrame(ScreencastFrame frame);
}
