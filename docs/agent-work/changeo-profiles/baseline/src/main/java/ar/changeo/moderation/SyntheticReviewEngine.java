package ar.changeo.moderation;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SyntheticReviewEngine implements ReviewEngine {
    private final boolean enabled;
    public SyntheticReviewEngine(@Value("${changeo.synthetic-moderation:false}") boolean enabled) { this.enabled = enabled; }
    @Override public Map<String, Object> review(ReviewInput input) {
        ReviewResult result=new ReviewResult("HOLD","ADULT","DOUBT");
        if(enabled) {
            if(input.text().equals("synthetic:outage")) { throw new ReviewUnavailable(); }
            if(input.text().equals("synthetic:invalid")) { return Map.of("action","APPROVE"); }
            result=SyntheticPolicy.fixture(input.text()).orElse(result);
        }
        return Map.of("revision", input.reference().revisionId().toString(), "digest", input.digest(), "files", input.attachments().stream().map(f -> f.id() + ":" + f.digest()).sorted().toList(),
                "action", result.action(), "label", result.label(), "reason", result.reason(), "policy", "synthetic-v1", "model", "local-fixture-v1", "scope", "LOCAL_SYNTHETIC");
    }
    static final class ReviewUnavailable extends RuntimeException {}
}
