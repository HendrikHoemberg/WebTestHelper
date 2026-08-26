package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteSummary;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunTrigger;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.runner.RunService;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.List;

@Controller
public class SiteController {

    private final SiteService siteService;
    private final RunService runService;
    private final SiteDetailModel siteDetailModel;

    public SiteController(SiteService siteService, RunService runService, SiteDetailModel siteDetailModel) {
        this.siteService = siteService;
        this.runService = runService;
        this.siteDetailModel = siteDetailModel;
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
        long id = siteService.create(form.toForm());
        return "redirect:/websites/" + id;
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
        siteService.update(id, form.toForm());
        return "redirect:/websites/" + id;
    }
}
