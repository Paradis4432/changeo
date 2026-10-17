package ar.changeo.notifications;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class NotificationController {
    private final NotificationService notifications;
    NotificationController(NotificationService notifications) { this.notifications = notifications; }
    @GetMapping("/notifications") public String own(Model model) { model.addAttribute("notices", notifications.own()); return "conversation-notices"; }
}
