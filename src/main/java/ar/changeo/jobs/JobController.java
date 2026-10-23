package ar.changeo.jobs;

import ar.changeo.conversations.ConversationController;
import ar.changeo.conversations.MessageReader;
import ar.changeo.agreements.AgreementReader;
import ar.changeo.jobs.api.JobAccess;
import ar.changeo.identity.api.SubjectId;
import ar.changeo.moderation.Admission;
import ar.changeo.moderation.ModerationService;
import ar.changeo.moderation.api.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class JobController {
    private final JobExecutionService execution;
    private final JobContentService service;
    private final JobContentReader reader;
    private final Admission admission;
    private final JobSupportService support;
    private final JobAccess jobs;
    private final MessageReader messages;
    private final AgreementReader agreements;
    private final ModerationService moderation;
    private final boolean syntheticPayments;
    JobController(JobExecutionService execution, JobContentService service, JobContentReader reader, Admission admission, JobSupportService support,
            JobAccess jobs, MessageReader messages, AgreementReader agreements, ModerationService moderation,
            @Value("${changeo.synthetic-payments:false}") boolean syntheticPayments) {
        this.execution = execution; this.service = service; this.reader = reader; this.admission = admission; this.support = support;
        this.jobs = jobs; this.messages = messages; this.agreements = agreements; this.moderation = moderation; this.syntheticPayments = syntheticPayments;
    }
    @GetMapping("/jobs/origin") public String origin(@RequestParam SurfaceKind kind, @RequestParam UUID resource, @RequestParam UUID revision, Model model) {
        model.addAttribute("origin", reader.origin(new ContentReference(kind, resource, revision)));
        return "job-origin";
    }
    @GetMapping("/jobs/{id}") public String detail(@PathVariable UUID id, @RequestParam UUID subject, @RequestParam(required = false) UUID content, @RequestParam(required = false) UUID after, Model model) {
        var view = execution.view(id, new SubjectId(subject)); model.addAttribute("view", view); model.addAttribute("subject", subject); model.addAttribute("command", UUID.randomUUID());
        model.addAttribute("syntheticPayments", syntheticPayments);
        List<JobContentReader.ContentView> contents = new ArrayList<>();
        for (var reference : reader.candidates(id, view.job().finalDelivery())) { reader.display(id, reference, new SubjectId(subject)).ifPresent(contents::add); }
        model.addAttribute("finalDisplayed", contents.stream().anyMatch(entry -> entry.kind().equals("FINAL_DELIVERY") && view.job().finalDelivery().filter(entry.reference()::equals).isPresent()));
        model.addAttribute("contents", List.copyOf(contents)); own(id, subject, content, after, model); return "job-detail";
    }
    @PostMapping("/jobs/start") public String start(@RequestParam UUID command, @RequestParam UUID job, @RequestParam long version, @RequestParam UUID subject, HttpServletRequest request) {
        source(request); execution.start(command, job, version, new SubjectId(subject)); return location(job, subject);
    }
    @GetMapping("/jobs/{id}/evidence") public String evidence(@PathVariable UUID id, @RequestParam UUID subject,
            @RequestParam(required = false) String evidenceAfter, @RequestParam(required = false) String evidenceBefore, Model model) {
        var page = reader.evidencePage(id, new SubjectId(subject), Optional.ofNullable(evidenceAfter), Optional.ofNullable(evidenceBefore));
        model.addAttribute("evidencePage", page); model.addAttribute("jobId", id); model.addAttribute("subject", subject); model.addAttribute("command", UUID.randomUUID());
        return "job-evidence-history";
    }
    @PostMapping("/jobs/content") public String save(@RequestParam UUID command, @RequestParam UUID job, @RequestParam UUID subject,
            @RequestParam(required = false) UUID resource, @RequestParam(defaultValue = "0") long version, @RequestParam String kind, @RequestParam String body,
            @RequestParam(required = false) String score, @RequestParam String label, @RequestParam(required = false) List<MultipartFile> uploads,
            HttpServletRequest request, HttpServletResponse response, RedirectAttributes redirect, Model model) throws IOException {
        source(request);
        reader.requireDraftAccess(job, new SubjectId(subject));
        var form = new JobForms.Content(kind, body, score, label);
        var errors = new BeanPropertyBindingResult(form, "form");
        form.validate(errors);
        var selected = uploads == null ? List.<MultipartFile>of() : uploads.stream().filter(file -> !file.isEmpty()).toList();
        if (selected.size() > 4 || selected.stream().anyMatch(file -> file.getSize() > 1048576)) {
            errors.reject("uploads", "Elegí hasta cuatro archivos nuevos de hasta 1 MiB cada uno");
        }
        if (resource != null) {
            var entries = reader.ownPage(job, new SubjectId(subject), Optional.of(resource), Optional.empty()).entries();
            if (entries.size() != 1 || !entries.getFirst().visible() || !entries.getFirst().kind().equals(kind)
                    || (errors.hasErrors() && entries.getFirst().version() != version)) {
                throw new IllegalStateException("La revisión propia cambió o no está disponible para editar");
            }
        }
        if (errors.hasErrors()) {
            var bounded = form.bounded();
            var displayedErrors = new BeanPropertyBindingResult(bounded, "form");
            for (var error : errors.getAllErrors()) {
                if (error instanceof FieldError field) { displayedErrors.rejectValue(field.getField(), "invalid", field.getDefaultMessage()); }
                else { displayedErrors.reject("invalid", error.getDefaultMessage()); }
            }
            model.addAttribute("form", bounded); model.addAttribute(BindingResult.MODEL_KEY_PREFIX + "form", displayedErrors);
            model.addAttribute("command", command); model.addAttribute("jobId", job); model.addAttribute("subject", subject);
            model.addAttribute("resource", resource); model.addAttribute("version", version);
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return "job-content-form";
        }
        var saved = service.save(new JobContentService.Draft(command, job, Optional.ofNullable(resource), version, new SubjectId(subject), kind, body,
                form.parsedScore(), label, ConversationController.uploads(selected)));
        redirect.addFlashAttribute("notice", "Evidencia privada guardada, todavía sin entregar. Cada revisión y archivo requiere moderación.");
        return location(job, subject) + "&content=" + saved.resourceId();
    }
    @PostMapping("/jobs/submit") public String submit(@RequestParam UUID job, @RequestParam UUID subject, @RequestParam SurfaceKind kind, @RequestParam UUID resource,
            @RequestParam UUID revision, HttpServletRequest request) {
        source(request); requireOwn(job, subject, new ContentReference(kind, resource, revision)); service.submit(new ContentReference(kind, resource, revision)); return location(job, subject);
    }
    @PostMapping("/jobs/retry") public String retry(@RequestParam UUID command, @RequestParam UUID job, @RequestParam UUID subject, @RequestParam SurfaceKind kind,
            @RequestParam UUID resource, @RequestParam UUID revision, @RequestParam long generation, HttpServletRequest request) {
        source(request); var reference = new ContentReference(kind, resource, revision); requireOwn(job, subject, reference); moderation.retry(command, reference, generation);
        return "redirect:/jobs/" + job + "/authored?subject=" + subject + "&content=" + resource;
    }
    @GetMapping("/jobs/{id}/authored") public String authored(@PathVariable UUID id, @RequestParam UUID subject,
            @RequestParam(required = false) UUID content, @RequestParam(required = false) UUID after, Model model) {
        own(id, subject, content, after, model); model.addAttribute("jobId", id); model.addAttribute("subject", subject);
        model.addAttribute("command", UUID.randomUUID()); return "job-authored";
    }
    private void own(UUID id, UUID subject, UUID selected, UUID after, Model model) {
        var page = reader.ownPage(id, new SubjectId(subject), Optional.ofNullable(selected), Optional.ofNullable(after));
        model.addAttribute("prepared", page.entries()); model.addAttribute("contentNext", page.next());
        Map<UUID, Long> generations = new HashMap<>();
        for (var entry : page.entries()) { if (!entry.state().equals("PREPARED_UNSUBMITTED")) { generations.put(entry.reference().revisionId(), moderation.generation(entry.reference())); } }
        model.addAttribute("generations", generations);
        model.addAttribute("ownReview", reader.reviewResource(id, new SubjectId(subject)));
    }
    private void requireOwn(UUID job, UUID subject, ContentReference reference) {
        var own = reader.ownPage(job, new SubjectId(subject), Optional.of(reference.resourceId()), Optional.empty()).entries();
        if (own.size() != 1 || !own.getFirst().reference().equals(reference)) { throw new IllegalArgumentException("Revisión propia exacta requerida"); }
    }
    @PostMapping("/jobs/accept-final") public String accept(@RequestParam UUID command, @RequestParam UUID job, @RequestParam UUID subject, @RequestParam long version,
            @RequestParam UUID resource, @RequestParam UUID revision, HttpServletRequest request, RedirectAttributes redirect) {
        source(request); execution.acceptFinal(command, job, version, new SubjectId(subject), new ContentReference(SurfaceKind.JOB_FEEDBACK, resource, revision));
        redirect.addFlashAttribute("notice", "Finalización no financiera registrada por aceptación expresa. No equivale a liberación ni pago bancario."); return location(job, subject);
    }
    private void source(HttpServletRequest request) { admission.source(request.getRemoteAddr(), "job-mutation"); }
    @GetMapping("/jobs/{id}/support") public String support(@PathVariable UUID id, @RequestParam UUID subject, @RequestParam(required = false) UUID messageAfter,
            @RequestParam(required = false) String evidenceAfter, @RequestParam(required = false) String evidenceBefore, Model model) {
        var side = new SubjectId(subject);
        model.addAttribute("syntheticPayments", syntheticPayments);
        model.addAttribute("support", support.view(id, side)); model.addAttribute("subject", subject); model.addAttribute("command", UUID.randomUUID());
        var job = jobs.discover(id);
        var page = messages.historicalPage(job.conversation().id(), side, Optional.ofNullable(messageAfter));
        model.addAttribute("messages", page.messages()); model.addAttribute("messageNext", page.next()); model.addAttribute("messageAfter", messageAfter);
        model.addAttribute("terms", agreements.historical(job.agreement().orElseThrow(), side));
        var evidence = reader.historicalPage(id, side, Optional.ofNullable(evidenceAfter), Optional.ofNullable(evidenceBefore));
        model.addAttribute("evidencePage", evidence); model.addAttribute("evidenceAfter", evidenceAfter); model.addAttribute("evidenceBefore", evidenceBefore);
        return "job-support";
    }
    @GetMapping("/jobs/support") public String obligations(Model model) {
        model.addAttribute("syntheticPayments", syntheticPayments); model.addAttribute("obligations", support.obligations()); return "job-support-list";
    }
    @PostMapping("/jobs/support") public String supportRequest(@RequestParam UUID command, @RequestParam UUID job, @RequestParam long version, @RequestParam UUID subject,
            @RequestParam(defaultValue = "false") boolean dispute, HttpServletRequest request) {
        source(request); support.request(command, job, version, new SubjectId(subject), dispute); return "redirect:/jobs/" + job + "/support?subject=" + subject;
    }
    @PostMapping("/jobs/cancel") public String cancel(@RequestParam UUID command, @RequestParam UUID job, @RequestParam long version, @RequestParam UUID subject, HttpServletRequest request) {
        source(request); support.cancel(command, job, version, new SubjectId(subject)); return location(job, subject);
    }
    @PostMapping("/jobs/resume") public String resume(@RequestParam UUID command, @RequestParam UUID job, @RequestParam long version,
            @RequestParam UUID subject, @RequestParam UUID settlement, HttpServletRequest request) {
        source(request); support.resume(command, job, version, new SubjectId(subject), settlement); return location(job, subject);
    }
    private static String location(UUID job, UUID subject) { return "redirect:/jobs/" + job + "?subject=" + subject; }
}
