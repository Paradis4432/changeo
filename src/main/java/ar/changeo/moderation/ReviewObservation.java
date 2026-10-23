package ar.changeo.moderation;

import java.util.*;

record ReviewObservation(Outcome outcome, Reason reason, Integer httpStatus, String responseId, String returnedModel,
                         String responseHash, Boolean flagged, Map<String, Category> categories, Classification classification) {
    ReviewObservation {
        Objects.requireNonNull(outcome); Objects.requireNonNull(reason);
        categories=Map.copyOf(categories);
        if(httpStatus!=null && (httpStatus<100 || httpStatus>599)
                || responseId!=null && !responseId.matches("[A-Za-z0-9_-]{1,128}")
                || returnedModel!=null && !Set.of(Wire.MODERATIONS.model(),Wire.RESPONSES.model()).contains(returnedModel)
                || responseHash!=null && !responseHash.matches("[a-f0-9]{64}")) { throw new IllegalArgumentException("Invalid observation binding"); }
        if(outcome==Outcome.VALID && ((classification==null)==categories.isEmpty() || (flagged==null)!=categories.isEmpty())
                || outcome!=Outcome.VALID && (flagged!=null || !categories.isEmpty() || classification!=null)) {
            throw new IllegalArgumentException("Invalid observation shape");
        }
    }

    static ReviewObservation failure(Outcome outcome,Reason reason,Integer status,String hash) {
        return new ReviewObservation(outcome,reason,status,null,null,hash,null,Map.of(),null);
    }

    Map<String,Object> normalized() {
        if(classification!=null) {
            return Map.of("proposed_action",classification.action(),"proposed_label",classification.label(),"reason",classification.reason(),"uncertain",classification.uncertain());
        }
        if(categories.isEmpty()) { return Map.of(); }
        Map<String,Object> normalized=new TreeMap<>();
        categories.forEach((name,category)->normalized.put(name,Map.of("flagged",category.flagged(),"score",category.score(),"applied_input_types",category.appliedTypes().stream().sorted().toList())));
        return Map.of("flagged",flagged,"categories",Map.copyOf(normalized));
    }

    boolean retryable() { return outcome==Outcome.UNKNOWN || outcome==Outcome.UNAVAILABLE; }
    enum Wire {
        MODERATIONS("/v1/moderations","omni-moderation-latest"), RESPONSES("/v1/responses","gpt-6-luna");
        private final String path;
        private final String model;
        Wire(String path,String model) { this.path=path; this.model=model; }
        String path() { return path; }
        String model() { return model; }
    }
    enum Outcome { VALID, REFUSAL, INCOMPLETE, INVALID, UNAVAILABLE, UNKNOWN }
    enum Reason { PROTOCOL_VALID, REFUSAL, INCOMPLETE, PROTOCOL_INVALID, AUTHORIZATION, HTTP_REJECTED, REDIRECT, RESPONSE_BOUND, TRANSPORT, DEADLINE, INTERRUPTED, CLOSED, RATE_LIMIT, SERVER_ERROR }
    record Category(boolean flagged,double score,Set<String> appliedTypes) {
        Category {
            appliedTypes=Set.copyOf(appliedTypes);
            if(!Double.isFinite(score) || score<0 || score>1 || appliedTypes.isEmpty() || !Set.of("text","image").containsAll(appliedTypes)) {
                throw new IllegalArgumentException("Invalid category observation");
            }
        }
    }
    record Classification(String action,String label,String reason,boolean uncertain) {
        Classification {
            if(!Set.of("APPROVE","HOLD","REJECT").contains(action) || !Set.of("GENERAL","SENSITIVE","ADULT").contains(label)
                    || !Set.of("CLEAR","DOUBT","PROHIBITED","UNINSPECTABLE").contains(reason)
                    || action.equals("APPROVE") && (!reason.equals("CLEAR") || uncertain)
                    || action.equals("REJECT") && !reason.equals("PROHIBITED")
                    || action.equals("HOLD") && !Set.of("DOUBT","UNINSPECTABLE").contains(reason)) {
                throw new IllegalArgumentException("Contradictory draft observation");
            }
        }
    }
}
