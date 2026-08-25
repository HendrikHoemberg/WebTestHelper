package dev.hendrikhoemberg.webtesthelper.web;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

@Controller
@RequestMapping("/hilfe")
public class HelpController {

    private final HelpService helpService;

    public HelpController(HelpService helpService) {
        this.helpService = helpService;
    }

    @GetMapping
    public String index(Model model) {
        model.addAttribute("topics", helpService.all());
        return "hilfe/index";
    }

    @GetMapping("/{id}")
    public String thema(@PathVariable("id") String id, Model model) {
        HelpTopic topic = helpService.byId(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Hilfethema nicht gefunden: " + id));
        model.addAttribute("topic", topic);
        return "hilfe/thema";
    }

    @GetMapping("/hinweis/{id}")
    public String hinweis(@PathVariable("id") String id, Model model) {
        HelpTopic topic = helpService.byId(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Hilfethema nicht gefunden: " + id));
        model.addAttribute("thema", topic);
        return "fragments/hinweis";
    }
}
