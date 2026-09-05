package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.auth.AppUserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.security.Principal;

@Controller
@RequestMapping("/tutorial")
public class TutorialController {

    private final AppUserService userService;

    public TutorialController(AppUserService userService) {
        this.userService = userService;
    }

    @PostMapping("/abschliessen")
    public ResponseEntity<Void> abschliessen(Principal principal, HttpSession session) {
        if (principal != null) {
            userService.setTutorialAbgeschlossen(principal.getName(), true);
            if (session != null) {
                session.setAttribute(TutorialAdvice.SESSION_KEY, false);
            }
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/neustarten")
    public String neustarten(Principal principal, HttpSession session) {
        if (principal != null) {
            userService.setTutorialAbgeschlossen(principal.getName(), false);
            if (session != null) {
                session.setAttribute(TutorialAdvice.SESSION_KEY, true);
            }
        }
        return "redirect:/?tour=start";
    }
}
