package ar.changeo.agreements;

import ar.changeo.agreements.api.*;
import ar.changeo.conversations.*;
import ar.changeo.conversations.api.ConversationAccess;
import ar.changeo.identity.api.*;
import ar.changeo.jobs.api.JobAccess;
import ar.changeo.identity.SecurityProof;
import ar.changeo.moderation.Admission;
import ar.changeo.moderation.ModerationService;
import ar.changeo.moderation.api.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class AgreementController {
    private static final String COMPOSITION = "agreementComposition";
    private final AgreementService service;
    private final JobAccess jobs;
    private final ConversationAccess contexts;
    private final Admission admission;
    private final AgreementReader agreements;
    private final SecurityProof proof;
    private final ModerationService moderation;
    private final boolean syntheticPayments;
    AgreementController(AgreementService service, JobAccess jobs, ConversationAccess contexts, Admission admission, AgreementReader agreements, SecurityProof proof, ModerationService moderation,
            @Value("${changeo.synthetic-payments:false}") boolean syntheticPayments) {
        this.service = service; this.jobs = jobs; this.contexts = contexts; this.admission = admission; this.agreements = agreements; this.proof = proof; this.moderation = moderation;
        this.syntheticPayments = syntheticPayments;
    }
    @ModelAttribute("syntheticPayments") public boolean syntheticPayments() { return syntheticPayments; }
    @GetMapping("/agreements/new") public String create(@RequestParam UUID job, @RequestParam UUID subject, @RequestParam(defaultValue = "INITIAL") String kind,
            @RequestParam(required = false) UUID resource, @RequestParam(required = false) UUID revision, Model model) {
        var context = agreements.composition(job, new SubjectId(subject));
        if (!Set.of("INITIAL", "CHANGE").contains(kind)) { throw new IllegalArgumentException("Tipo de propuesta inválido"); }
        model.addAttribute("context", context); model.addAttribute("subject", subject); model.addAttribute("kind", kind); model.addAttribute("command", UUID.randomUUID());
        model.addAttribute("edit", editable(job, subject, kind, resource, revision).orElse(null));
        return "agreement-scope";
    }
    @PostMapping("/agreements/compose") public String compose(@RequestParam UUID command, @RequestParam UUID job, @RequestParam UUID subject,
            @RequestParam String kind, @RequestParam String scope, @RequestParam(required = false) BigDecimal serviceAmount,
            @RequestParam(required = false) BigDecimal deliveryAmount, @RequestParam(defaultValue = "") String deliverables,
            @RequestParam(required = false) UUID resource, @RequestParam(required = false) UUID revision, @RequestParam(defaultValue = "0") long version, HttpServletRequest request, Model model) {
        source(request); var context = agreements.composition(job, new SubjectId(subject));
        if (scope.length() > 4000 || deliverables.length() > 4000 || !Set.of("INITIAL", "CHANGE").contains(kind)) { throw new IllegalArgumentException("Campos inválidos"); }
        var previous = editable(job, subject, kind, resource, revision);
        if (previous.isPresent() && previous.orElseThrow().version() != version) { throw new IllegalArgumentException("El presupuesto cambió"); }
        var composition = new Composition(command, job, subject, kind, scope, minor(serviceAmount), minor(deliveryAmount), deliverables, Optional.ofNullable(resource), version, previous.map(AgreementSnapshot::terms));
        request.getSession().setAttribute(COMPOSITION, composition); model.addAttribute("composition", composition); model.addAttribute("context", context);
        var previousTerms = composition.previous().orElse(null); model.addAttribute("previous", previousTerms);
        model.addAttribute("deadline", previousTerms == null || previousTerms.completionDeadline() == null ? "" : java.time.LocalDateTime.ofInstant(previousTerms.completionDeadline(), java.time.ZoneId.of("America/Argentina/Buenos_Aires")).toString());
        return "agreement-details";
    }
    @PostMapping("/agreements/drafts") public String save(@RequestParam UUID stage, @RequestParam(required = false) AgreementTerms.Arrangement arrangement,
            @RequestParam(defaultValue = "") String arrangementDescription, @RequestParam(defaultValue = "") String privateInstructions,
            @RequestParam(defaultValue = "") String completionDeadline, @RequestParam(required = false) Integer feedbackRounds,
            @RequestParam(required = false) AgreementTerms.Evidence completionEvidence, @RequestParam(defaultValue = "") String evidenceDescription,
            @RequestParam(required = false) Integer reviewWindowMinutes, @RequestParam String label, @RequestParam(required = false) List<MultipartFile> uploads,
            HttpServletRequest request, RedirectAttributes redirect) throws IOException {
        source(request);
        Object found = request.getSession().getAttribute(COMPOSITION);
        if (!(found instanceof Composition composition) || !stage.equals(composition.command())) { throw new IllegalArgumentException("El formulario venció o cambió"); }
        var context = jobs.discover(composition.job()).conversation();
        var terms = new AgreementTerms(composition.scope(), composition.serviceMinor(), composition.deliveryMinor(), context.mode(), arrangement, arrangementDescription,
                privateInstructions, composition.deliverables(), completionDeadline.isBlank() ? null : java.time.LocalDateTime.parse(completionDeadline).atZone(java.time.ZoneId.of("America/Argentina/Buenos_Aires")).toInstant(), feedbackRounds, completionEvidence, evidenceDescription,
                AgreementTerms.PolicyScope.PRIVATE_SYNTHETIC, AgreementTerms.PolicyVersion.S1_EXPLICIT_ACCEPTANCE_V1, AgreementTerms.FinalRule.EXPLICIT_CUSTOMER_ACCEPTANCE,
                AgreementTerms.ReviewTrigger.APPROVED_FINAL_DELIVERY, reviewWindowMinutes, AgreementTerms.SupportRoute.NONFINANCIAL_SUPPORT_CASE, AgreementTerms.InactivityRule.NO_AUTOMATIC_ACCEPTANCE_OR_RELEASE);
        var saved = service.save(new AgreementService.Draft(stage, composition.job(), composition.quoteId(), composition.version(), new SubjectId(composition.subject()), composition.kind(), terms, label, ConversationController.uploads(uploads)));
        request.getSession().removeAttribute(COMPOSITION); redirect.addFlashAttribute("notice", "Presupuesto exploratorio guardado. Revisá todos los términos antes de enviarlo a moderación.");
        return location(context.id(), composition.subject()) + "&quote=" + saved.resourceId();
    }
    @PostMapping("/agreements/submit") public String submit(@RequestParam UUID resource, @RequestParam UUID revision, @RequestParam UUID subject,
            @RequestParam UUID thread, HttpServletRequest request) {
        source(request); requireOwn(thread, subject, resource, revision); service.submit(new ContentReference(SurfaceKind.QUOTE_DESCRIPTION, resource, revision)); return location(thread, subject);
    }
    @PostMapping("/agreements/retry") public String retry(@RequestParam UUID command, @RequestParam UUID resource, @RequestParam UUID revision,
            @RequestParam UUID subject, @RequestParam UUID thread, @RequestParam long generation, HttpServletRequest request) {
        source(request); requireOwn(thread, subject, resource, revision); moderation.retry(command, new ContentReference(SurfaceKind.QUOTE_DESCRIPTION, resource, revision), generation);
        return location(thread, subject) + "&quote=" + resource;
    }
    private void requireOwn(UUID thread, UUID subject, UUID resource, UUID revision) {
        var context = contexts.discover(thread);
        var own = agreements.ownPage(context.jobId(), new SubjectId(subject), Optional.of(resource), Optional.empty()).entries();
        if (own.size() != 1 || !own.getFirst().reference().equals(new ContentReference(SurfaceKind.QUOTE_DESCRIPTION, resource, revision))) { throw new IllegalArgumentException("Revisión propia exacta requerida"); }
    }
    @PostMapping("/agreements/accept") public String accept(@RequestParam UUID command, @RequestParam UUID resource, @RequestParam UUID revision,
            @RequestParam long version, @RequestParam UUID subject, HttpServletRequest request, RedirectAttributes redirect) {
        source(request); var job = service.accept(command, new ContentReference(SurfaceKind.QUOTE_DESCRIPTION, resource, revision), version, new SubjectId(subject));
        redirect.addFlashAttribute("notice", "Aceptación expresa registrada para esta revisión. La financiación, liberación y pago bancario tienen estados separados.");
        return location(job.conversation().id(), subject);
    }
    private static Long minor(BigDecimal amount) {
        if (amount == null) { return null; }
        if (amount.signum() < 0 || amount.scale() > 2) { throw new IllegalArgumentException("Usá importes ARS positivos con hasta dos decimales"); }
        return amount.movePointRight(2).longValueExact();
    }
    private Optional<AgreementSnapshot> editable(UUID job, UUID subject, String kind, UUID resource, UUID revision) {
        if ((resource == null) != (revision == null)) { throw new IllegalArgumentException("Revisión incompleta"); }
        if (resource == null) { return Optional.empty(); }
        var quote = agreements.editable(new ContentReference(SurfaceKind.QUOTE_DESCRIPTION, resource, revision), new SubjectId(subject));
        if (!quote.jobId().equals(job) || !quote.current() || !quote.kind().equals(kind) || !quote.author().subject().value().equals(subject)
                || !proof.actor().filter(quote.author().actor().value()::equals).isPresent()
                || jobs.discover(job).agreement().filter(reference -> reference.resourceId().equals(resource)).isPresent()) { throw new IllegalArgumentException("Presupuesto ajeno, aceptado o desactualizado"); }
        return Optional.of(quote);
    }
    private void source(HttpServletRequest request) { admission.source(request.getRemoteAddr(), "agreement-mutation"); }
    private static String location(UUID thread, UUID subject) { return "redirect:/conversations/" + thread + "?subject=" + subject; }
    public record Composition(UUID command, UUID job, UUID subject, String kind, String scope, Long serviceMinor, Long deliveryMinor, String deliverables,
            Optional<UUID> quoteId, long version, Optional<AgreementTerms> previous) {}
}
