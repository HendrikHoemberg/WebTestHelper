package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.ConsoleMessage;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.PageSnapshot;
import dev.hendrikhoemberg.webtesthelper.model.Severity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Uncaught JavaScript errors — <strong>off by default</strong> (spec 7.1). Real sites throw
 * console errors constantly: third-party scripts, tracking pixels, consent tools. Enabled by
 * default this check would make the very first report mostly noise, which is the one thing this
 * design cannot afford.
 *
 * <p>Two things make it survivable once a site does enable it. Messages about a subresource
 * that failed to load are dropped outright — {@code IMAGE_BROKEN} and {@code DEAD_LINK} already
 * report those with the URL attached, so repeating them here is duplication, not coverage. And
 * the site's own ignore list, {@code {"ignorePatterns": ["cookiebot", "gtm.js"]}}, is matched as
 * case-insensitive substrings (deviation D16): what a colleague types is a fragment of a
 * message, not a path pattern.
 *
 * <p>With this check enabled, a Maps billing or key failure reports twice — {@code
 * IFRAME_EMBED.maps} names the embed, {@code CONSOLE_ERRORS.uncaught} the raw provider error.
 * That is deliberate: the two checks answer different questions, and the second is opt-in.
 */
public final class ConsoleErrorsCheck implements PageCheck {

    static final String UNCAUGHT = "finding.CONSOLE_ERRORS.uncaught";
    static final int MAX_SUBJECT_LENGTH = 200;

    /** Owned by other checks, which can also say which file is missing. */
    private static final List<String> ALWAYS_IGNORED = List.of("failed to load resource");

    @Override
    public CheckType type() {
        return CheckType.CONSOLE_ERRORS;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.INFO;
    }

    @Override
    public Set<String> messageKeys() {
        return Set.of(UNCAUGHT);
    }

    @Override
    public List<CheckFinding> evaluate(PageSnapshot snapshot, CheckConfig config) {
        if (!snapshot.reachable()) {
            return List.of();
        }
        List<String> ignored = new ArrayList<>(ALWAYS_IGNORED);
        config.optionList("ignorePatterns").forEach(
                pattern -> ignored.add(pattern.toLowerCase(Locale.ROOT)));

        List<CheckFinding> findings = new ArrayList<>();
        Set<String> reported = new HashSet<>();
        for (ConsoleMessage message : snapshot.errors()) {
            String subject = normalise(message.text());
            if (subject.isEmpty() || !reported.add(subject)) {
                continue;
            }
            String lower = subject.toLowerCase(Locale.ROOT);
            if (ignored.stream().anyMatch(lower::contains)) {
                continue;
            }
            findings.add(new CheckFinding(type(), config.severity(), subject, snapshot.url(),
                    UNCAUGHT, List.of(subject),
                    new Evidence(snapshot.screenshotPath(), null, null, message.location(),
                            List.of(message.text()))));
        }
        return findings;
    }

    /**
     * The subject key doubles as the fingerprint input (spec 6.2), so it has to be stable
     * across runs: collapse the whitespace a stack trace brings and cap the length.
     */
    private static String normalise(String text) {
        if (text == null) {
            return "";
        }
        String collapsed = text.replaceAll("\\s+", " ").trim();
        if (collapsed.codePointCount(0, collapsed.length()) <= MAX_SUBJECT_LENGTH) {
            return collapsed;
        }
        return collapsed.substring(0, collapsed.offsetByCodePoints(0, MAX_SUBJECT_LENGTH));
    }
}