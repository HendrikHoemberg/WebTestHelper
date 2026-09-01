package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteSummary;
import dev.hendrikhoemberg.webtesthelper.findings.Finding;
import dev.hendrikhoemberg.webtesthelper.findings.FindingQuery;
import dev.hendrikhoemberg.webtesthelper.findings.FindingService;
import dev.hendrikhoemberg.webtesthelper.findings.MuteRule;
import dev.hendrikhoemberg.webtesthelper.findings.MuteRuleForm;
import dev.hendrikhoemberg.webtesthelper.findings.MuteRuleService;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.ObservedStatus;
import dev.hendrikhoemberg.webtesthelper.model.TriageStatus;
import dev.hendrikhoemberg.webtesthelper.scheduling.ScheduleService;
import org.springframework.context.MessageSource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Controller for managing mute rules (spec 6.3, D48).
 * Per-site rules can be created and deleted by any authenticated USER.
 * Global rules (siteId == null) require ROLE_ADMIN.
 */
@Controller
public class MuteRuleController {

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Europe/Berlin");

    private final MuteRuleService muteRuleService;
    private final SiteService siteService;
    private final ScheduleService scheduleService;
    private final FindingService findingService;
    private final MessageSource messageSource;

    public MuteRuleController(
            MuteRuleService muteRuleService,
            SiteService siteService,
            ScheduleService scheduleService,
            FindingService findingService,
            MessageSource messageSource) {
        this.muteRuleService = muteRuleService;
        this.siteService = siteService;
        this.scheduleService = scheduleService;
        this.findingService = findingService;
        this.messageSource = messageSource;
    }

    public record MuteRuleFormModel(
            Long siteId,
            CheckType checkType,
            String subjectPattern,
            String locationPattern,
            String reason,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate expiresAt
    ) {
        public static MuteRuleFormModel empty() {
            return new MuteRuleFormModel(null, null, "", "", "", LocalDate.now().plusDays(90));
        }
    }

    @GetMapping("/stummschaltungen")
    public String index(Model model) {
        List<MuteRule> rules = muteRuleService.all();
        List<SiteSummary> sites = siteService.summaries();
        Map<Long, String> siteMap = sites.stream()
                .collect(Collectors.toMap(SiteSummary::id, SiteSummary::name));

        model.addAttribute("rules", rules);
        model.addAttribute("sites", sites);
        model.addAttribute("siteMap", siteMap);
        model.addAttribute("checkTypes", CheckType.values());
        model.addAttribute("form", MuteRuleFormModel.empty());
        model.addAttribute("now", Instant.now());
        return "stummschaltungen/index";
    }

    @PostMapping("/stummschaltungen")
    public String create(
            @ModelAttribute("form") MuteRuleFormModel form,
            Authentication authentication,
            RedirectAttributes redirectAttributes,
            Locale locale) {
        if (form.siteId() == null) {
            checkGlobalRuleAdmin(authentication);
        }

        String actor = authentication != null ? authentication.getName() : "anonymous";
        Instant now = Instant.now();
        ZoneId zone = form.siteId() != null ? siteZone(form.siteId()) : DEFAULT_ZONE;

        Instant expiryInstant = form.expiresAt() != null
                ? form.expiresAt().atTime(LocalTime.MAX).atZone(zone).toInstant()
                : null;

        MuteRuleForm domainForm = new MuteRuleForm(
                form.siteId(),
                form.checkType(),
                form.subjectPattern(),
                form.locationPattern(),
                form.reason(),
                expiryInstant
        );

        muteRuleService.create(domainForm, actor, now);

        String successMsg = messageSource.getMessage("ui.stummschaltungen.erstellt", null, locale);
        redirectAttributes.addFlashAttribute("flashMessage", successMsg);

        return "redirect:/stummschaltungen";
    }

    @PostMapping("/stummschaltungen/{id}/loeschen")
    public String delete(
            @PathVariable("id") long id,
            Authentication authentication,
            RedirectAttributes redirectAttributes,
            Locale locale) {
        MuteRule rule = muteRuleService.byId(id)
                .orElseThrow(() -> new IllegalArgumentException("Stummschaltungsregel nicht gefunden: " + id));

        if (rule.siteId() == null) {
            checkGlobalRuleAdmin(authentication);
        }

        muteRuleService.delete(id);

        String successMsg = messageSource.getMessage("ui.stummschaltungen.geloescht", null, locale);
        redirectAttributes.addFlashAttribute("flashMessage", successMsg);

        return "redirect:/stummschaltungen";
    }

    @GetMapping("/stummschaltungen/vorschau")
    public String vorschau(
            @RequestParam(name = "siteId", required = false) Long siteId,
            @RequestParam(name = "checkType", required = false) CheckType checkType,
            @RequestParam(name = "subjectPattern", required = false) String subjectPattern,
            @RequestParam(name = "locationPattern", required = false) String locationPattern,
            Model model) {
        int count = muteRuleService.countMatching(siteId, checkType, subjectPattern, locationPattern);
        model.addAttribute("trefferAnzahl", count);
        return "fragments/regelvorschau :: regelvorschau";
    }

    @GetMapping("/stummschaltungen/auswahl")
    public String auswahl(
            @RequestParam(name = "siteId", required = false) Long siteId,
            Model model) {
        model.addAttribute("siteGewaehlt", siteId != null);
        Set<CheckType> zulaessig = EnumSet.complementOf(
                EnumSet.of(CheckType.JOURNEY_STEP_FAILED, CheckType.SELECTOR_DRIFT));
        List<Finding> findings = siteId == null ? List.of() : findingService.search(
                new FindingQuery(siteId, Set.of(),
                        Set.of(TriageStatus.UNTRIAGED, TriageStatus.ACKNOWLEDGED),
                        ObservedStatus.ACTIVE, zulaessig, 1, 20)).findings();
        model.addAttribute("auswahlFindings", findings);
        return "fragments/stummschaltungsauswahl :: stummschaltungsauswahl";
    }

    private void checkGlobalRuleAdmin(Authentication authentication) {
        if (authentication == null || authentication.getAuthorities().stream()
                .noneMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))) {
            throw new AccessDeniedException("Globale Stummschaltungsregeln erfordern Administratorrechte.");
        }
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
