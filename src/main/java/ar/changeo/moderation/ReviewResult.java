package ar.changeo.moderation;

import java.util.*;

record ReviewResult(String action, String label, String reason) {
    static ReviewResult validate(ReviewInput input, Map<String, Object> result) {
        if (result == null || result.values().stream().anyMatch(Objects::isNull) || !result.keySet().equals(Set.of("revision", "digest", "files", "action", "label", "reason", "policy", "model", "scope"))
                || !input.reference().revisionId().toString().equals(result.get("revision")) || !input.digest().equals(result.get("digest"))
                || !input.attachments().stream().map(f -> f.id() + ":" + f.digest()).sorted().toList().equals(result.get("files"))
                || !"LOCAL_SYNTHETIC".equals(result.get("scope")) || !"synthetic-v1".equals(result.get("policy")) || !"local-fixture-v1".equals(result.get("model"))
                || !Set.of("APPROVE", "HOLD", "REJECT").contains(result.get("action")) || !Set.of("GENERAL", "SENSITIVE", "ADULT").contains(result.get("label"))
                || !coherent((String)result.get("action"),(String)result.get("label"),result.get("reason"))) { return new ReviewResult("HOLD", "ADULT", "INVALID"); }
        String action = (String) result.get("action");
        var expected=SyntheticPolicy.fixture(input.text());
        if(action.equals("APPROVE") && (expected.isEmpty() || !expected.orElseThrow().action().equals("APPROVE") || !expected.orElseThrow().label().equals(result.get("label")))) { return new ReviewResult("HOLD","ADULT","INVALID"); }
        if(SyntheticPolicy.prohibited(input.text()) && !action.equals("REJECT")) { return new ReviewResult("HOLD","ADULT","INVALID"); }
        if(action.equals("HOLD")) { return new ReviewResult("HOLD","ADULT",(String)result.get("reason")); }
        return new ReviewResult(action, (String) result.get("label"), (String) result.get("reason"));
    }
    private static boolean coherent(String action,String label,Object reason) {
        return switch(action) {
            case "APPROVE" -> "CLEAR".equals(reason);
            case "REJECT" -> label.equals("ADULT") && "PROHIBITED".equals(reason);
            case "HOLD" -> label.equals("ADULT") && Set.of("DOUBT","UNINSPECTABLE","INVALID","OUTAGE").contains(reason);
            default -> false;
        };
    }
}
