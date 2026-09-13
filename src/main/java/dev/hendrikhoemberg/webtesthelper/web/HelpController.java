package dev.hendrikhoemberg.webtesthelper.web;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class HelpController {

    private final HelpService helpService;

    public HelpController(HelpService helpService) {
        this.helpService = helpService;
    }

    @GetMapping("/hilfe")
    public String hilfeRedirect() {
        return "redirect:/handbuch";
    }

    @GetMapping("/handbuch")
    public String index(@RequestParam(value = "q", required = false) String q,
                        @RequestHeader(value = "HX-Request", required = false) String hxRequest,
                        Model model) {
        model.addAttribute("topics", helpService.search(q));
        model.addAttribute("q", q != null ? q : "");
        if ("true".equalsIgnoreCase(hxRequest)) {
            return "hilfe/index :: themenListe";
        }
        return "hilfe/index";
    }

    @GetMapping({"/handbuch/{id}", "/hilfe/{id}"})
    public String thema(@PathVariable("id") String id, Model model) {
        HelpTopic topic = helpService.byId(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Hilfethema nicht gefunden: " + id));
        model.addAttribute("topic", topic);
        return "hilfe/thema";
    }

    @GetMapping({"/handbuch/hinweis/{id}", "/hilfe/hinweis/{id}"})
    public String hinweis(@PathVariable("id") String id, Model model) {
        HelpTopic topic = helpService.byId(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Hilfethema nicht gefunden: " + id));
        model.addAttribute("thema", topic);
        return "fragments/hinweis :: hinweis";
    }
}
