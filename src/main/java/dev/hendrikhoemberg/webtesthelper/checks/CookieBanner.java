package dev.hendrikhoemberg.webtesthelper.checks;

import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.WaitForSelectorState;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Heuristic cookie banner detection and consent acceptance (spec 7.2, D69, D57).
 *
 * <p>Shared between {@code CookieBannerCheck} (reporting undismissable overlays) and
 * the interaction runner (establishing consent so subsequent checks see an unblocked DOM).
 */
public final class CookieBanner {

    public record BannerOutcome(boolean present, String containerId, boolean dismissed, String acceptLabel) {
    }

    /**
     * The container must become hidden or detached within this long. Long enough for a CSS
     * fade-out, short enough that three targets do not add ten seconds to every run. Both callers
     * — the reporting check and the runner's consent-only path — share it, or the same banner
     * would be called dismissable by one and undismissable by the other.
     */
    public static final Duration DISMISSAL_WAIT = Duration.ofSeconds(3);

    private static final String SCRIPT;

    static {
        try {
            SCRIPT = new ClassPathResource("checks/cookie-banner.js")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("checks/cookie-banner.js fehlt im Klassenpfad", e);
        }
    }

    static final List<String> ACCEPT_LABELS = List.of(
            "Alle akzeptieren", "Alles akzeptieren", "Alle Cookies akzeptieren", "Alle zulassen", "Alle auswählen",
            "Alle annehmen", "Alles annehmen", "Zustimmen & weiter", "Akzeptieren & weiter", "Einverstanden & weiter",
            "Accept all", "Allow all", "Akzeptieren", "Zustimmen", "Einverstanden",
            "Verstanden", "Ich stimme zu", "Accept", "Agree", "OK");

    private CookieBanner() {
    }

    /**
     * Detects an overlay matching common CMP patterns and attempts to accept it.
     *
     * <p>Idempotent and side-effect-free when no banner is present. Never throws for a page-level
     * reason; a Playwright timeout on dismissal wait is {@code dismissed=false}, not an exception.
     */
    public static BannerOutcome accept(Page page, Duration dismissalWait) {
        if (page == null) {
            return new BannerOutcome(false, null, false, null);
        }

        Frame targetFrame = null;
        String containerId = null;

        try {
            Object res = page.mainFrame().evaluate(SCRIPT);
            if (res != null) {
                targetFrame = page.mainFrame();
                containerId = res.toString();
            }
        } catch (PlaywrightException ignored) {
            // Page navigation or evaluation failed
        }

        if (targetFrame == null) {
            for (Frame frame : page.frames()) {
                if (frame.equals(page.mainFrame())) {
                    continue;
                }
                try {
                    Object res = frame.evaluate(SCRIPT);
                    if (res != null) {
                        targetFrame = frame;
                        containerId = res.toString();
                        break;
                    }
                } catch (PlaywrightException ignored) {
                    // Frame may be detached, navigating, or cross-origin restricted
                }
            }
        }

        if (targetFrame == null) {
            return new BannerOutcome(false, null, false, null);
        }

        Locator banner = targetFrame.locator("[data-wth-banner]");
        Locator chosenLocator = null;
        String chosenLabel = null;

        for (String testId : List.of("uc-accept-all-button", "uc-accept-button")) {
            try {
                Locator btn = banner.getByTestId(testId);
                if (btn.count() > 0 && btn.first().isVisible()) {
                    chosenLocator = btn.first();
                    chosenLabel = testId;
                    break;
                }
            } catch (PlaywrightException ignored) {
            }
        }

        if (chosenLocator == null) {
            for (String label : ACCEPT_LABELS) {
                try {
                    Locator button = banner.getByRole(AriaRole.BUTTON,
                            new Locator.GetByRoleOptions().setName(label));
                    if (button.count() > 0 && button.first().isVisible()) {
                        chosenLocator = button.first();
                        chosenLabel = label;
                        break;
                    }
                    Locator link = banner.getByRole(AriaRole.LINK,
                            new Locator.GetByRoleOptions().setName(label));
                    if (link.count() > 0 && link.first().isVisible()) {
                        chosenLocator = link.first();
                        chosenLabel = label;
                        break;
                    }
                } catch (PlaywrightException ignored) {
                    // DOM mutated or element invalid
                }
            }
        }

        if (chosenLocator == null) {
            return new BannerOutcome(true, containerId, false, null);
        }

        try {
            chosenLocator.click();
        } catch (PlaywrightException e) {
            return new BannerOutcome(true, containerId, false, chosenLabel);
        }

        boolean dismissed = false;
        long timeoutMs = Math.max(0, (dismissalWait == null ? DISMISSAL_WAIT : dismissalWait).toMillis());
        try {
            banner.waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.HIDDEN)
                    .setTimeout(timeoutMs));
            dismissed = true;
        } catch (PlaywrightException e) {
            // On timeout, re-read the container once (D78's in-check retry) before answering dismissed=false
            try {
                dismissed = banner.count() == 0 || !banner.first().isVisible()
                        || (chosenLocator != null && (chosenLocator.count() == 0 || !chosenLocator.isVisible()));
            } catch (Exception ignored) {
                dismissed = true;
            }
        }

        return new BannerOutcome(true, containerId, dismissed, chosenLabel);
    }
}
