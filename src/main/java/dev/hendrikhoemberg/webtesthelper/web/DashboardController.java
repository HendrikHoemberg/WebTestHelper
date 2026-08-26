package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.reporting.DashboardService;
import dev.hendrikhoemberg.webtesthelper.runner.DashboardProperties;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The dashboard screen (spec 14). {@code /} answers "is anything wrong?" with one view model;
 * {@code /uebersicht/kacheln} is the tile grid HTMX swaps every poll interval, so it returns the
 * {@code kacheln} fragment rather than a document. Both render the same {@link DashboardView}
 * under the same attribute name so the fragment needs no separate controller. The poll cadence
 * comes from {@link DashboardProperties} (D64) so the template and the property never disagree.
 */
@Controller
public class DashboardController {

    private final DashboardService dashboardService;
    private final DashboardProperties dashboardProperties;

    public DashboardController(DashboardService dashboardService, DashboardProperties dashboardProperties) {
        this.dashboardService = dashboardService;
        this.dashboardProperties = dashboardProperties;
    }

    @GetMapping("/")
    public String index(Model model) {
        populate(model);
        return "uebersicht/index";
    }

    @GetMapping("/uebersicht/kacheln")
    public String kacheln(Model model) {
        populate(model);
        return "fragments/kacheln :: kacheln";
    }

    private void populate(Model model) {
        model.addAttribute("uebersicht", dashboardService.overview());
        model.addAttribute("pollSekunden", dashboardProperties.pollInterval().toSeconds());
    }
}
