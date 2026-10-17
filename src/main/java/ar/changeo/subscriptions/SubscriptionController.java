package ar.changeo.subscriptions;

import ar.changeo.identity.api.SubjectId;
import ar.changeo.jobs.api.JobLoyaltyAccess;
import ar.changeo.payments.api.PromotionBudgetAccess;
import ar.changeo.moderation.Admission;
import ar.changeo.moderation.ModerationFailure;
import ar.changeo.security.AccountPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.time.Instant;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.http.HttpStatus;

@Controller
public class SubscriptionController {
    private final ObjectProvider<SubscriptionService> subscriptions;
    private final ObjectProvider<SubscriptionLoyaltyService> loyalty;
    private final Admission admission;

    SubscriptionController(ObjectProvider<SubscriptionService> subscriptions, ObjectProvider<SubscriptionLoyaltyService> loyalty, Admission admission) { this.subscriptions = subscriptions; this.loyalty = loyalty; this.admission = admission; }

    @GetMapping("/subscriptions")
    public String dashboard(@RequestParam(required = false) UUID subject, @RequestParam(required = false) UUID enrollment,
            @AuthenticationPrincipal AccountPrincipal principal, Model model) {
        var service = subscriptions.getIfAvailable();
        model.addAttribute("enabled", service != null);
        if (service != null) { model.addAttribute("subscription", service.view(new SubjectId(subject == null ? principal.id() : subject), Optional.ofNullable(enrollment))); }
        model.addAttribute("command", UUID.randomUUID()); return "subscription-dashboard";
    }

    @PostMapping("/subscriptions/enroll")
    public String enroll(@RequestParam UUID command, @RequestParam UUID subject, @RequestParam long version,
            @RequestParam String mode, @RequestParam String policyDigest, @RequestParam(defaultValue = "false") boolean recurringConsent,
            @RequestParam(defaultValue = "false") boolean useCredit, @RequestParam(required = false) UUID predecessor,
            HttpServletRequest request, RedirectAttributes redirect) {
        admission.source(request.getRemoteAddr(), "subscription-enrollment");
        requireEnabled().enroll(new SubscriptionService.EnrollmentCommand(command, new SubjectId(subject), version, mode, policyDigest, recurringConsent, useCredit, Optional.ofNullable(predecessor)));
        redirect.addFlashAttribute("notice", "Decisión conservada con sus términos exactos. Un cobro pendiente solo activa el período tras confirmación financiera completa.");
        return "redirect:/subscriptions?subject=" + subject;
    }

    @PostMapping("/subscriptions/cancel")
    public String cancel(@RequestParam UUID command, @RequestParam UUID enrollment, @RequestParam long version, @RequestParam UUID subject,
            HttpServletRequest request, RedirectAttributes redirect) {
        admission.source(request.getRemoteAddr(), "subscription-cancel"); requireEnabled().cancel(command, enrollment, version);
        redirect.addFlashAttribute("notice", "Futuros cobros cancelados. El tiempo válido se conserva y los intentos ya despachados siguen en conciliación.");
        return "redirect:/subscriptions?subject=" + subject + "&enrollment=" + enrollment;
    }

    @GetMapping("/subscriptions/progress")
    public String progress(@RequestParam(required = false) UUID subject, @RequestParam(required = false) UUID enrollment,
            @RequestParam(required = false) UUID after, @RequestParam(required = false) Long cohortVersion,
            @RequestParam(required = false) UUID wallet, @RequestParam(required = false) Long walletVersion,
            @RequestParam(required = false) Instant correctionAfterTime, @RequestParam(required = false) UUID correctionAfter,
            @RequestParam(required = false) String appealObservation, @RequestParam(required = false) Instant appealAfterTime,
            @RequestParam(required = false) UUID appealAfter, @AuthenticationPrincipal AccountPrincipal principal, Model model) {
        UUID provider = subject == null ? principal.id() : subject;
        model.addAttribute("subject", provider);
        model.addAttribute("progress", requireLoyalty().progress(new SubjectId(provider), Optional.ofNullable(enrollment), cursor(after, cohortVersion),
                historyCursor(wallet, walletVersion, correctionAfterTime, correctionAfter), appealCursor(enrollment, appealObservation, appealAfterTime, appealAfter)));
        return "subscription-progress";
    }

