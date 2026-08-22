package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.support.Snapshots;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConsoleErrorsCheckTest {

    private final ConsoleErrorsCheck check = new ConsoleErrorsCheck();

    @Test
    void anUncaughtScriptErrorIsReported() {
        CheckFinding finding = check.evaluate(
                Snapshots.page("https://example.com/")
                        .consoleError("Uncaught TypeError: kunde is not defined").build(),
                Snapshots.config(check, Snapshots.facts())).getFirst();

        assertThat(finding.severity()).isEqualTo(Severity.INFO);
        assertThat(finding.subjectKey()).isEqualTo("Uncaught TypeError: kunde is not defined");
        assertThat(check.messageKeys()).contains(finding.messageKey());
    }

    @Test
    void aFailedSubresourceIsNotAScriptError() {
        // "Failed to load resource" is what a missing image logs. IMAGE_BROKEN and DEAD_LINK
        // already report those with the URL attached; repeating them here is pure noise.
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/")
                        .consoleError("Failed to load resource: the server responded with a status of 404")
                        .build(),
                Snapshots.config(check, Snapshots.facts()))).isEmpty();
    }

    @Test
    void aConfiguredIgnoreSubstringSilencesAMessageRegardlessOfCase() {
        // Deviation D16: substrings, not the crawler's anchored URL globs. What a colleague
        // types into this list is a fragment of a message, not a path pattern.
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/")
                        .consoleError("Cookiebot: consent not given").build(),
                Snapshots.config(check, Snapshots.facts(), Map.of("ignorePatterns",
                        List.of("cookiebot"))))).isEmpty();
    }

    @Test
    void theSameMessageTwiceOnOnePageIsOneFinding() {
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/")
                        .consoleError("Uncaught TypeError: x")
                        .consoleError("Uncaught TypeError: x").build(),
                Snapshots.config(check, Snapshots.facts()))).hasSize(1);
    }

    @Test
    void aVeryLongMessageIsTruncatedSoTheSubjectStaysAUsableKey() {
        String long1 = "Uncaught Error: " + "x".repeat(500);
        CheckFinding finding = check.evaluate(
                Snapshots.page("https://example.com/").consoleError(long1).build(),
                Snapshots.config(check, Snapshots.facts())).getFirst();

        assertThat(finding.subjectKey()).hasSize(200);
    }

    @Test
    void aMessageWhoseCollapsedFormIsExactly200CharactersIsNotTruncated() {
        String message = "x".repeat(200);
        CheckFinding finding = check.evaluate(
                Snapshots.page("https://example.com/").consoleError(message).build(),
                Snapshots.config(check, Snapshots.facts())).getFirst();

        assertThat(finding.subjectKey()).isEqualTo(message);
    }

    @Test
    void whitespaceCollapseCanBringALongMessageBelowTheLengthCap() {
        String message = "Uncaught Error: a" + " ".repeat(300) + "b";
        CheckFinding finding = check.evaluate(
                Snapshots.page("https://example.com/").consoleError(message).build(),
                Snapshots.config(check, Snapshots.facts())).getFirst();

        assertThat(finding.subjectKey()).isEqualTo("Uncaught Error: a b");
    }

    @Test
    void truncationDoesNotSplitASurrogatePair() {
        // An emoji is a surrogate pair; the 200-code-point cut must not land between its halves.
        String emoji = "\uD83D\uDE00";
        String message = "x".repeat(199) + emoji + "y";
        CheckFinding finding = check.evaluate(
                Snapshots.page("https://example.com/").consoleError(message).build(),
                Snapshots.config(check, Snapshots.facts())).getFirst();

        assertThat(finding.subjectKey()).hasSize(201).endsWith(emoji);
    }
}