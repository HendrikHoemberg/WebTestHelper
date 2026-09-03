package dev.hendrikhoemberg.webtesthelper.web;

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
    public ResponseEntity<Void> abschliessen(Principal principal) {
        if (principal != null) {
            userService.setTutorialAbgeschlossen(principal.getName(), true);
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/neustarten")
    public String neustarten(Principal principal) {
        if (principal != null) {
            userService.setTutorialAbgeschlossen(principal.getName(), false);
        }
        return "redirect:/?tour=start";
    }
}
