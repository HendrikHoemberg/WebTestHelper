package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.reporting.OutboxDetail;
import dev.hendrikhoemberg.webtesthelper.reporting.OutboxService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/postausgang")
public class OutboxController {

    private final OutboxService outboxService;

    public OutboxController(OutboxService outboxService) {
        this.outboxService = outboxService;
    }

    @GetMapping
    public String index(Model model) {
        model.addAttribute("eintraege", outboxService.recent(50));
        return "postausgang/index";
    }

    @PostMapping("/{id}/wiederholen")
    public String wiederholen(@PathVariable("id") long id, RedirectAttributes redirectAttributes) {
        outboxService.retry(id);
        redirectAttributes.addFlashAttribute("erfolg", "E-Mail wird erneut versendet.");
        return "redirect:/postausgang";
    }

    @PostMapping("/alle-wiederholen")
    public String alleWiederholen(RedirectAttributes redirectAttributes) {
        int count = outboxService.retryAllFailed();
        redirectAttributes.addFlashAttribute("erfolg", count + " fehlgeschlagene E-Mails werden erneut versendet.");
        return "redirect:/postausgang";
    }

    @PostMapping("/{id}/loeschen")
    public String loeschen(@PathVariable("id") long id, RedirectAttributes redirectAttributes) {
        outboxService.delete(id);
        redirectAttributes.addFlashAttribute("erfolg", "E-Mail aus dem Postausgang gelöscht.");
        return "redirect:/postausgang";
    }

    @PostMapping("/alle-loeschen")
    public String alleLoeschen(RedirectAttributes redirectAttributes) {
        int count = outboxService.deleteAllFailed();
        redirectAttributes.addFlashAttribute("erfolg", count + " fehlgeschlagene Einträge wurden gelöscht.");
        return "redirect:/postausgang";
    }

    @GetMapping("/{id}/details")
    @ResponseBody
    public ResponseEntity<OutboxDetail> details(@PathVariable("id") long id) {
        return outboxService.findDetail(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
