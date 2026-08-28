package dev.hendrikhoemberg.webtesthelper.runner;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import dev.hendrikhoemberg.webtesthelper.model.JourneyStep;
import dev.hendrikhoemberg.webtesthelper.model.LocatorCandidate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves a step's ranked locator candidates against a live Playwright page (§10.2).
 *
 * <p>Candidates are evaluated in {@code (strategy.ordinal(), rank)} order. The first candidate
 * resolving to <strong>exactly one</strong> match ({@code count() == 1}) wins. If the winner
 * is not the primary candidate, {@link LocatorMatch#drifted()} is reported as {@code true}.
 *
 * <p>A candidate that cannot even be evaluated — an unparsable role, a syntactically invalid CSS
 * selector — is treated as not matching rather than as an error. One bad candidate emitted by the
 * recorder must not disable the ladder that exists to survive bad candidates.
 */
public final class LocatorResolver {

    private static final Logger log = LoggerFactory.getLogger(LocatorResolver.class);

    /** Re-try interval for {@link #resolveWithin}; a compromise between latency and CDP chatter. */
    static final int POLL_INTERVAL_MS = 50;

    private LocatorResolver() {
    }

    /**
     * Resolves the locator candidates of the given step against the page.
     *
     * @param page the live Playwright page
     * @param step the journey step containing locator candidates
     * @return the winning locator match, or {@link Optional#empty()} if no candidate uniquely matched
     */
    public static Optional<LocatorMatch> resolve(Page page, JourneyStep step) {
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(step, "step");
        return resolve(page, step.locatorCandidates());
    }

    /**
     * Resolves a list of locator candidates against the page.
     *
     * @param page       the live Playwright page
     * @param candidates the candidate locator list
     * @return the winning locator match, or {@link Optional#empty()} if no candidate uniquely matched
     */
    public static Optional<LocatorMatch> resolve(Page page, List<LocatorCandidate> candidates) {
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(candidates, "candidates");
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        List<LocatorCandidate> sorted = inLadderOrder(candidates);
        LocatorCandidate primary = sorted.get(0);

        for (LocatorCandidate candidate : sorted) {
            Locator locator = uniqueMatchOrNull(page, candidate);
            if (locator != null) {
                boolean drifted = !candidate.equals(primary);
                return Optional.of(new LocatorMatch(locator, candidate, drifted));
            }
        }

        return Optional.empty();
    }

    /**
     * Resolves the step's candidates, re-trying the whole ladder until the budget is spent.
     *
     * <p>§10.4 asks replay to auto-wait. {@link #resolve} deliberately snapshots the DOM with
     * {@code count()} so that ranking a candidate costs nothing; waiting is therefore this
     * method's job, and it waits on the <em>ladder</em>, not on the primary candidate — a step
     * that has drifted resolves on the first pass instead of paying the full timeout first.
     *
     * @param page      the live Playwright page
     * @param step      the journey step containing locator candidates
     * @param timeoutMs how long to keep re-trying, in milliseconds
     * @return the winning locator match, or {@link Optional#empty()} if the budget ran out
     */
    public static Optional<LocatorMatch> resolveWithin(Page page, JourneyStep step, int timeoutMs) {
        Objects.requireNonNull(step, "step");
        return resolveWithin(page, step.locatorCandidates(), timeoutMs);
    }

    /**
     * Resolves a candidate list, re-trying the whole ladder until the budget is spent.
     *
     * @param page       the live Playwright page
     * @param candidates the candidate locator list
     * @param timeoutMs  how long to keep re-trying, in milliseconds
     * @return the winning locator match, or {@link Optional#empty()} if the budget ran out
     */
    public static Optional<LocatorMatch> resolveWithin(Page page, List<LocatorCandidate> candidates, int timeoutMs) {
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(candidates, "candidates");

        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (true) {
            Optional<LocatorMatch> match = resolve(page, candidates);
            if (match.isPresent()) {
                return match;
            }
            long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
            if (remainingMs <= 0) {
                return Optional.empty();
            }
            page.waitForTimeout(Math.min(POLL_INTERVAL_MS, remainingMs));
        }
    }

    /**
     * Returns the candidate's locator when it matches exactly one element, else {@code null}.
     * A candidate that cannot be evaluated at all — an unparsable role, a malformed selector —
     * counts as not matching: that is an authoring fault in one rung of the ladder, and the
     * remaining rungs are still worth trying.
     */
    private static Locator uniqueMatchOrNull(Page page, LocatorCandidate candidate) {
        try {
            Locator locator = toLocator(page, candidate);
            return locator.count() == 1 ? locator : null;
        } catch (RuntimeException e) {
            log.debug("Kandidat {}={} ist nicht auswertbar und wird übersprungen: {}",
                    candidate.strategy(), candidate.value(), e.getMessage());
            return null;
        }
    }

    /**
     * Returns the candidate's match count, or {@code -1} when the candidate cannot be evaluated.
     * Used by assertions over element sets, where "resolved" means "matched at all" rather than
     * "matched exactly once".
     */
    static int countOrMinusOne(Page page, LocatorCandidate candidate) {
        try {
            return toLocator(page, candidate).count();
        } catch (RuntimeException e) {
            log.debug("Kandidat {}={} ist nicht auswertbar und wird übersprungen: {}",
                    candidate.strategy(), candidate.value(), e.getMessage());
            return -1;
        }
    }

    /**
     * Orders candidates the way the ladder tries them: by strategy preference, then by rank.
     *
     * @param candidates the candidate locator list
     * @return the same candidates in ladder order
     */
    static List<LocatorCandidate> inLadderOrder(List<LocatorCandidate> candidates) {
        return candidates.stream()
                .sorted(Comparator.comparingInt((LocatorCandidate c) -> c.strategy().ordinal())
                        .thenComparingInt(LocatorCandidate::rank))
                .toList();
    }

    static Locator toLocator(Page page, LocatorCandidate candidate) {
        return switch (candidate.strategy()) {
            case TEST_ID -> page.getByTestId(candidate.value());
            case ROLE -> resolveRole(page, candidate.value());
            case LABEL -> page.getByLabel(candidate.value());
            case ID -> resolveId(page, candidate.value());
            case TEXT -> page.getByText(candidate.value());
            case CSS -> page.locator(candidate.value());
        };
    }

    private static Locator resolveId(Page page, String value) {
        String id = value.startsWith("#") ? value.substring(1) : value;
        return page.locator("id=" + id);
    }

    private static Locator resolveRole(Page page, String value) {
        String input = value.trim();
        if (input.startsWith("getByRole(") && input.endsWith(")")) {
            input = input.substring("getByRole(".length(), input.length() - 1).trim();
        }
        if (input.startsWith("role=")) {
            input = input.substring("role=".length()).trim();
        }

        String roleName;
        String accessibleName = null;

        int bracketStart = input.indexOf('[');
        if (bracketStart != -1 && input.endsWith("]")) {
            roleName = input.substring(0, bracketStart).trim();
            String inside = input.substring(bracketStart + 1, input.length() - 1).trim();
            if (inside.startsWith("name=")) {
                accessibleName = stripQuotes(inside.substring("name=".length()).trim());
            } else {
                accessibleName = stripQuotes(inside);
            }
        } else if (input.contains(",")) {
            int comma = input.indexOf(',');
            roleName = stripQuotes(input.substring(0, comma).trim());
            String rest = input.substring(comma + 1).trim();
            if (rest.startsWith("name=")) {
                accessibleName = stripQuotes(rest.substring("name=".length()).trim());
            } else {
                accessibleName = stripQuotes(rest);
            }
        } else if (input.contains(":")) {
            int colon = input.indexOf(':');
            roleName = stripQuotes(input.substring(0, colon).trim());
            accessibleName = stripQuotes(input.substring(colon + 1).trim());
        } else {
            roleName = stripQuotes(input);
        }

        AriaRole role = parseAriaRole(roleName);
        if (accessibleName != null && !accessibleName.isEmpty()) {
            return page.getByRole(role, new Page.GetByRoleOptions().setName(accessibleName));
        }
        return page.getByRole(role);
    }

    private static String stripQuotes(String s) {
        String trimmed = s.trim();
        if ((trimmed.startsWith("'") && trimmed.endsWith("'")) || (trimmed.startsWith("\"") && trimmed.endsWith("\""))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static AriaRole parseAriaRole(String roleName) {
        String normalized = roleName.toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return AriaRole.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            return switch (normalized) {
                case "IMAGE" -> AriaRole.IMG;
                case "INPUT" -> AriaRole.TEXTBOX;
                case "SELECT" -> AriaRole.COMBOBOX;
                default -> throw new IllegalArgumentException("Unknown AriaRole: " + roleName, e);
            };
        }
    }
}
