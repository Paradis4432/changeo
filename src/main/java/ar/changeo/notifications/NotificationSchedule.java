package ar.changeo.notifications;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "changeo.notification-worker", havingValue = "true")
class NotificationSchedule {
    private final NotificationService service;
    NotificationSchedule(NotificationService service) { this.service = service; }
    @Scheduled(fixedDelay = 3000) void tick() { service.runOnce(); }
}
