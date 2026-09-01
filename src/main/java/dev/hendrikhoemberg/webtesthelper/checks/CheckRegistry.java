package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckCategory;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;

import dev.hendrikhoemberg.webtesthelper.model.Mailbox;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Every check the system can run, held by kind (spec 7.3).
 *
 * <p>Deviation D15: an explicit list rather than component scanning, because spec 5.1 says this
 * module holds no Spring. The property that matters is preserved — adding the twelfth check
 * means adding one class and one line <em>here</em>, never touching the runner — and
 * {@code CheckRegistryTest} fails the build if a {@link CheckType} ends up with no
 * implementation or two.
 */
public final class CheckRegistry {

    private final List<PageCheck> pageChecks;
    private final List<SiteCheck> siteChecks;
    private final List<InteractionCheck> interactionChecks;

    public CheckRegistry(List<PageCheck> pageChecks, List<SiteCheck> siteChecks) {
        this(pageChecks, siteChecks, List.of());
    }

    public CheckRegistry(List<PageCheck> pageChecks, List<SiteCheck> siteChecks, List<InteractionCheck> interactionChecks) {
        this.pageChecks = List.copyOf(pageChecks);
        this.siteChecks = List.copyOf(siteChecks);
        this.interactionChecks = List.copyOf(interactionChecks);
    }

    public static CheckRegistry standard() {
        return standard(Mailbox.UNCONFIGURED);
    }

    public static CheckRegistry standard(Mailbox mailbox) {
        return new CheckRegistry(
                List.of(new PageUnreachableCheck(),
                        new PageStatusCheck(),
                        new RedirectChainCheck(),
                        new ImageBrokenCheck(),
                        new MediaPlayableCheck(),
                        new MixedContentCheck(),
                        new IframeEmbedCheck(),
                        new ConsoleErrorsCheck(),
                        new DeadLinkCheck(),
                        new FileDownloadCheck()),
                List.of(new TlsCertCheck(),
                        new HreflangCheck(),
                        new SitemapConsistencyCheck()),
                List.of(new CookieBannerCheck(),
                        new LanguageSwitcherCheck(),
                        new ButtonReachabilityCheck(),
                        new ContactFormCheck(mailbox)));
    }

    public List<PageCheck> pageChecks() {
        return pageChecks;
    }

    public List<SiteCheck> siteChecks() {
        return siteChecks;
    }

    public List<InteractionCheck> interactionChecks() {
        return interactionChecks;
    }

    public List<CheckDescriptor> all() {
        return Stream.of(pageChecks.stream(), siteChecks.stream(), interactionChecks.stream())
                .flatMap(s -> s)
                .map(CheckDescriptor.class::cast)
                .toList();
    }

    public Set<CheckType> coveredTypes() {
        Set<CheckType> types = EnumSet.noneOf(CheckType.class);
        all().forEach(check -> types.add(check.type()));
        return types;
    }

    /** The display category of a check, with an anonymous fallback so nothing renders blankness. */
    public CheckCategory category(CheckType type) {
        CheckCategory category = CATEGORIES.get(type);
        return category == null ? CheckCategory.TECHNIK : category;
    }

    /** Categories in the order the configuration screen renders their accordions. */
    public List<CheckCategory> categories() {
        return List.of(CheckCategory.INHALT, CheckCategory.TECHNIK, CheckCategory.RECHT);
    }

    private static final Map<CheckType, CheckCategory> CATEGORIES = categoryMap();

    private static Map<CheckType, CheckCategory> categoryMap() {
        Map<CheckType, CheckCategory> map = new EnumMap<>(CheckType.class);
        for (CheckType type : List.of(CheckType.PAGE_STATUS, CheckType.PAGE_UNREACHABLE,
                CheckType.DEAD_LINK, CheckType.IMAGE_BROKEN, CheckType.MEDIA_PLAYABLE,
                CheckType.FILE_DOWNLOAD, CheckType.REDIRECT_CHAIN, CheckType.SITEMAP_CONSISTENCY,
                CheckType.HREFLANG)) {
            map.put(type, CheckCategory.INHALT);
        }
        for (CheckType type : List.of(CheckType.TLS_CERT, CheckType.MIXED_CONTENT,
                CheckType.CONSOLE_ERRORS, CheckType.IFRAME_EMBED)) {
            map.put(type, CheckCategory.TECHNIK);
        }
        for (CheckType type : List.of(CheckType.COOKIE_BANNER, CheckType.CONTACT_FORM,
                CheckType.LANGUAGE_SWITCHER, CheckType.BUTTON_REACHABILITY)) {
            map.put(type, CheckCategory.RECHT);
        }
        return map;
    }
}