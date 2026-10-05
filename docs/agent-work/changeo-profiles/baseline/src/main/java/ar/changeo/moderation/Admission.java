package ar.changeo.moderation;

import ar.changeo.identity.AbuseLimits;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class Admission {
    private final AbuseLimits limits;
    public Admission(AbuseLimits limits) { this.limits=limits; }
    public void actor(UUID actor, String operation) { require("moderation-actor:"+operation+":"+actor,10); }
    public void source(String source, String operation) { require("moderation-source:"+operation+":"+source,20); }
    private void require(String key,int maximum) { if (!limits.allow(key,maximum)) { throw new ModerationFailure("Demasiados intentos; reintentá en un minuto. Tus estados y ayuda siguen disponibles."); } }
}
