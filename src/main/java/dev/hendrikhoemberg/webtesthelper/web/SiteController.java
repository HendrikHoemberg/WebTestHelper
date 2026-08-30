package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteSummary;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunTrigger;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.runner.RunService;
import dev.hendrikhoemberg.webtesthelper.scheduling.ScheduleService;
import jakarta.validation.Valid;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Controller
public class SiteController {

    private final SiteService siteService;
    private final RunService runService;
    private final ScheduleService scheduleService;
    private final SiteDetailModel siteDetailModel;
    private final MessageSource messageSource;

    public SiteController(SiteService siteService, RunService runService,
                          ScheduleService scheduleService, SiteDetailModel siteDetailModel,
                          MessageSource messageSource) {
        this.siteService = siteService;
        this.runService = runService;
        this.scheduleService = scheduleService;
        this.siteDetailModel = siteDetailModel;
        this.messageSource = messageSource;
    }

    @GetMapping("/websites")
    public String index(Model model) {
        List<SiteSummary> sites = siteService.summaries();
        model.addAttribute("sites", sites);
        return "websites/liste";
    }

    @GetMapping("/websites/neu")
    public String neu(Model model) {
        model.addAttribute("form", SiteFormModel.empty());
        return "websites/formular";
    }

    @PostMapping("/websites")
    public String create(@Valid @ModelAttribute("form") SiteFormModel form,
                         BindingResult bindingResult,
                         Model model) {
        if (bindingResult.hasErrors()) {
            return "websites/formular";
        }
        if (siteService.baseUrlTaken(form.baseUrl())) {
            bindingResult.rejectValue("baseUrl", "ui.websites.formular.fehler.baseUrl.vergeben",
                    "Eine Website mit dieser Adresse ist bereits angelegt.");
            return "websites/formular";
        }
        long id = siteService.create(form.toForm());
        // Spec 9: "Defaults are applied when a site is created." The dispatcher's D38 backfill
        // stays as the net for a site that arrives by any other route, but it cannot be the only
        // seeder: tick() short-circuits on the global pause (spec 14) before it reaches the seed
        // step, so a site added while the clock is paused would have no tiers to edit. Seeding is
        // not scheduling — the rows sit still until the pause lifts, exactly like every other
        // site's.
        scheduleService.seedDefaults(id, Instant.now());
        return "redirect:/websites/" + id + "/einrichtung";
    }

    @GetMapping("/websites/{id}")
    public String detail(@PathVariable("id") long id, Model model) {
        siteDetailModel.populate(id, model);
        return "websites/detail";
    }

    @PostMapping("/websites/{id}/pruefen")
    public String pruefen(@PathVariable("id") long id) {
        long runId = runService.enqueue(id, RunTrigger.MANUAL, RunScope.FULL);
        return "redirect:/laeufe/" + runId;
    }

    @GetMapping("/websites/{id}/bearbeiten")
    public String bearbeiten(@PathVariable("id") long id, Model model) {
        SiteContext context = siteService.contextFor(id);
        model.addAttribute("siteId", id);
        model.addAttribute("form", SiteFormModel.of(context, siteService.summary(id).enabled()));
        return "websites/formular";
    }

    @PostMapping("/websites/{id}")
    public String update(@PathVariable("id") long id,
                         @Valid @ModelAttribute("form") SiteFormModel form,
                         BindingResult bindingResult,
                         Model model) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("siteId", id);
            return "websites/formular";
        }
        if (siteService.baseUrlTaken(form.baseUrl(), id)) {
            bindingResult.rejectValue("baseUrl", "ui.websites.formular.fehler.baseUrl.vergeben",
                    "Eine Website mit dieser Adresse ist bereits angelegt.");
            model.addAttribute("siteId", id);
            return "websites/formular";
        }
        siteService.update(id, form.toForm());
        return "redirect:/websites/" + id;
    }

    @PostMapping("/websites/{id}/loeschen")
    public String delete(@PathVariable("id") long id,
                         RedirectAttributes redirectAttributes,
                         Locale locale) {
        String name = siteService.summary(id).name();
        siteService.delete(id);
        String successMsg = messageSource.getMessage(
                "ui.websites.geloescht", new Object[]{name}, locale);
        redirectAttributes.addFlashAttribute("flashMessage", successMsg);
        return "redirect:/websites";
    }
}
