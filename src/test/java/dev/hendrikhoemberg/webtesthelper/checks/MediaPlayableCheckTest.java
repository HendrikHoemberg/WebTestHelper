package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.MediaKind;
import dev.hendrikhoemberg.webtesthelper.support.Snapshots;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MediaPlayableCheckTest {

    private final MediaPlayableCheck check = new MediaPlayableCheck();

    @Test
    void aVideoWhoseSourceFailedIsReportedAsAVideo() {
        // Spec 13.1: the sentence a colleague reads must say "Video", so the kind picks the key
        // rather than being interpolated as an enum name.
        CheckFinding finding = check.evaluate(
                Snapshots.page("https://example.com/medien")
                        .media(MediaKind.VIDEO, "https://example.com/fehlt.mp4", 0, 0.0,
                                "MEDIA_ERR_SRC_NOT_SUPPORTED").build(),
                Snapshots.config(check, Snapshots.facts())).getFirst();

        assertThat(finding.messageKey()).isEqualTo("finding.MEDIA_PLAYABLE.video");
        assertThat(finding.subjectKey()).isEqualTo("https://example.com/fehlt.mp4");
        assertThat(check.messageKeys()).contains(finding.messageKey());
    }

    @Test
    void anAudioElementWithoutMetadataIsReportedAsAudio() {
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/medien")
                        .media(MediaKind.AUDIO, "https://example.com/ton.wav", 0, 0.0, null).build(),
                Snapshots.config(check, Snapshots.facts())))
                .singleElement()
                .satisfies(finding ->
                        assertThat(finding.messageKey()).isEqualTo("finding.MEDIA_PLAYABLE.audio"));
    }

    @Test
    void mediaThatLoadedItsMetadataAndHasADurationIsNotReported() {
        // Spec 7.1: readyState >= 1 and duration > 0 together, because either alone lies.
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/medien")
                        .media(MediaKind.AUDIO, "https://example.com/ton.wav", 1, 0.5, null).build(),
                Snapshots.config(check, Snapshots.facts()))).isEmpty();
    }
}