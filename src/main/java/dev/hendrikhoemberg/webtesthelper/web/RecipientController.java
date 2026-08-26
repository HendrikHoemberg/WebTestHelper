package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.catalog.EmailAddresses;
import dev.hendrikhoemberg.webtesthelper.catalog.RecipientService;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Locale;

@Controller
public class RecipientController {

    private final RecipientService recipientService;
    private final SiteDetailModel siteDetailModel;
    private final MessageSource messageSource;

    public RecipientController(RecipientService recipientService, SiteDetailModel siteDetailModel,
                               MessageSource messageSource) {
        this.recipientService = recipientService;
        this.siteDetailModel = siteDetailModel;
        this.messageSource = messageSource;
    }

    @PostMapping("/websites/{id}/empfaenger")
    public String add(
            @PathVariable("id") long id,
            @RequestParam(name = "email", required = false) String email,
            Model model,
            Locale locale) {
        if (!EmailAddresses.isValid(email)) {
            return reject(id, email, "ui.websites.detail.empfaenger.fehler.invalid", model, locale);
        }

        try {
            recipientService.add(id, email);
            return "redirect:/websites/" + id;
        } catch (IllegalArgumentException e) {
            String key = "recipient.email.duplicate".equals(e.getMessage())
                    ? "ui.websites.detail.empfaenger.fehler.duplicate"
                    : "ui.websites.detail.empfaenger.fehler.invalid";
            return reject(id, email, key, model, locale);
        }
    }

    @PostMapping("/websites/{id}/empfaenger/{rid}/loeschen")
    public String remove(@PathVariable("id") long id, @PathVariable("rid") long rid) {
        recipientService.remove(id, rid);
        return "redirect:/websites/" + id;
    }

    private String reject(long siteId, String email, String messageKey, Model model, Locale locale) {
        siteDetailModel.populate(siteId, model);
        model.addAttribute("email", email);
        model.addAttribute("recipientError", messageSource.getMessage(messageKey, null, locale));
        return "websites/detail";
    }
}
