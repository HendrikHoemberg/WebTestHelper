package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.findings.Finding;
import dev.hendrikhoemberg.webtesthelper.findings.FindingOccurrence;
import dev.hendrikhoemberg.webtesthelper.findings.FindingService;
import dev.hendrikhoemberg.webtesthelper.model.TriageStatus;
import dev.hendrikhoemberg.webtesthelper.reporting.FindingDetailView;
import dev.hendrikhoemberg.webtesthelper.reporting.FindingViewFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

/**
 * Controller for the finding detail view (§13.2), showing humanised explanations,
 * occurrences, evidence, and isolated technical details.
 */
@Controller
@RequestMapping("/befunde")
public class FindingController {

    /** Same zone TriageController uses to read "Stumm bis" back, so the date round-trips. */
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Europe/Berlin");

    private final FindingService findingService;
    private final FindingViewFactory findingViewFactory;

    public FindingController(FindingService findingService, FindingViewFactory findingViewFactory) {
        this.findingService = findingService;
        this.findingViewFactory = findingViewFactory;
    }

    /** A bare /befunde has nothing to render; land on the websites overview instead of a 404. */
    @GetMapping
    public String root() {
        return "redirect:/websites";
    }

    /**
     * The triage form starts at the decision already taken, so changing a reason or extending a
     * mute does not silently reset the status. An untriaged finding keeps the advice's default
     * ("zur Kenntnis nehmen") — preselecting "ungeprüft" would offer a no-op.
     */
    private static void prefillTriageForm(Finding finding, Model model) {
        if (finding.triage() == TriageStatus.UNTRIAGED) {
            return;
        }
        model.addAttribute("triageDefaultAction", finding.triage().name());
        model.addAttribute("triageCurrentReason", finding.triageReason());
        if (finding.mutedUntil() != null) {
            model.addAttribute("triageCurrentMutedUntil",
                    LocalDate.ofInstant(finding.mutedUntil(), DEFAULT_ZONE).toString());
        }
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable("id") long id, Model model, Locale locale) {
        Finding finding = findingService.byId(id)
                .orElseThrow(() -> new IllegalArgumentException("Befund " + id + " existiert nicht"));

        List<FindingOccurrence> occurrences = findingService.occurrencesOfLastRun(id, 50);
        FindingDetailView detail = findingViewFactory.detailOf(finding, occurrences, locale);

        model.addAttribute("detail", detail);
        model.addAttribute("finding", finding);
        prefillTriageForm(finding, model);
        return "befunde/detail";
    }
}
