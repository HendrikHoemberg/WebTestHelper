package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.runner.CheckProposal;
import dev.hendrikhoemberg.webtesthelper.runner.ProbeState;
import dev.hendrikhoemberg.webtesthelper.runner.ProbeStatus;
import dev.hendrikhoemberg.webtesthelper.runner.SetupProbeService;
import dev.hendrikhoemberg.webtesthelper.runner.SetupProposal;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The guided-setup wizard (§13.3): instead of authoring a configuration, a colleague confirms
 * a proposal the probe assembled. The shell starts the probe; the {@code /stand} fragment is
 * what HTMX polls, and it ends its own polling by swapping in terminal markup with no
 * {@code hx-trigger} — the only way an HTMX poll stops without client-side state.
 */
@Controller
public class SetupController {

    private final SetupProbeService setupProbeService;
    private final SiteService siteService;
    private final MessageSource messageSource;

    public SetupController(SetupProbeService setupProbeService, SiteService siteService,
                           MessageSource messageSource) {
        this.setupProbeService = setupProbeService;
        this.siteService = siteService;
        this.messageSource = messageSource;
    }

    @GetMapping("/websites/{id}/einrichtung")
    public String einrichtung(@PathVariable("id") long id, Model model) {
        model.addAttribute("siteName", siteService.summary(id).name()); // unknown site → 404
        if (setupProbeService.stateOf(id).isEmpty()) {
            setupProbeService.start(id);
        }
        populateStand(id, model);
        return "einrichtung/index";
    }

    @GetMapping("/websites/{id}/einrichtung/stand")
    public String stand(@PathVariable("id") long id, Model model) {
        siteService.summary(id); // unknown site → 404
        populateStand(id, model);
        return "fragments/einrichtungsstand :: stand";
    }

    @PostMapping("/websites/{id}/einrichtung")
    public String anwenden(@PathVariable("id") long id, @ModelAttribute SetupForm form) {
        siteService.summary(id); // unknown site → 404
        Set<CheckType> aktiv = form.getAktiv().isEmpty()
                ? EnumSet.noneOf(CheckType.class)
                : EnumSet.copyOf(form.getAktiv());
        for (CheckType type : CheckType.values()) {
            siteService.setCheckEnabled(id, type, aktiv.contains(type));
        }
        setupProbeService.clear(id);
        return "redirect:/websites/" + id;
    }

    @PostMapping("/websites/{id}/einrichtung/neu")
    public String neu(@PathVariable("id") long id) {
        siteService.summary(id); // unknown site → 404
        setupProbeService.clear(id);
        setupProbeService.start(id);
        return "redirect:/websites/" + id + "/einrichtung";
    }

    private void populateStand(long id, Model model) {
        ProbeState state = setupProbeService.stateOf(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Keine Einrichtungs-Prüfung für Website " + id));
        model.addAttribute("siteId", id);
        model.addAttribute("laeuft", state.status() == ProbeStatus.LAEUFT);
        model.addAttribute("fertig", state.status() == ProbeStatus.FERTIG);
        model.addAttribute("fehlgeschlagen", state.status() == ProbeStatus.FEHLGESCHLAGEN);
        model.addAttribute("error", state.error());

        List<SetupCheckView> checks = List.of();
        List<String> pagesVisited = List.of();
        List<String> formPages = List.of();
        SetupProposal proposal = state.proposal();
        if (proposal != null) {
            pagesVisited = proposal.pagesVisited();
            formPages = proposal.formPages();
            checks = proposal.checks().stream()
                    .map(this::toView)
                    .toList();
        }
        model.addAttribute("pagesVisited", pagesVisited);
        model.addAttribute("formPages", formPages);
        model.addAttribute("checks", checks);
    }

    private SetupCheckView toView(CheckProposal check) {
        String reason = messageSource.getMessage(
                check.reasonKey(), check.reasonArgs().toArray(), Locale.GERMAN);
        return new SetupCheckView(check.type(), check.suggested(), reason);
    }

    /**
     * One tickable row of the proposal: the check, whether the probe suggests ticking it, and the
     * plain-language reason already resolved through the message bundle. The form field still
     * carries the check's internal name so Spring can bind it back to a {@code CheckType}; that
     * value is machine-only and never shown as a label (§13.1).
     */
    record SetupCheckView(CheckType type, boolean suggested, String reason) {
    }
}
