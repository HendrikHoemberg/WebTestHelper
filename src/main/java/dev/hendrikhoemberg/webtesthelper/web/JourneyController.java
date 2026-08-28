package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.catalog.JourneyHealth;
import dev.hendrikhoemberg.webtesthelper.catalog.JourneyHealthService;
import dev.hendrikhoemberg.webtesthelper.catalog.JourneyService;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.model.JourneyDefinition;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Controller
public class JourneyController {

    private final JourneyService journeyService;
    private final SiteService siteService;
    private final JourneyHealthService journeyHealthService;

    public JourneyController(JourneyService journeyService, SiteService siteService, JourneyHealthService journeyHealthService) {
        this.journeyService = journeyService;
        this.siteService = siteService;
        this.journeyHealthService = journeyHealthService;
    }

    @GetMapping({"/sites/{siteId}/journeys", "/websites/{siteId}/reisen"})
    public String list(@PathVariable("siteId") long siteId, Model model) {
        SiteContext site = siteService.contextFor(siteId);
        List<JourneyDefinition> journeys = journeyService.findBySite(siteId);
        Map<Long, JourneyHealth> healthByJourneyId = journeyHealthService.healthBySite(siteId);
        model.addAttribute("site", site);
        model.addAttribute("journeys", journeys);
        model.addAttribute("healthByJourneyId", healthByJourneyId);
        return "journey/list";
    }

    @GetMapping({"/sites/{siteId}/journeys/{journeyId}", "/websites/{siteId}/reisen/{journeyId}"})
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
}
