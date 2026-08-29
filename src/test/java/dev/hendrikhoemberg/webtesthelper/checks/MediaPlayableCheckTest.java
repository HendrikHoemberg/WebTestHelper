package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.MediaKind;
import dev.hendrikhoemberg.webtesthelper.model.UrlStatus;
import dev.hendrikhoemberg.webtesthelper.model.UrlVerification;
import dev.hendrikhoemberg.webtesthelper.support.Snapshots;
import org.junit.jupiter.api.Test;

import java.time.Instant;

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

    @Test
    void twoSourceLessElementsAreTwoFindingsEachNamingThePage() {
        // A <video> and an <audio> with no src at all both collapse onto the page URL, so the
        // dedupe key cannot be the URL — each element is its own broken thing.
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/medien")
                        .media(MediaKind.VIDEO, 0, 0.0, null)
                        .media(MediaKind.AUDIO, 0, 0.0, null).build(),
                Snapshots.config(check, Snapshots.facts())))
                .hasSize(2)
                .extracting(CheckFinding::messageKey)
                .containsExactly("finding.MEDIA_PLAYABLE.video", "finding.MEDIA_PLAYABLE.audio");
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/medien")
                        .media(MediaKind.VIDEO, 0, 0.0, null)
                        .media(MediaKind.AUDIO, 0, 0.0, null).build(),
                Snapshots.config(check, Snapshots.facts())))
                .extracting(CheckFinding::subjectKey)
                .containsExactly("https://example.com/medien", "https://example.com/medien");
    }

    @Test
    void theSameBrokenSourceTwiceOnAPageIsOneFinding() {
        // One broken file is one broken thing regardless of how many elements use it.
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/medien")
                        .media(MediaKind.VIDEO, "https://example.com/fehlt.mp4", 0, 0.0, null)
                        .media(MediaKind.VIDEO, "https://example.com/fehlt.mp4", 0, 0.0, null).build(),
                Snapshots.config(check, Snapshots.facts()))).hasSize(1);
    }

    @Test
    void aMediaSourceWithAVerificationCarriesThatEvidence() {
        UrlVerification dead = new UrlVerification("https://example.com/fehlt.mp4",
                UrlStatus.DEAD, 404, "text/plain", 0, null, "Not Found", Instant.EPOCH,
                "HEAD https://example.com/fehlt.mp4\nUser-Agent: WebTestHelper/1.0\n",
                "404\ncontent-type: text/plain\n");

        CheckFinding finding = check.evaluate(
                Snapshots.page("https://example.com/medien")
                        .media(MediaKind.VIDEO, "https://example.com/fehlt.mp4", 0, 0.0,
                                "MEDIA_ERR_SRC_NOT_SUPPORTED").build(),
                Snapshots.config(check, Snapshots.facts(dead))).getFirst();

        assertThat(finding.evidence().httpStatus()).isEqualTo(404);
        assertThat(finding.evidence().requestDetail())
                .contains("HEAD https://example.com/fehlt.mp4");
        assertThat(finding.evidence().responseDetail()).contains("404");
        assertThat(finding.evidence().responseDetail())
                .contains("MEDIA_ERR_SRC_NOT_SUPPORTED");
    }

    @Test
    void aMediaSourceWithoutAVerificationKeepsTheBrowserErrorCodeAsEvidence() {
        CheckFinding finding = check.evaluate(
                Snapshots.page("https://example.com/medien")
                        .media(MediaKind.VIDEO, "https://example.com/fehlt.mp4", 0, 0.0,
                                "MEDIA_ERR_SRC_NOT_SUPPORTED").build(),
                Snapshots.config(check, Snapshots.facts())).getFirst();

        assertThat(finding.evidence().responseDetail())
                .isEqualTo("MEDIA_ERR_SRC_NOT_SUPPORTED");
        assertThat(finding.evidence().requestDetail()).isNull();
    }

    @Test
    void aMediaElementWithA200SourceKeepsItsBrowserErrorCode() {
        UrlVerification ok = new UrlVerification("https://example.com/clip.mp4",
                UrlStatus.OK, 200, "video/mp4", 4096, null, null, Instant.EPOCH,
                "GET https://example.com/clip.mp4\nRange: bytes=0-1023\n",
                "200\ncontent-type: video/mp4\ncontent-length: 4096\n");

        CheckFinding finding = check.evaluate(
                Snapshots.page("https://example.com/medien")
                        .media(MediaKind.VIDEO, "https://example.com/clip.mp4", 0, 0.0,
                                "MEDIA_ERR_DECODE").build(),
                Snapshots.config(check, Snapshots.facts(ok))).getFirst();

        assertThat(finding.evidence().httpStatus()).isEqualTo(200);
        assertThat(finding.evidence().requestDetail()).contains("GET https://example.com/clip.mp4");
        assertThat(finding.evidence().responseDetail()).contains("200");
        assertThat(finding.evidence().responseDetail()).contains("MEDIA_ERR_DECODE");
    }
}
