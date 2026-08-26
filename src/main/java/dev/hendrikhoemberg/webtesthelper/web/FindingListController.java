package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.checks.CheckRegistry;
import dev.hendrikhoemberg.webtesthelper.findings.FindingPage;
import dev.hendrikhoemberg.webtesthelper.findings.FindingProperties;
import dev.hendrikhoemberg.webtesthelper.findings.FindingQuery;
import dev.hendrikhoemberg.webtesthelper.findings.FindingService;
import dev.hendrikhoemberg.webtesthelper.model.ObservedStatus;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.TriageStatus;
import dev.hendrikhoemberg.webtesthelper.reporting.FindingView;
import dev.hendrikhoemberg.webtesthelper.reporting.FindingViewFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.List;
import java.util.Locale;

@Controller
public class FindingListController {

    private final SiteService siteService;
    private final FindingService findingService;
    private final FindingViewFactory findingViewFactory;
    private final CheckRegistry checkRegistry;
    private final FindingProperties findingProperties;

    public FindingListController(SiteService siteService, FindingService findingService,
                                 FindingViewFactory findingViewFactory, CheckRegistry checkRegistry,
                                 FindingProperties findingProperties) {
        this.siteService = siteService;
        this.findingService = findingService;
        this.findingViewFactory = findingViewFactory;
        this.checkRegistry = checkRegistry;
        this.findingProperties = findingProperties;
    }

    @GetMapping("/websites/{id}/befunde")
    public String list(@PathVariable("id") long siteId,
                       @ModelAttribute("filter") FindingFilterForm filter,
                       @RequestHeader(value = "HX-Request", required = false) String hxRequest,
                       Locale locale,
                       Model model) {
        SiteContext site = siteService.contextFor(siteId);
        FindingQuery query = filter.toQuery(siteId, findingProperties.pageSize());
        FindingPage page = findingService.search(query);

        List<FindingView> findingViews = page.findings().stream()
                .map(f -> findingViewFactory.of(f, locale))
                .toList();

        model.addAttribute("site", site);
        model.addAttribute("filter", filter);
        model.addAttribute("page", page);
        model.addAttribute("findings", findingViews);
        model.addAttribute("allSeverities", Severity.values());
        model.addAttribute("allTriageStatuses", TriageStatus.values());
        model.addAttribute("allObservedStatuses", ObservedStatus.values());
        model.addAttribute("allCheckDescriptors", checkRegistry.all());

        if ("true".equalsIgnoreCase(hxRequest)) {
            return "websites/befunde :: befundListe";
        }
        return "websites/befunde";
    }
}
