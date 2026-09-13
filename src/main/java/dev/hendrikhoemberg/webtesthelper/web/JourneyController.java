package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.catalog.JourneyHealth;
import dev.hendrikhoemberg.webtesthelper.catalog.JourneyHealthService;
import dev.hendrikhoemberg.webtesthelper.catalog.JourneyService;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.model.JourneyDefinition;
import dev.hendrikhoemberg.webtesthelper.model.JourneyReplayResult;
import dev.hendrikhoemberg.webtesthelper.model.JourneyStep;
import dev.hendrikhoemberg.webtesthelper.model.LocatorCandidate;
import dev.hendrikhoemberg.webtesthelper.model.ReplayStatus;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.StepAction;
import dev.hendrikhoemberg.webtesthelper.model.StepOutcome;
import dev.hendrikhoemberg.webtesthelper.model.StepStatus;
import dev.hendrikhoemberg.webtesthelper.runner.JourneyReplayer;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Controller
public class JourneyController {

    private final JourneyService journeyService;
    private final SiteService siteService;
    private final JourneyHealthService journeyHealthService;
    private final JourneyReplayer journeyReplayer;
    private final MessageSource messageSource;

    public JourneyController(JourneyService journeyService, SiteService siteService,
                             JourneyHealthService journeyHealthService, JourneyReplayer journeyReplayer,
                             MessageSource messageSource) {
        this.journeyService = journeyService;
        this.siteService = siteService;
        this.journeyHealthService = journeyHealthService;
        this.journeyReplayer = journeyReplayer;
        this.messageSource = messageSource;
    }

    @GetMapping("/websites/{siteId}/journeys")
    public String list(@PathVariable("siteId") long siteId, Model model) {
        SiteContext site = siteService.contextFor(siteId);
        List<JourneyDefinition> journeys = journeyService.findBySite(siteId);
        Map<Long, JourneyHealth> healthByJourneyId = journeyHealthService.healthBySite(siteId);
        model.addAttribute("site", site);
        model.addAttribute("journeys", journeys);
        model.addAttribute("healthByJourneyId", healthByJourneyId);
        return "journey/list";
    }

