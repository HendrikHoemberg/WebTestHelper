package dev.hendrikhoemberg.webtesthelper.checks;

import com.microsoft.playwright.Dialog;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.RunSnapshots;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Button reachability interaction check (spec 7.2, D77, D83, D85, D86, D87, D88, D104).
 *
 * <p>Clicks safe interactive controls on key pages and verifies whether any observable
 * change occurred (URL navigation, DOM digest change, modal dialog, or popup window) —
 * and, when the click navigated, whether the page it landed on actually exists (D104).
 */
public final class ButtonReachabilityCheck implements InteractionCheck {

    static final String DEAD = "finding.BUTTON_REACHABILITY.dead";
    static final String DEAD_TARGET = "finding.BUTTON_REACHABILITY.deadTarget";

    private static final String CLICKABLES_SCRIPT;
    private static final String DOM_DIGEST_SCRIPT;

    static {
        try {
            CLICKABLES_SCRIPT = new ClassPathResource("checks/clickables.js")
                    .getContentAsString(StandardCharsets.UTF_8);
            DOM_DIGEST_SCRIPT = new ClassPathResource("checks/dom-digest.js")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("checks/clickables.js oder checks/dom-digest.js fehlt im Klassenpfad", e);
        }
    }

    @Override
    public CheckType type() {
        return CheckType.BUTTON_REACHABILITY;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.WARN;
    }

    @Override
    public Set<String> messageKeys() {
        return Set.of(DEAD, DEAD_TARGET);
    }

    @Override
    public List<NormalizedUrl> targets(RunSnapshots snapshots, SiteContext site, int maxTargets) {
        return InteractionTargets.keyPagesOrHomepage(snapshots, site, maxTargets);
    }

