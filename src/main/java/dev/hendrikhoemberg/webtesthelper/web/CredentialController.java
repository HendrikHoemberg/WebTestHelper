package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.catalog.CredentialService;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Locale;

@Controller
public class CredentialController {

    private final CredentialService credentialService;
    private final SiteDetailModel siteDetailModel;
    private final MessageSource messageSource;

    public CredentialController(CredentialService credentialService, SiteDetailModel siteDetailModel,
                                MessageSource messageSource) {
        this.credentialService = credentialService;
        this.siteDetailModel = siteDetailModel;
        this.messageSource = messageSource;
    }

    @PostMapping("/websites/{id}/zugangsdaten")
    public String create(
            @PathVariable("id") long id,
            @RequestParam(name = "name", required = false) String name,
            @RequestParam(name = "benutzername", required = false) String benutzername,
            @RequestParam(name = "passwort", required = false) String passwort,
            Model model,
            Locale locale) {
        try {
            credentialService.create(id, name, benutzername, passwort);
            return "redirect:/websites/" + id + "/konfiguration";
        } catch (IllegalArgumentException e) {
            String key;
            if ("credential.name.duplicate".equals(e.getMessage())) {
                key = "ui.websites.detail.zugangsdaten.fehler.duplikat";
            } else if ("credential.password.blank".equals(e.getMessage())) {
                key = "ui.websites.detail.zugangsdaten.fehler.passwort";
            } else {
                key = "ui.websites.detail.zugangsdaten.fehler.name";
            }
            return reject(id, name, benutzername, key, model, locale);
        }
    }

    @PostMapping("/websites/{id}/zugangsdaten/{cid}")
    public String update(
            @PathVariable("id") long id,
            @PathVariable("cid") long cid,
            @RequestParam(name = "benutzername", required = false) String benutzername,
            @RequestParam(name = "passwort", defaultValue = "") String passwort) {
        credentialService.update(id, cid, benutzername, passwort);
        return "redirect:/websites/" + id + "/konfiguration";
    }

    @PostMapping("/websites/{id}/zugangsdaten/{cid}/loeschen")
    public String delete(@PathVariable("id") long id, @PathVariable("cid") long cid) {
        credentialService.delete(id, cid);
        return "redirect:/websites/" + id + "/konfiguration";
    }

    private String reject(long siteId, String name, String benutzername, String messageKey, Model model, Locale locale) {
        siteDetailModel.populateConfig(siteId, model);
        model.addAttribute("name", name);
        model.addAttribute("benutzername", benutzername);
        model.addAttribute("credentialError", messageSource.getMessage(messageKey, null, locale));
        return "websites/konfiguration";
    }
}
