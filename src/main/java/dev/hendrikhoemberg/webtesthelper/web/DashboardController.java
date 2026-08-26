package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.reporting.DashboardService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The dashboard screen (spec 14). {@code /} answers "is anything wrong?" with one view model;
 * {@code /uebersicht/kacheln} is the tile grid HTMX swaps every poll interval, so it returns the
 * {@code kacheln} fragment rather than a document. Both render the same {@link DashboardView}
 * under the same attribute name so the fragment needs no separate controller.
 */
@Controller
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("uebersicht", dashboardService.overview());
        return "uebersicht/index";
    }

    @GetMapping("/uebersicht/kacheln")
    public String kacheln(Model model) {
        model.addAttribute("uebersicht", dashboardService.overview());
        return "fragments/kacheln :: kacheln";
    }
}
