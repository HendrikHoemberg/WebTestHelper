package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.findings.Finding;
import dev.hendrikhoemberg.webtesthelper.findings.FindingProperties;
import dev.hendrikhoemberg.webtesthelper.findings.FindingService;
import dev.hendrikhoemberg.webtesthelper.findings.TriageAction;
import dev.hendrikhoemberg.webtesthelper.model.TriageStatus;
import dev.hendrikhoemberg.webtesthelper.scheduling.ScheduleService;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

/**
 * Controller for triage actions on single findings and bulk finding selections (§13.4, spec 6.3).
 *
 * <p>Redirect targets are ALWAYS constructed server-side from path variables, never from request
 * parameters, avoiding open-redirect vulnerabilities.
 */
@Controller
public class TriageController {

    private static final int MAX_BULK_IDS = 200;
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Europe/Berlin");

    private final FindingService findingService;
    private final ScheduleService scheduleService;
    private final FindingProperties findingProperties;
    private final MessageSource messageSource;

    public TriageController(
            FindingService findingService,
            ScheduleService scheduleService,
            FindingProperties findingProperties,
            MessageSource messageSource) {
        this.findingService = findingService;
        this.scheduleService = scheduleService;
        this.findingProperties = findingProperties;
        this.messageSource = messageSource;
    }

    @PostMapping("/befunde/{id}/bewerten")
    public String bewertenSingle(
            @PathVariable("id") long id,
            @ModelAttribute TriageForm form,
            Authentication authentication,
            RedirectAttributes redirectAttributes,
            Locale locale) {
        Finding finding = findingService.byId(id)
                .orElseThrow(() -> new IllegalArgumentException("Befund " + id + " existiert nicht"));

        String actor = authentication != null ? authentication.getName() : "anonymous";
        Instant now = Instant.now();
        ZoneId zone = siteZone(finding.siteId());

        Instant mutedUntil = null;
        if (form.aktion() == TriageStatus.MUTED && form.stummBis() != null) {
            mutedUntil = form.stummBis().atTime(LocalTime.MAX).atZone(zone).toInstant();
        }

        TriageAction action = TriageAction.of(form.aktion(), form.grund(), mutedUntil, now, findingProperties.maxMuteDays());
        findingService.triage(finding.siteId(), List.of(id), action, actor, now);

        String successMsg = messageSource.getMessage("ui.befunde.triage.einzeln_erfolg", null, locale);
        redirectAttributes.addFlashAttribute("flashMessage", successMsg);

        return "redirect:/befunde/" + id;
    }

    @PostMapping("/websites/{id}/befunde/bewerten")
    public String bewertenBulk(
            @PathVariable("id") long siteId,
            @ModelAttribute TriageForm form,
            Authentication authentication,
            RedirectAttributes redirectAttributes,
            Locale locale) {
        if (form.ids() != null && form.ids().size() > MAX_BULK_IDS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Zu viele Befunde ausgewählt (maximal " + MAX_BULK_IDS + " erlaubt)");
        }

        if (form.ids() == null || form.ids().isEmpty()) {
            String emptyMsg = messageSource.getMessage("ui.befunde.triage.keine_auswahl", null, locale);
            redirectAttributes.addFlashAttribute("flashMessage", emptyMsg);
            return "redirect:/websites/" + siteId + "/befunde";
        }

        String actor = authentication != null ? authentication.getName() : "anonymous";
        Instant now = Instant.now();
        ZoneId zone = siteZone(siteId);

        Instant mutedUntil = null;
        if (form.aktion() == TriageStatus.MUTED && form.stummBis() != null) {
            mutedUntil = form.stummBis().atTime(LocalTime.MAX).atZone(zone).toInstant();
        }

        TriageAction action = TriageAction.of(form.aktion(), form.grund(), mutedUntil, now, findingProperties.maxMuteDays());
        int updated = findingService.triage(siteId, form.ids(), action, actor, now);

        String successMsg = messageSource.getMessage("ui.befunde.triage.erfolg", new Object[]{updated}, locale);
        redirectAttributes.addFlashAttribute("flashMessage", successMsg);

        return "redirect:/websites/" + siteId + "/befunde";
    }

    private ZoneId siteZone(long siteId) {
        return scheduleService.forSite(siteId).stream()
                .findFirst()
                .map(s -> {
                    try {
                        return ZoneId.of(s.timezone());
                    } catch (Exception e) {
                        return DEFAULT_ZONE;
                    }
                })
                .orElse(DEFAULT_ZONE);
    }
}
