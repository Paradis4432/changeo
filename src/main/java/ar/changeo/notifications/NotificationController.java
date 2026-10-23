package ar.changeo.notifications;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ar.changeo.moderation.OwnerRegistry;
import java.util.Optional;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.Map;

@Controller
public class NotificationController {
    private final NotificationService notifications;
    private final OwnerRegistry owners;
    NotificationController(NotificationService notifications, OwnerRegistry owners) { this.notifications = notifications; this.owners = owners; }
    @GetMapping("/notifications") public String own(@RequestParam(required = false) UUID after, Model model) {
        var page = notifications.own(Optional.ofNullable(after));
        Map<UUID, Optional<String>> origins = new LinkedHashMap<>();
        for (var notice : page.notices()) { origins.put(notice.id(), owners.require(notice.reference()).caseOrigin(notice.reference())); }
        model.addAttribute("notices", page.notices());
        model.addAttribute("origins", Map.copyOf(origins));
        model.addAttribute("next", page.next());
        model.addAttribute("older", after != null);
        return "conversation-notices";
    }
}
