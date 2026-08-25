package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.findings.FindingService;
import dev.hendrikhoemberg.webtesthelper.findings.ReportSection;
import dev.hendrikhoemberg.webtesthelper.findings.RunDiff;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.runner.RunService;
import dev.hendrikhoemberg.webtesthelper.runner.RunSummary;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Handles run report views, live HTMX progress polling, and baseline acceptance.
 */
@Controller
@RequestMapping("/laeufe")
public class RunController {

    private final RunService runService;
    private final FindingService findingService;
    private final FindingViewFactory findingViewFactory;
    private final SiteService siteService;
    private final MessageSource messageSource;

    public RunController(RunService runService,
                         FindingService findingService,
                         FindingViewFactory findingViewFactory,
                         SiteService siteService,
                         MessageSource messageSource) {
        this.runService = runService;
        this.findingService = findingService;
        this.findingViewFactory = findingViewFactory;
        this.siteService = siteService;
        this.messageSource = messageSource;
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable("id") long id, Model model, Locale locale) {
        RunSummary run = runService.summary(id);
        SiteContext site = siteService.contextFor(run.siteId());

        if (run.status().isTerminal()) {
            RunDiff diff = findingService.diffOf(run.siteId(), id);
            Map<ReportSection, List<FindingView>> sections = findingViewFactory.of(diff, locale);
            model.addAttribute("diff", diff);
            model.addAttribute("sections", sections);
        } else {
            model.addAttribute("sections", Map.of());
        }

        model.addAttribute("run", run);
        model.addAttribute("site", site);
        return "laeufe/detail";
    }

    @GetMapping("/{id}/fortschritt")
    public String fortschritt(@PathVariable("id") long id, Model model, HttpServletResponse response) {
        RunSummary run = runService.summary(id);
        model.addAttribute("run", run);
        if (run.status().isTerminal()) {
            response.setHeader("HX-Refresh", "true");
        }
        return "fragments/fortschritt";
    }

    @PostMapping("/{id}/ausgangsbestand")
    public String ausgangsbestand(@PathVariable("id") long id, RedirectAttributes redirectAttributes, Locale locale) {
        int moved = runService.acceptBaseline(id);
        String msg = messageSource.getMessage("ui.lauf.ausgangsbestand.erfolg", new Object[]{moved}, locale);
        redirectAttributes.addFlashAttribute("flashMessage", msg);
        return "redirect:/laeufe/" + id;
    }
}
