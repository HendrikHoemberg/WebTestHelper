package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.auth.AppUserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;

import java.security.Principal;

@ControllerAdvice
public class TutorialAdvice {

    /** Session slot for the per-user tutorial flag, so the DB is read once per session (D36). */
    static final String SESSION_KEY = "wth.tutorialOffen";

    private final ObjectProvider<AppUserService> userServiceProvider;

    public TutorialAdvice(ObjectProvider<AppUserService> userServiceProvider) {
        this.userServiceProvider = userServiceProvider;
    }

    @ModelAttribute
    public void tutorialModel(Model model, Principal principal,
                              @RequestParam(name = "tour", required = false) String tourParam,
                              HttpSession session) {
        if (principal == null) {
            model.addAttribute("tutorialOffen", false);
            return;
        }
        if ("start".equalsIgnoreCase(tourParam)) {
            if (session != null) {
                session.setAttribute(SESSION_KEY, true);
            }
            model.addAttribute("tutorialOffen", true);
            return;
        }
        if (session != null && session.getAttribute(SESSION_KEY) instanceof Boolean cached) {
            model.addAttribute("tutorialOffen", cached);
            return;
        }
        AppUserService service = userServiceProvider.getIfAvailable();
        boolean abgeschlossen = service != null && service.isTutorialAbgeschlossen(principal.getName());
        boolean offen = !abgeschlossen;
        if (session != null) {
            session.setAttribute(SESSION_KEY, offen);
        }
        model.addAttribute("tutorialOffen", offen);
    }
}
