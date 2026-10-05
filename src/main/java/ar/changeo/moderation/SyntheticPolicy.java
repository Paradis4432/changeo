package ar.changeo.moderation;

import java.util.*;

final class SyntheticPolicy {
    private static final Map<String,ReviewResult> FIXTURES=Map.of(
            "synthetic:clear",new ReviewResult("APPROVE","GENERAL","CLEAR"),
            "synthetic:tattoo",new ReviewResult("APPROVE","GENERAL","CLEAR"),
            "synthetic:medical",new ReviewResult("APPROVE","SENSITIVE","CLEAR"),
            "synthetic:adult",new ReviewResult("APPROVE","ADULT","CLEAR"),
            "synthetic:prohibited",new ReviewResult("REJECT","ADULT","PROHIBITED"));
    private SyntheticPolicy() {}
    static Optional<ReviewResult> fixture(String text) { return Optional.ofNullable(FIXTURES.get(text)); }
    static boolean prohibited(String text) { return fixture(text).filter(r->r.reason().equals("PROHIBITED")).isPresent(); }
}
