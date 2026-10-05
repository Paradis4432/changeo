package ar.changeo.config;

import ar.changeo.moderation.ModerationWorker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name={"changeo.synthetic-moderation","changeo.moderation-worker"},havingValue="true")
class ModerationSchedule {
    private final ModerationWorker worker;
    ModerationSchedule(ModerationWorker worker) { this.worker=worker; }
    @Scheduled(fixedDelay=1000) void process() { for(int count=0;count<4 && worker.processOne();count++) { } }
}
