package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.findings.Finding;
import dev.hendrikhoemberg.webtesthelper.findings.FindingOccurrence;
import dev.hendrikhoemberg.webtesthelper.findings.FindingService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;
import java.util.Locale;

/**
 * Controller for the finding detail view (§13.2), showing humanised explanations,
 * occurrences, evidence, and isolated technical details.
 */
@Controller
@RequestMapping("/befunde")
public class FindingController {

    private final FindingService findingService;
    private final FindingViewFactory findingViewFactory;

    public FindingController(FindingService findingService, FindingViewFactory findingViewFactory) {
        this.findingService = findingService;
        this.findingViewFactory = findingViewFactory;
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable("id") long id, Model model, Locale locale) {
        Finding finding = findingService.byId(id)
                .orElseThrow(() -> new IllegalArgumentException("Befund " + id + " existiert nicht"));

        List<FindingOccurrence> occurrences = findingService.occurrencesOfLastRun(id, 50);
        FindingDetailView detail = findingViewFactory.detailOf(finding, occurrences, locale);

        model.addAttribute("detail", detail);
        model.addAttribute("finding", finding);
        return "befunde/detail";
    }
}
