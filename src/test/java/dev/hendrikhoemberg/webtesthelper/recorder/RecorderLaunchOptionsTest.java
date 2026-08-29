package dev.hendrikhoemberg.webtesthelper.recorder;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RecorderLaunchOptionsTest {

    @Test
    void setsTheHeadlessFlag() {
        assertThat(RecorderWorker.launchOptions(true, false).headless).isTrue();
        assertThat(RecorderWorker.launchOptions(false, false).headless).isFalse();
    }

    @Test
    void addsTheNoSandboxArgOnlyWhenRequested() {
        assertThat(RecorderWorker.launchOptions(true, true).args).contains("--no-sandbox");
        assertThat(RecorderWorker.launchOptions(true, false).args).isNullOrEmpty();
        assertThat(RecorderWorker.launchOptions(false, true).args).contains("--no-sandbox");
    }
}
