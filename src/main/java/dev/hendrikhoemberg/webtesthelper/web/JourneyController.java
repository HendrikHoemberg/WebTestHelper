package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.catalog.JourneyHealth;
import dev.hendrikhoemberg.webtesthelper.catalog.JourneyHealthService;
import dev.hendrikhoemberg.webtesthelper.catalog.JourneyService;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.model.JourneyDefinition;
import dev.hendrikhoemberg.webtesthelper.model.JourneyReplayResult;
import dev.hendrikhoemberg.webtesthelper.model.ReplayStatus;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
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

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

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
            model.addAttribute("fehlermeldung", failureText(result, locale));
        } catch (RuntimeException e) {
            model.addAttribute("fehler", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }

        return "journey/ergebnis :: ergebnis";
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
}
