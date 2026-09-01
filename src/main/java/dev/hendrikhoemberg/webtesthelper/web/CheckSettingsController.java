package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.checks.CheckDescriptor;
import dev.hendrikhoemberg.webtesthelper.checks.CheckRegistry;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Per-check configuration on the site detail page: the active set plus a severity override
 * per check. Every authenticated role may save it, like the guided-setup confirmation; only
 * the registry checks are touched, journey types are not.
 */
@Controller
public class CheckSettingsController {

    private final SiteService siteService;
    private final CheckRegistry checkRegistry;
    private final SiteDetailModel siteDetailModel;
    private final MessageSource messageSource;

    public CheckSettingsController(SiteService siteService, CheckRegistry checkRegistry,
                                   SiteDetailModel siteDetailModel, MessageSource messageSource) {
        this.siteService = siteService;
        this.checkRegistry = checkRegistry;
        this.siteDetailModel = siteDetailModel;
        this.messageSource = messageSource;
    }

    @PostMapping("/websites/{id}/pruefungen")
    public String speichern(@PathVariable("id") long id,
                            @ModelAttribute CheckSettingsForm form,
                            Model model,
                            RedirectAttributes redirectAttributes,
                            Locale locale) {
        siteService.summary(id); // unknown site → 404

        Set<CheckType> aktiv = form.getAktiv().isEmpty()
                ? EnumSet.noneOf(CheckType.class)
                : EnumSet.copyOf(form.getAktiv());
        EnumMap<CheckType, Severity> overrides = new EnumMap<>(CheckType.class);
        for (CheckDescriptor check : checkRegistry.all()) {
            String value = form.getSchweregrad().get(check.type().name());
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                overrides.put(check.type(), Severity.valueOf(value));
            } catch (IllegalArgumentException e) {
                siteDetailModel.populate(id, model);
                model.addAttribute("checkSettingsError", messageSource.getMessage(
                        "ui.websites.detail.pruefungen.fehler.schweregrad", null, locale));
                return "websites/detail";
            }
        }

        for (CheckDescriptor check : checkRegistry.all()) {
            siteService.updateCheckSetting(id, check.type(),
                    aktiv.contains(check.type()), overrides.get(check.type()));
        }
        redirectAttributes.addFlashAttribute("flashMessage", messageSource.getMessage(
                "ui.websites.detail.pruefungen.gespeichert", null, locale));
        return "redirect:/websites/" + id;
    }
}