    @GetMapping("/websites/{siteId}/journeys/{journeyId}")
    public String detail(@PathVariable("siteId") long siteId,
                         @PathVariable("journeyId") long journeyId,
                         Model model) {
        SiteContext site = siteService.contextFor(siteId);
        JourneyDefinition journey = journeyService.findDefinition(journeyId)
                .filter(j -> Objects.equals(j.siteId(), siteId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ablauf nicht gefunden: " + journeyId));
        JourneyHealth health = journeyHealthService.health(journeyId)
                .orElse(new JourneyHealth(null, 0, 0));
        model.addAttribute("site", site);
        model.addAttribute("journey", journey);
        model.addAttribute("health", health);
        return "journey/detail";
    }

    /**
     * Replays a single journey immediately against the live site (§10.4, D106), so a user can see
     * whether it still passes without enqueuing a full crawl. The replay is synchronous: the
     * request blocks until Playwright finishes, and the HTMX caller shows a spinner meanwhile.
     */
    @PostMapping("/websites/{siteId}/journeys/{journeyId}/jetzt-ausfuehren")
    public String runNow(@PathVariable("siteId") long siteId,
                         @PathVariable("journeyId") long journeyId,
                         Model model, Locale locale) {
        SiteContext site = siteService.contextFor(siteId);
        JourneyDefinition journey = journeyService.findDefinition(journeyId)
                .filter(j -> Objects.equals(j.siteId(), siteId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ablauf nicht gefunden: " + journeyId));

        model.addAttribute("result", null);
        model.addAttribute("fehler", null);
        model.addAttribute("fehlermeldung", null);

        try {
            JourneyReplayResult result = journeyReplayer.replay(journey, site, null);
            journeyHealthService.record(journey.id(), result);
            model.addAttribute("result", result);
            model.addAttribute("journey", journey);
            model.addAttribute("site", site);
            model.addAttribute("fehlermeldung", failureText(result, locale));
            model.addAttribute("stepResults", buildStepResults(journey, result, locale));
        } catch (RuntimeException e) {
            model.addAttribute("fehler", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }

        return "journey/ergebnis :: ergebnis";
    }

    private List<JourneyStepResultView> buildStepResults(JourneyDefinition journey, JourneyReplayResult result, Locale locale) {
        Map<UUID, StepOutcome> outcomesByStepId = result.outcomes().stream()
                .collect(Collectors.toMap(StepOutcome::stepId, o -> o, (a, b) -> a));

        List<JourneyStepResultView> stepViews = new ArrayList<>();
        for (JourneyStep step : journey.steps()) {
            StepOutcome outcome = outcomesByStepId.get(step.id());
            StepStatus status = outcome != null ? outcome.status() : StepStatus.SKIPPED;
            boolean drifted = outcome != null && outcome.drifted();

            String actionLabel = messageSource.getMessage("ui.journey.action." + step.action().name(), null, locale);
            String targetSummary = stepTargetSummary(step, locale);

            String statusBadgeClass;
            String statusLabel;
            switch (status) {
                case PASSED -> {
                    statusBadgeClass = "badge-healthy status-aktiv";
                    statusLabel = messageSource.getMessage("ui.journey.test.schritt.status.erfolgreich", null, locale);
                }
                case DRIFTED -> {
                    statusBadgeClass = "badge-warning schritt-drift";
                    statusLabel = messageSource.getMessage("ui.journey.test.schritt.status.angepasst", null, locale);
                }
                case FAILED -> {
                    statusBadgeClass = "status-inaktiv";
                    statusLabel = messageSource.getMessage("ui.journey.test.schritt.status.fehlgeschlagen", null, locale);
                }
                default -> {
                    statusBadgeClass = "status-inaktiv";
                    statusLabel = messageSource.getMessage("ui.journey.test.schritt.status.uebersprungen", null, locale);
                }
            }

            String winnerDetails = null;
            if (drifted && outcome != null && outcome.winner() != null) {
                LocatorCandidate winner = outcome.winner();
                String strategyLabel = messageSource.getMessage("ui.journey.strategy." + winner.strategy().name(), null, locale);
                winnerDetails = messageSource.getMessage("ui.journey.test.schritt.ausweich_verwendet",
                        new Object[]{strategyLabel, winner.value()}, locale);
            }

            String failureMessage = null;
            if (outcome != null && outcome.failureMessageKey() != null) {
                failureMessage = messageSource.getMessage(outcome.failureMessageKey(), outcome.failureArgs().toArray(), locale);
            }

            stepViews.add(new JourneyStepResultView(
                    step.ordinal() + 1,
                    step.action(),
                    actionLabel,
                    targetSummary,
                    status,
                    statusBadgeClass,
                    statusLabel,
                    drifted,
                    winnerDetails,
                    failureMessage
            ));
        }
        return stepViews;
    }

    private String stepTargetSummary(JourneyStep step, Locale locale) {
        if (step.value() != null && !step.value().isBlank()) {
            return step.value();
        }
        if (!step.locatorCandidates().isEmpty()) {
            LocatorCandidate primary = step.locatorCandidates().get(0);
            String strat = messageSource.getMessage("ui.journey.strategy." + primary.strategy().name(), null, locale);
            return strat + ": " + primary.value();
        }
        return "—";
    }

    /** The first failed step's message, human-readable, or {@code null} when nothing failed. */
    private String failureText(JourneyReplayResult result, Locale locale) {
        if (result.status() != ReplayStatus.FAILED) {
            return null;
        }
        return result.outcomes().stream()
                .filter(o -> o.status() == StepStatus.FAILED && o.failureMessageKey() != null)
                .findFirst()
                .map(o -> messageSource.getMessage(o.failureMessageKey(), o.failureArgs().toArray(), locale))
                .orElse(null);
    }

    @PostMapping("/websites/{siteId}/journeys/{journeyId}/loeschen")
    public String deleteJourney(@PathVariable("siteId") long siteId,
                                @PathVariable("journeyId") long journeyId,
                                RedirectAttributes redirectAttributes,
                                Locale locale) {
        JourneyDefinition journey = journeyService.findDefinition(journeyId)
                .filter(j -> Objects.equals(j.siteId(), siteId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ablauf nicht gefunden: " + journeyId));

        journeyService.delete(journeyId);
        String successMsg = messageSource.getMessage(
                "ui.journey.geloescht", new Object[]{journey.name()}, locale);
        redirectAttributes.addFlashAttribute("flashMessage", successMsg);
        return "redirect:/websites/" + siteId + "/journeys";
    }
}
