package dev.hendrikhoemberg.webtesthelper.checks;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import dev.hendrikhoemberg.webtesthelper.checks.LanguageSwitchers.LocaleLink;
import dev.hendrikhoemberg.webtesthelper.checks.LanguageSwitchers.LocaleTarget;
import dev.hendrikhoemberg.webtesthelper.checks.LanguageSwitchers.Observation;
import dev.hendrikhoemberg.webtesthelper.checks.LanguageSwitchers.SwitchVerdict;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Language switcher interaction check (spec 7.2, D77, D83, D84, D87, D88).
 *
 * <p>Clicks language switch links and verifies that the URL changed, {@code <html lang>} changed,
 * and visible content was actually translated.
 */
public final class LanguageSwitcherCheck implements InteractionCheck {

    static final String NO_NAVIGATION = "finding.LANGUAGE_SWITCHER.noNavigation";
    static final String LANG_UNCHANGED = "finding.LANGUAGE_SWITCHER.langUnchanged";
    static final String SAME_CONTENT = "finding.LANGUAGE_SWITCHER.sameContent";

    /**
     * Bounds a single locale click, matching {@code ButtonReachabilityCheck}. A switcher link that
     * cannot be clicked must not consume the runner's whole per-check budget and starve the
     * locales behind it.
     */
    private static final int CLICK_TIMEOUT_MS = 2000;

    private static final String SCRIPT;

    static {
        try {
            SCRIPT = new ClassPathResource("checks/locale-links.js")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("checks/locale-links.js fehlt im Klassenpfad", e);
        }
    }

    @Override
    public CheckType type() {
        return CheckType.LANGUAGE_SWITCHER;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.ERROR;
    }

    @Override
    public Set<String> messageKeys() {
        return Set.of(NO_NAVIGATION, LANG_UNCHANGED, SAME_CONTENT);
    }

    @Override
    public List<NormalizedUrl> targets(RunSnapshots snapshots, SiteContext site, int maxTargets) {
        return InteractionTargets.homepage(snapshots, site);
    }

    @Override
    public List<CheckFinding> evaluate(Page page, SiteContext site, CheckConfig config) {
        if (page == null) {
            return List.of();
        }

        NormalizedUrl fallback = site != null ? site.baseUrl() : null;
        NormalizedUrl baselineUrl = page.url() != null
                ? UrlNormalizer.normalize(page.url()).orElse(fallback)
                : fallback;

        if (baselineUrl == null) {
            return List.of();
        }

        String rawBaselineUrl = page.url();
        String baselineLang = getHtmlLang(page);
        String baselineText = getBodyText(page);
        Observation before = new Observation(baselineUrl, baselineLang, baselineText);

        List<LocaleLink> harvested = harvest(page);
        int maxLocales = config != null ? config.option("maxLocales", 3) : 3;
        int maxTextDistance = config != null ? config.option("maxTextDistance", 12) : 12;
        List<LocaleTarget> targets = LanguageSwitchers.select(harvested, baselineUrl, baselineLang, maxLocales);

        if (targets.isEmpty()) {
            return List.of();
        }

        List<CheckFinding> findings = new ArrayList<>();
        Severity severity = config != null ? config.severity() : defaultSeverity();

        for (LocaleTarget target : targets) {
            try {
                Locator locator = page.locator("[data-wth-locale='" + target.index() + "']");
                locator.click(new Locator.ClickOptions().setTimeout(CLICK_TIMEOUT_MS));
                page.waitForLoadState();

                NormalizedUrl afterUrl = page.url() != null
                        ? UrlNormalizer.normalize(page.url()).orElse(fallback)
                        : fallback;
                String afterLang = getHtmlLang(page);
                String afterText = getBodyText(page);
                Observation after = new Observation(afterUrl, afterLang, afterText);

                SwitchVerdict verdict = LanguageSwitchers.verdict(before, after, maxTextDistance);
                if (verdict != SwitchVerdict.OK) {
                    String messageKey;
                    List<String> args;
                    switch (verdict) {
                        case NO_NAVIGATION -> {
                            messageKey = NO_NAVIGATION;
                            args = List.of(target.label());
                        }
                        case LANG_UNCHANGED -> {
                            messageKey = LANG_UNCHANGED;
                            args = List.of(target.url().value(), target.label(), afterLang);
                        }
                        case SAME_CONTENT -> {
                            messageKey = SAME_CONTENT;
                            args = List.of(target.label(), target.url().value());
                        }
                        default -> throw new IllegalStateException("Unexpected verdict: " + verdict);
                    }

                    findings.add(new CheckFinding(
                            type(),
                            severity,
                            target.url().value(),
                            baselineUrl,
                            messageKey,
                            args,
                            Evidence.NONE));
                }
            } catch (Exception ignored) {
            } finally {
                try {
                    if (rawBaselineUrl != null) {
                        page.navigate(rawBaselineUrl);
                        page.waitForLoadState();
                        harvest(page);
                    }
                } catch (Exception ignored) {
                }
            }
        }

        return List.copyOf(findings);
    }

    private static List<LocaleLink> harvest(Page page) {
        if (page == null) {
            return List.of();
        }
        try {
            Object res = page.evaluate(SCRIPT);
            if (res instanceof List<?> list) {
                List<LocaleLink> links = new ArrayList<>(list.size());
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        int index = map.get("index") instanceof Number n ? n.intValue() : 0;
                        String href = Objects.toString(map.get("href"), "");
                        String hreflang = Objects.toString(map.get("hreflang"), "");
                        String label = Objects.toString(map.get("label"), "");
                        boolean visible = Boolean.TRUE.equals(map.get("visible"));
                        links.add(new LocaleLink(index, href, hreflang, label, visible));
                    }
                }
                return links;
            }
        } catch (PlaywrightException ignored) {
        }
        return List.of();
    }

    private static String getHtmlLang(Page page) {
        try {
            Object res = page.evaluate("() => document.documentElement.lang || document.documentElement.getAttribute('lang') || ''");
            return res != null ? res.toString().trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String getBodyText(Page page) {
        try {
            Object res = page.evaluate("() => (document.body && document.body.innerText) || ''");
            return res != null ? res.toString() : "";
        } catch (Exception e) {
            return "";
        }
    }
}
