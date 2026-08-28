package dev.hendrikhoemberg.webtesthelper.runner;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import dev.hendrikhoemberg.webtesthelper.model.JourneyStep;
import dev.hendrikhoemberg.webtesthelper.model.LocatorCandidate;

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
 */
public final class LocatorResolver {

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

        List<LocatorCandidate> sorted = candidates.stream()
                .sorted(Comparator.comparingInt((LocatorCandidate c) -> c.strategy().ordinal())
                        .thenComparingInt(LocatorCandidate::rank))
                .toList();

        LocatorCandidate primary = sorted.get(0);

        for (LocatorCandidate candidate : sorted) {
            Locator locator = toLocator(page, candidate);
            if (locator.count() == 1) {
                boolean drifted = !candidate.equals(primary);
                return Optional.of(new LocatorMatch(locator, candidate, drifted));
            }
        }

        return Optional.empty();
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
