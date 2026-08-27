package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckType;

import java.util.EnumSet;
import java.util.List;
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
                        new ButtonReachabilityCheck()));
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
}