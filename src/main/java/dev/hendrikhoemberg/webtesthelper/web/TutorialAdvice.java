package dev.hendrikhoemberg.webtesthelper.web;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;

import java.security.Principal;

@ControllerAdvice
public class TutorialAdvice {

    private final ObjectProvider<AppUserService> userServiceProvider;

    public TutorialAdvice(ObjectProvider<AppUserService> userServiceProvider) {
        this.userServiceProvider = userServiceProvider;
    }

    @ModelAttribute
    public void tutorialModel(Model model, Principal principal,
                              @RequestParam(name = "tour", required = false) String tourParam) {
        if (principal == null) {
            model.addAttribute("tutorialOffen", false);
            return;
        }
        if ("start".equalsIgnoreCase(tourParam)) {
            model.addAttribute("tutorialOffen", true);
            return;
        }
        AppUserService service = userServiceProvider.getIfAvailable();
        boolean abgeschlossen = service != null && service.isTutorialAbgeschlossen(principal.getName());
        model.addAttribute("tutorialOffen", !abgeschlossen);
    }
}
