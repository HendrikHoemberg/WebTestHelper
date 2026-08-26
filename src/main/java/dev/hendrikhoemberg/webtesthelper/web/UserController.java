package dev.hendrikhoemberg.webtesthelper.web;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Locale;

/**
 * User administration on the Settings screen (§12). The list is a read-mostly screen: every write is
 * a small POST that redirects back with a flash. The create POST is the one write that re-renders,
 * because a validation failure belongs next to the field it concerns rather than on the far side of a
 * redirect. The last-enable-admin guard (D71) is enforced in the service; a row that failure protects
 * renders its disable, demote and delete controls absent, so the user never sees a control that would
 * answer with an error — see {@code einstellungen/benutzer.html}.
 */
@Controller
@RequestMapping("/einstellungen/benutzer")
public class UserController {

    private final AppUserService userService;
    private final MessageSource messageSource;

    public UserController(AppUserService userService, MessageSource messageSource) {
        this.userService = userService;
        this.messageSource = messageSource;
    }

    @GetMapping
    public String index(@ModelAttribute("form") UserFormModel form, Model model) {
        populate(model);
        return "einstellungen/benutzer";
    }

    @PostMapping
    public String create(@ModelAttribute("form") UserFormModel form,
                         BindingResult bindingResult,
                         Model model,
                         RedirectAttributes redirectAttributes,
                         Locale locale) {
        try {
            userService.create(form.getUsername(), form.getPassword(), AppRole.USER);
            redirectAttributes.addFlashAttribute("benutzerAngelegt", true);
            return "redirect:/einstellungen/benutzer";
        } catch (UserValidationException ex) {
            bindingResult.rejectValue(fieldFor(ex.messageKey()), ex.messageKey(), ex.args(),
                    messageSource.getMessage(ex.messageKey(), ex.args(), locale));
        }
        populate(model);
        return "einstellungen/benutzer";
    }

    @PostMapping("/{id}")
    public String change(@PathVariable long id,
                         @RequestParam("aktion") String aktion,
                         @RequestParam(name = "sollAdministrator", required = false) Boolean sollAdministrator,
                         @RequestParam(name = "aktiv", required = false) Boolean aktiv,
                         @RequestParam(name = "passwort", required = false) String passwort,
                         RedirectAttributes redirectAttributes,
                         Locale locale) {
        try {
            switch (aktion) {
                case "rolle" -> {
                    if (sollAdministrator == null) {
                        throw new IllegalArgumentException("sollAdministrator fehlt für die Rollenaktion");
                    }
                    userService.setRole(id, sollAdministrator ? AppRole.ADMIN : AppRole.USER);
                    redirectAttributes.addFlashAttribute("benutzerGespeichert", true);
                }
                case "aktiv" -> {
                    userService.setEnabled(id, Boolean.TRUE.equals(aktiv));
                    redirectAttributes.addFlashAttribute("benutzerGespeichert", true);
                }
                case "passwort" -> {
                    if (passwort != null && !passwort.isBlank()) {
                        userService.setPassword(id, passwort);
                        redirectAttributes.addFlashAttribute("benutzerGespeichert", true);
                    }
                }
                default -> throw new IllegalArgumentException("Unbekannte Aktion: " + aktion);
            }
        } catch (UserValidationException ex) {
            redirectAttributes.addFlashAttribute("flashError",
                    messageSource.getMessage(ex.messageKey(), ex.args(), locale));
        }
        return "redirect:/einstellungen/benutzer";
    }

    @PostMapping("/{id}/loeschen")
    public String delete(@PathVariable long id, RedirectAttributes redirectAttributes, Locale locale) {
        try {
            userService.delete(id);
            redirectAttributes.addFlashAttribute("benutzerGeloescht", true);
        } catch (UserValidationException ex) {
            redirectAttributes.addFlashAttribute("flashError",
                    messageSource.getMessage(ex.messageKey(), ex.args(), locale));
        }
        return "redirect:/einstellungen/benutzer";
    }

    private void populate(Model model) {
        model.addAttribute("benutzer", userService.list());
        model.addAttribute("letzterAdminGeschuetzt", userService.enabledAdminCount() <= 1);
    }

    private String fieldFor(String messageKey) {
        return "user.password.tooShort".equals(messageKey) ? "password" : "username";
    }
}
