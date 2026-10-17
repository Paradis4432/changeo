package ar.changeo.agreements.api;

import ar.changeo.identity.api.JobContext.FulfillmentMode;
import java.time.*;
import java.util.*;

public record AgreementTerms(String scope, Long serviceMinor, Long deliveryMinor, FulfillmentMode mode,
        Arrangement arrangement, String arrangementDescription, String privateInstructions, String deliverables,
        Instant completionDeadline, Integer feedbackRounds, Evidence completionEvidence, String evidenceDescription,
        PolicyScope policyScope, PolicyVersion policyVersion, FinalRule finalRule, ReviewTrigger reviewTrigger,
        Integer reviewWindowMinutes, SupportRoute supportRoute, InactivityRule inactivityRule) {
    public enum Arrangement { APPOINTMENT, REMOTE, SHIPPING, PICKUP }
    public enum Evidence { TEXT, PRIVATE_FILE }
    public enum PolicyScope { PRIVATE_SYNTHETIC }
    public enum PolicyVersion { S1_EXPLICIT_ACCEPTANCE_V1 }
    public enum FinalRule { EXPLICIT_CUSTOMER_ACCEPTANCE }
    public enum ReviewTrigger { APPROVED_FINAL_DELIVERY }
    public enum SupportRoute { NONFINANCIAL_SUPPORT_CASE }
    public enum InactivityRule { NO_AUTOMATIC_ACCEPTANCE_OR_RELEASE }

    public AgreementTerms {
        scope = text(scope); arrangementDescription = text(arrangementDescription); privateInstructions = text(privateInstructions);
        deliverables = text(deliverables); evidenceDescription = text(evidenceDescription);
        if ((serviceMinor != null && serviceMinor < 0) || (deliveryMinor != null && deliveryMinor < 0)
                || (feedbackRounds != null && (feedbackRounds < 0 || feedbackRounds > 10))
                || (reviewWindowMinutes != null && (reviewWindowMinutes < 1 || reviewWindowMinutes > 1440))) {
            throw new IllegalArgumentException("Importes o límites técnicos inválidos");
        }
        if (serviceMinor != null && deliveryMinor != null) { Math.addExact(serviceMinor, deliveryMinor); }
        if (completionDeadline != null && reviewWindowMinutes != null) { dueAt(completionDeadline, reviewWindowMinutes); }
    }

    public List<String> missing() {
        List<String> fields = new ArrayList<>();
        if (scope.isBlank()) { fields.add("alcance"); }
        if (serviceMinor == null || deliveryMinor == null) { fields.add("importes exactos ARS"); }
        if (mode == null || arrangement == null || arrangementDescription.isBlank()) { fields.add("modalidad y coordinación"); }
        if (deliverables.isBlank() || completionDeadline == null) { fields.add("entregables y fecha de finalización"); }
        if (feedbackRounds == null || completionEvidence == null || evidenceDescription.isBlank()) { fields.add("revisiones y evidencia"); }
        if (policyScope == null || policyVersion == null || finalRule == null || reviewTrigger == null
                || reviewWindowMinutes == null || supportRoute == null || inactivityRule == null) { fields.add("política técnica privada explícita"); }
        if (mode != null && arrangement != null && !compatible()) { fields.add("coordinación compatible"); }
        if (arrangement != null && Set.of(Arrangement.SHIPPING, Arrangement.PICKUP, Arrangement.APPOINTMENT).contains(arrangement) && privateInstructions.isBlank()) {
            fields.add("instrucciones privadas");
        }
        return List.copyOf(fields);
    }

    public AgreementTerms requireComplete(FulfillmentMode actualMode) {
        if (!missing().isEmpty() || actualMode != mode) { throw new IllegalArgumentException("El presupuesto todavía es exploratorio o incompatible"); }
        totalMinor();
        return this;
    }
    public long totalMinor() {
        if (serviceMinor == null || deliveryMinor == null) { throw new IllegalArgumentException("Importe pendiente"); }
        return Math.addExact(serviceMinor, deliveryMinor);
    }
    public String servicePrice() { return serviceMinor == null ? "A convenir" : "ARS " + java.math.BigDecimal.valueOf(serviceMinor, 2).toPlainString(); }
    public String deliveryPrice() { return deliveryMinor == null ? "A convenir" : "ARS " + java.math.BigDecimal.valueOf(deliveryMinor, 2).toPlainString(); }
    public String totalPrice() { return serviceMinor == null || deliveryMinor == null ? "A convenir" : "ARS " + java.math.BigDecimal.valueOf(totalMinor(), 2).toPlainString(); }
    public Instant dueAt(Instant approvedAt) {
        if (reviewWindowMinutes == null) { throw new IllegalArgumentException("Plazo técnico pendiente"); }
        return dueAt(approvedAt, reviewWindowMinutes);
    }
    public String reviewText() {
        var output = new StringBuilder("CHANGEO_WHOLE_AGREEMENT_S1\n");
        for (Object field : new Object[]{scope, serviceMinor, deliveryMinor, mode, arrangement, arrangementDescription, privateInstructions,
                deliverables, completionDeadline, feedbackRounds, completionEvidence, evidenceDescription, policyScope, policyVersion,
                finalRule, reviewTrigger, reviewWindowMinutes, supportRoute, inactivityRule}) {
            String value = field == null ? "" : field.toString();
            output.append(field == null ? -1 : value.length()).append(':').append(value).append('\n');
        }
        return output.toString();
    }
    private boolean compatible() {
        return switch (mode) {
            case REMOTE -> arrangement == Arrangement.REMOTE;
            case LOCAL -> arrangement == Arrangement.APPOINTMENT || arrangement == Arrangement.PICKUP;
            case SHIPPED -> arrangement == Arrangement.SHIPPING || arrangement == Arrangement.PICKUP;
        };
    }
    private static Instant dueAt(Instant start, int minutes) {
        try { return start.plusSeconds(Math.multiplyExact((long) minutes, 60)); }
        catch (DateTimeException | ArithmeticException invalid) { throw new IllegalArgumentException("Plazo técnico fuera de rango", invalid); }
    }
    private static String text(String value) {
        if (value == null) { return ""; }
        if (value.length() > 4000 || value.indexOf('\0') >= 0) { throw new IllegalArgumentException("Texto demasiado largo o inválido"); }
        return value.trim();
    }
}