    @Override
    public List<CheckFinding> evaluate(Page page, SiteContext site, CheckConfig config) {
        if (page == null) {
            return List.of();
        }

        String rawInitialUrl = page.url();
        NormalizedUrl fallback = site != null ? site.baseUrl() : null;
        NormalizedUrl targetUrl = rawInitialUrl != null
                ? UrlNormalizer.normalize(rawInitialUrl).orElse(fallback)
                : fallback;

        if (targetUrl == null) {
            return List.of();
        }

        int maxButtons = config != null ? config.option("maxButtons", 10) : 10;
        List<Clickables.Clickable> harvested = harvest(page);
        List<Clickables.Clickable> candidates = Clickables.select(harvested, targetUrl, maxButtons);

        if (candidates.isEmpty()) {
            return List.of();
        }

        // Restlessness probe: DOM must be stable without user interaction
        String initialDigest = readDigest(page);
        page.waitForTimeout(500);
        String secondDigest = readDigest(page);

        if (!Objects.equals(initialDigest, secondDigest)) {
            throw new CheckAbstainedException(type(), targetUrl.value(),
                    "DOM ändert sich fortlaufend ohne Interaktion (unruhige Seite)");
        }

        List<CheckFinding> findings = new ArrayList<>();
        Severity severity = config != null ? config.severity() : defaultSeverity();

        for (Clickables.Clickable candidate : candidates) {
            AtomicBoolean dialogSeen = new AtomicBoolean(false);
            Consumer<Dialog> dialogHandler = dialog -> {
                dialogSeen.set(true);
                try {
                    dialog.dismiss();
                } catch (Exception ignored) {
                }
            };

            AtomicBoolean popupSeen = new AtomicBoolean(false);
            Consumer<Page> popupHandler = popup -> {
                popupSeen.set(true);
                try {
                    popup.close();
                } catch (Exception ignored) {
                }
            };

            // Spec 7.2 asks whether the control "navigates somewhere valid", and the destination of
            // a script-driven navigation is never a link in the DOM — so DEAD_LINK never resolves
            // it and the crawl never visits it. The response status is the only place the answer
            // exists, and it exists only while the navigation is happening.
            List<NavigationResponse> navigations = Collections.synchronizedList(new ArrayList<>());
            Consumer<Response> responseHandler = response -> {
                try {
                    if (response.request().isNavigationRequest()
                            && page.mainFrame().equals(response.frame())) {
                        navigations.add(new NavigationResponse(response.url(), response.status()));
                    }
                } catch (PlaywrightException ignored) {
                    // The response object outlived its frame; nothing to record.
                }
            };

            page.onDialog(dialogHandler);
            page.onPopup(popupHandler);
            page.onResponse(responseHandler);

            boolean navigated = false;
            try {
                // Re-tag first when an earlier in-page interaction reloaded the document without
                // changing the URL. Harvesting writes data-wth-btn onto every control, which alters
                // outerHTML; doing it after the baseline digest would make every later candidate
                // look alive no matter what its click did.
                if (page.locator("[data-wth-btn]").count() == 0) {
                    harvest(page);
                }

                String beforeUrl = page.url();
                NormalizedUrl beforeNormalized = beforeUrl != null
                        ? UrlNormalizer.normalize(beforeUrl).orElse(null)
                        : null;
                String beforeDigest = readDigest(page);

                Locator locator = page.locator("[data-wth-btn='" + candidate.index() + "']");
                locator.click(new Locator.ClickOptions().setTimeout(2000));
                page.waitForTimeout(500);

                String afterUrl = page.url();
                NormalizedUrl afterNormalized = afterUrl != null
                        ? UrlNormalizer.normalize(afterUrl).orElse(null)
                        : null;
                String afterDigest = readDigest(page);

                boolean urlChanged = !Objects.equals(beforeNormalized, afterNormalized);
                boolean digestChanged = !Objects.equals(beforeDigest, afterDigest);
                boolean dialogAppeared = dialogSeen.get();
                boolean popupOpened = popupSeen.get();

                boolean hadEffect = urlChanged || digestChanged || dialogAppeared || popupOpened;

                String subjectKey = candidate.label() != null && !candidate.label().isBlank()
                        ? candidate.label().trim()
                        : candidate.tag() + "#" + candidate.index();

                if (!hadEffect) {
                    findings.add(new CheckFinding(
                            type(),
                            severity,
                            subjectKey,
                            targetUrl,
                            DEAD,
                            List.of(subjectKey, targetUrl.value()),
                            Evidence.NONE));
                } else if (urlChanged) {
                    // A navigation with no response at all is a same-document one (pushState, a
                    // fragment router): there is no status to judge, and silence is the honest
                    // answer rather than a finding.
                    NavigationResponse destination = destinationOf(navigations, afterUrl);
                    if (destination != null && destination.status() >= 400) {
                        findings.add(new CheckFinding(
                                type(),
                                severity,
                                subjectKey,
                                targetUrl,
                                DEAD_TARGET,
                                List.of(subjectKey, destination.url(),
                                        String.valueOf(destination.status())),
                                Evidence.NONE));
                    }
                }

                if (urlChanged) {
                    navigated = true;
                }
            } catch (Exception ignored) {
            } finally {
                page.offDialog(dialogHandler);
                page.offPopup(popupHandler);
                // Before navigating back, or the return trip records itself as the destination.
                page.offResponse(responseHandler);

                if (navigated) {
                    try {
                        if (rawInitialUrl != null) {
                            page.navigate(rawInitialUrl);
                            page.waitForLoadState();
                            harvest(page);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        // D88: leave page on its initial target URL
        if (rawInitialUrl != null && !Objects.equals(page.url(), rawInitialUrl)) {
            try {
                page.navigate(rawInitialUrl);
                page.waitForLoadState();
            } catch (Exception ignored) {
            }
        }

        return List.copyOf(findings);
    }

    private record NavigationResponse(String url, int status) {
    }

    /**
     * The response the browser finally landed on. Redirects arrive as their own navigation
     * responses, so the one matching the URL the page ended up at is the destination; when nothing
     * matches — a rewritten history entry, an unusual redirect — the last one recorded is the
     * closest available answer.
     */
    private static NavigationResponse destinationOf(List<NavigationResponse> navigations, String finalUrl) {
        synchronized (navigations) {
            if (navigations.isEmpty()) {
                return null;
            }
            for (int i = navigations.size() - 1; i >= 0; i--) {
                if (Objects.equals(navigations.get(i).url(), finalUrl)) {
                    return navigations.get(i);
                }
            }
            return navigations.get(navigations.size() - 1);
        }
    }

    private static List<Clickables.Clickable> harvest(Page page) {
        if (page == null) {
            return List.of();
        }
        try {
            Object res = page.evaluate(CLICKABLES_SCRIPT);
            if (res instanceof List<?> list) {
                List<Clickables.Clickable> clickables = new ArrayList<>(list.size());
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        int index = map.get("index") instanceof Number n ? n.intValue() : 0;
                        String tag = Objects.toString(map.get("tag"), "");
                        String type = Objects.toString(map.get("type"), "");
                        String label = Objects.toString(map.get("label"), "");
                        String href = Objects.toString(map.get("href"), "");
                        boolean inForm = Boolean.TRUE.equals(map.get("inForm"));
                        boolean disabled = Boolean.TRUE.equals(map.get("disabled"));
                        boolean visible = Boolean.TRUE.equals(map.get("visible"));
                        String target = Objects.toString(map.get("target"), "");
                        clickables.add(new Clickables.Clickable(
                                index, tag, type, label, href, inForm, disabled, visible, target));
                    }
                }
                return clickables;
            }
        } catch (PlaywrightException ignored) {
        }
        return List.of();
    }

    private static String readDigest(Page page) {
        if (page == null) {
            return "";
        }
        try {
            Object res = page.evaluate(DOM_DIGEST_SCRIPT);
            return res != null ? res.toString() : "";
        } catch (Exception e) {
            return "";
        }
    }
}