    @PostMapping("/subscriptions/recheck")
    public String recheck(@RequestParam UUID subject, @RequestParam(required = false) UUID enrollment, @RequestParam(required = false) UUID candidate,
            @RequestParam(required = false) UUID after, @RequestParam(required = false) Long cohortVersion, HttpServletRequest request, RedirectAttributes redirect) {
        admission.source(request.getRemoteAddr(), "subscription-recheck");
        var result = requireLoyalty().recheck(new SubjectId(subject), Optional.ofNullable(enrollment), Optional.ofNullable(candidate), cursor(after, cohortVersion));
        redirect.addFlashAttribute("notice", "Revisión actual conservada: " + result.verifiedMilestone() + " de 2 clientes verificados. Sin concesiones discrecionales.");
        return "redirect:/subscriptions/progress?subject=" + subject + "&enrollment=" + result.enrollmentId();
    }

    @PostMapping("/subscriptions/achievements")
    public String achievements(@RequestParam UUID subject, @RequestParam(required = false) UUID enrollment,
            @RequestParam(defaultValue = "false") boolean visible, HttpServletRequest request, RedirectAttributes redirect) {
        admission.source(request.getRemoteAddr(), "subscription-achievements");
        UUID selected = requireLoyalty().publicOptIn(new SubjectId(subject), Optional.ofNullable(enrollment), visible);
        redirect.addFlashAttribute("notice", visible ? "Solo tus hitos actualmente verificables pueden aparecer en tu perfil de proveedor." : "Tus logros no se muestran públicamente.");
        return progressRedirect(subject, selected);
    }

    @PostMapping("/subscriptions/appeal")
    public String appeal(@RequestParam UUID subject, @RequestParam(required = false) UUID enrollment, @RequestParam String reason, HttpServletRequest request, RedirectAttributes redirect) {
        admission.source(request.getRemoteAddr(), "subscription-appeal");
        var receipt = requireLoyalty().appealReceipt(new SubjectId(subject), Optional.ofNullable(enrollment), reason);
        redirect.addFlashAttribute("notice", "Revisión registrada. Al revalidar tus trabajos se aplica la misma regla publicada; no autoriza cobros ni crea crédito manual.");
        return progressRedirect(subject, receipt.enrollmentId());
    }

    @ExceptionHandler({SubscriptionFailure.class, IllegalStateException.class})
    public ModelAndView failure(RuntimeException failure) {
        var view = new ModelAndView("subscription-error"); view.setStatus(HttpStatus.CONFLICT);
        view.addObject("message", failure.getMessage()); return view;
    }

    @ExceptionHandler(ModerationFailure.class)
    public ModelAndView limited(ModerationFailure failure) {
        var view = new ModelAndView("subscription-error"); view.setStatus(HttpStatus.TOO_MANY_REQUESTS);
        view.addObject("message", failure.getMessage()); return view;
    }

    private SubscriptionService requireEnabled() {
        var service = subscriptions.getIfAvailable();
        if (service == null) { throw new SubscriptionFailure("Las suscripciones sintéticas están deshabilitadas; la participación original se conserva"); }
        return service;
    }
    private SubscriptionLoyaltyService requireLoyalty() {
        requireEnabled(); return loyalty.getIfAvailable();
    }
    private static Optional<JobLoyaltyAccess.Cursor> cursor(UUID after, Long version) {
        if ((after == null) != (version == null)) { throw new SubscriptionFailure("La continuación requiere su versión de cohorte"); }
        return after == null ? Optional.empty() : Optional.of(new JobLoyaltyAccess.Cursor(after, version));
    }
    private static Optional<PromotionBudgetAccess.HistoryCursor> historyCursor(UUID wallet, Long version, Instant time, UUID after) {
        int supplied = (wallet == null ? 0 : 1) + (version == null ? 0 : 1) + (time == null ? 0 : 1) + (after == null ? 0 : 1);
        if (supplied != 0 && supplied != 4) { throw new SubscriptionFailure("La continuación requiere su observación promocional completa"); }
        return supplied == 0 ? Optional.empty() : Optional.of(new PromotionBudgetAccess.HistoryCursor(wallet, version, time, after));
    }
    private static Optional<SubscriptionLoyaltyService.AppealCursor> appealCursor(UUID enrollment, String observation, Instant time, UUID after) {
        int supplied = (observation == null ? 0 : 1) + (time == null ? 0 : 1) + (after == null ? 0 : 1);
        if (supplied != 0 && (supplied != 3 || enrollment == null)) { throw new SubscriptionFailure("La continuación requiere su revisión original completa"); }
        return supplied == 0 ? Optional.empty() : Optional.of(new SubscriptionLoyaltyService.AppealCursor(enrollment, observation, time, after));
    }
    private static String progressRedirect(UUID subject, UUID enrollment) { return "redirect:/subscriptions/progress?subject=" + subject + (enrollment == null ? "" : "&enrollment=" + enrollment); }
}
