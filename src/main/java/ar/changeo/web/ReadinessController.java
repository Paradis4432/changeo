package ar.changeo.web;

import ar.changeo.config.RuntimeReadiness;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
final class ReadinessController {
    private final RuntimeReadiness readiness;

    ReadinessController(RuntimeReadiness readiness) { this.readiness = readiness; }

    @GetMapping(value = "/readyz", produces = "text/plain;charset=UTF-8")
    ResponseEntity<String> ready() {
        boolean ready = readiness.ready();
        return ResponseEntity.status(ready ? 200 : 503).contentType(new MediaType("text", "plain", java.nio.charset.StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore()).body(ready ? "ready\n" : "unavailable\n");
    }
}
