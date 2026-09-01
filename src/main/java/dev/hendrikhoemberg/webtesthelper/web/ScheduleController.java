package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.checks.CheckRegistry;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.runner.RunService;
import dev.hendrikhoemberg.webtesthelper.scheduling.CronSchedule;
import dev.hendrikhoemberg.webtesthelper.scheduling.Schedule;
import dev.hendrikhoemberg.webtesthelper.scheduling.ScheduleService;
import dev.hendrikhoemberg.webtesthelper.scheduling.TierCron;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The schedules screen: one POST that saves all three tiers of a site at once. A per-tier route
 * would mean three navigations to change an 03:00 that is wrong for the same reason on all three.
 * Validation runs for every row before any {@code update} call, so a partial save across the tiers
 * is never left behind.
 *
 * <p>The common case is a time of day; the tier supplies the calendar part (see {@link TierCron}).
 * The raw cron survives only as the Erweitert escape hatch (§13.1), and when it is filled it is
 * taken verbatim, ignoring the time field.
 */
@Controller
public class ScheduleController {

    private static final String DEFAULT_TIME = "03:00";

    private final ScheduleService scheduleService;
    private final SiteService siteService;
    private final RunService runService;
    private final CheckRegistry checkRegistry;
    private final SiteDetailModel siteDetailModel;

    public ScheduleController(ScheduleService scheduleService, SiteService siteService,
                              RunService runService, CheckRegistry checkRegistry,
                              SiteDetailModel siteDetailModel) {
        this.scheduleService = scheduleService;
        this.siteService = siteService;
        this.runService = runService;
        this.checkRegistry = checkRegistry;
        this.siteDetailModel = siteDetailModel;
    }

    @PostMapping("/websites/{id}/zeitplaene")
    public String save(@PathVariable("id") long id,
                       @ModelAttribute("zeitplaene") ScheduleFormModel form,
                       BindingResult bindingResult,
                       Model model) {
        List<Schedule> current = scheduleService.forSite(id);
        validate(form, bindingResult);

        if (bindingResult.hasErrors()) {
            siteDetailModel.populateConfigContext(id, model);
            model.addAttribute("zeitplaeneDetail", ScheduleView.detailByScope(current));
            return "websites/konfiguration";
        }

        Map<RunScope, Schedule> byScope = new EnumMap<>(RunScope.class);
        for (Schedule schedule : current) {
            byScope.put(schedule.scope(), schedule);
        }

        Instant now = Instant.now();
        for (ScheduleFormModel.Row row : form.zeitplaene()) {
            Schedule schedule = byScope.get(row.scope());
            if (schedule == null) {
                continue;
            }
            String cron = row.cron() == null || row.cron().isBlank()
                    ? TierCron.compose(row.scope(), timeOf(row))
                    : row.cron().strip();
            scheduleService.update(schedule.id(), cron, row.timezone(),
                    Boolean.TRUE.equals(row.enabled()), now);
        }
        return "redirect:/websites/" + id + "/konfiguration";
    }

    /** Cross-field rule for one row: the cron, when filled, is taken verbatim and the time ignored. */
    private void validate(ScheduleFormModel form, BindingResult bindingResult) {
        List<ScheduleFormModel.Row> rows = form.zeitplaene();
        if (rows == null) {
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            ScheduleFormModel.Row row = rows.get(i);
            String zeit = row.zeit() == null ? "" : row.zeit().strip();
            String cron = row.cron() == null ? "" : row.cron().strip();
            String timezone = row.timezone() == null ? "" : row.timezone().strip();

            if (timezone.isEmpty()) {
                // A blank timezone is not "just invalid" here: it would reach update() and make
                // CronSchedule.parse fail mid-loop, after earlier tiers already committed (the
                // service deliberately commits per row). Reject it before any write so the whole
                // form fails atomically (§ "a partial save across three tiers" is the worst case).
                bindingResult.rejectValue("zeitplaene[" + i + "].timezone",
                        "ui.zeitplan.fehler.zone",
                        "Bitte eine Zeitzone angeben.");
            } else {
                try {
                    ZoneId.of(timezone);
                } catch (RuntimeException e) {
                    bindingResult.rejectValue("zeitplaene[" + i + "].timezone",
                            "ui.zeitplan.fehler.zone",
                            "Diese Zeitzone ist nicht bekannt.");
                }
            }

            if (!cron.isEmpty()) {
                if (CronSchedule.parse(cron, timezone).isEmpty()) {
                    bindingResult.rejectValue("zeitplaene[" + i + "].cron",
                            "ui.zeitplan.fehler.cron",
                            "Dieser Zeitplan ist keine gültige Cron-Angabe.");
                }
            } else {
                String effective = zeit.isEmpty() ? DEFAULT_TIME : zeit;
                try {
                    LocalTime.parse(effective);
                } catch (RuntimeException e) {
                    bindingResult.rejectValue("zeitplaene[" + i + "].zeit",
                            "ui.zeitplan.fehler.zeit",
                            "Bitte eine Uhrzeit im Format HH:MM wählen.");
                }
            }
        }
    }

    private LocalTime timeOf(ScheduleFormModel.Row row) {
        String zeit = row.zeit() == null || row.zeit().isBlank() ? DEFAULT_TIME : row.zeit().strip();
        return LocalTime.parse(zeit);
    }
}
