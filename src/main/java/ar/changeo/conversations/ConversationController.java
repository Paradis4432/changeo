package ar.changeo.conversations;

import ar.changeo.agreements.AgreementReader;
import ar.changeo.conversations.api.*;
import ar.changeo.files.api.*;
import ar.changeo.identity.GuardianService;
import ar.changeo.identity.api.*;
import ar.changeo.jobs.api.JobAccess;
import ar.changeo.marketplace.MarketplaceService.Upload;
import ar.changeo.moderation.*;
import ar.changeo.moderation.api.*;
import ar.changeo.security.AccountPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class ConversationController {
    private final ConversationService service;
    private final ConversationAccess conversations;
    private final MessageService messages;
    private final MessageReader reader;
    private final AgreementReader agreements;
    private final GuardianService guardians;
    private final JobAccess jobs;
    private final FileAccess files;
    private final ModerationService moderation;
    private final CaseService cases;
    private final Admission admission;
    private final boolean syntheticPayments;
    ConversationController(ConversationService service, ConversationAccess conversations, MessageService messages, MessageReader reader,
            AgreementReader agreements, GuardianService guardians, JobAccess jobs, FileAccess files, ModerationService moderation, CaseService cases, Admission admission,
            @Value("${changeo.synthetic-payments:false}") boolean syntheticPayments) {
        this.service = service; this.conversations = conversations; this.messages = messages; this.reader = reader; this.agreements = agreements;
        this.guardians = guardians; this.jobs = jobs; this.files = files; this.moderation = moderation; this.cases = cases; this.admission = admission;
        this.syntheticPayments = syntheticPayments;
    }
    @ModelAttribute("syntheticPayments") public boolean syntheticPayments() { return syntheticPayments; }
    @GetMapping("/conversations") public String inbox(@RequestParam(required = false) UUID subject, @AuthenticationPrincipal AccountPrincipal principal, Model model) {
        var selected = new SubjectId(subject == null ? principal.id() : subject);
        model.addAttribute("subject", selected.value()); model.addAttribute("guardianSubjects", guardians.own(principal.id()));
        model.addAttribute("self", principal.id()); model.addAttribute("threads", service.inbox(selected));
        return "conversation-inbox";
    }
    @PostMapping("/conversations/respond") public String respond(@RequestParam UUID listing, @RequestParam UUID subject, @RequestParam JobContext.FulfillmentMode mode, HttpServletRequest request) {
        source(request); UUID thread = service.respond(listing, new SubjectId(subject), mode); return location(thread, subject);
    }
    @GetMapping("/conversations/{id}") public String thread(@PathVariable UUID id, @RequestParam(required = false) UUID subject,
            @RequestParam(required = false) UUID message, @RequestParam(required = false) UUID messageAfter, @RequestParam(required = false) UUID quote, @RequestParam(required = false) UUID quoteAfter, @AuthenticationPrincipal AccountPrincipal principal, Model model) {
        var context = conversations.discover(id);
        UUID selected = subject == null ? (context.customerActor().value().equals(principal.id()) ? context.customer().value() : context.provider().value()) : subject;
        var display = service.view(id, new SubjectId(selected));
        model.addAttribute("context", display); model.addAttribute("subject", selected); model.addAttribute("job", jobs.discover(display.jobId()));
        model.addAttribute("command", UUID.randomUUID());
        model.addAttribute("poll", display.normalActions() ? reader.recent(id, new SubjectId(selected)) : new MessageReader.PollPage(List.of(), 0, false));
        var ownMessages = reader.ownPage(id, new SubjectId(selected), Optional.ofNullable(message), Optional.ofNullable(messageAfter));
        model.addAttribute("prepared", ownMessages.entries()); model.addAttribute("messageNext", ownMessages.next());
        List<ar.changeo.agreements.api.AgreementSnapshot> visible = new ArrayList<>();
        if (display.normalActions()) { for (var reference : agreements.candidates(display.jobId())) { visible.add(agreements.view(reference, new SubjectId(selected))); } }
        model.addAttribute("quotes", List.copyOf(visible));
        var ownQuotes = agreements.ownPage(display.jobId(), new SubjectId(selected), Optional.ofNullable(quote), Optional.ofNullable(quoteAfter));
        model.addAttribute("preparedQuotes", ownQuotes.entries()); model.addAttribute("quoteNext", ownQuotes.next());
        Map<UUID, Long> generations = new HashMap<>();
        for (var prepared : ownMessages.entries()) { if (!prepared.state().equals("PREPARED_UNSUBMITTED")) { generations.put(prepared.reference().revisionId(), moderation.generation(prepared.reference())); } }
        for (var prepared : ownQuotes.entries()) { if (!prepared.state().equals("PREPARED_UNSUBMITTED")) { generations.put(prepared.reference().revisionId(), moderation.generation(prepared.reference())); } }
        model.addAttribute("generations", generations); return "conversation-thread";
    }
    @GetMapping("/conversations/{id}/poll") @ResponseBody public MessageReader.PollPage poll(@PathVariable UUID id, @RequestParam UUID subject, @RequestParam(defaultValue = "0") long after) {
        return reader.poll(id, new SubjectId(subject), after);
    }
    @PostMapping("/conversations/messages") public String save(@RequestParam UUID command, @RequestParam UUID thread, @RequestParam UUID subject,
            @RequestParam(required = false) UUID resource, @RequestParam(defaultValue = "0") long version, @RequestParam(defaultValue = "PRIVATE_MESSAGE") String kind,
            @RequestParam String body, @RequestParam String label, @RequestParam(required = false) List<MultipartFile> uploads, HttpServletRequest request, RedirectAttributes redirect) throws IOException {
        source(request); var saved = messages.save(new MessageService.Draft(command, Optional.ofNullable(resource), version, Optional.of(thread), Optional.empty(), new SubjectId(subject), kind, body, label, uploads(uploads)));
        redirect.addFlashAttribute("notice", "Revisión guardada en privado. Falta enviarla a moderación; la contraparte recibe solamente una revisión aprobada."); return location(thread, subject) + "&message=" + saved.resourceId();
    }
    @PostMapping("/conversations/submit") public String submit(@RequestParam UUID thread, @RequestParam UUID subject, @RequestParam SurfaceKind kind,
            @RequestParam UUID resource, @RequestParam UUID revision, HttpServletRequest request, RedirectAttributes redirect) {
        source(request); requireOwnMessage(thread, subject, new ContentReference(kind, resource, revision)); messages.submit(new ContentReference(kind, resource, revision));
        redirect.addFlashAttribute("notice", "Revisión exacta en cola; no equivale a mensaje entregado."); return location(thread, subject);
    }
    @PostMapping("/conversations/retry") public String retry(@RequestParam UUID thread, @RequestParam UUID subject, @RequestParam UUID command,
            @RequestParam SurfaceKind kind, @RequestParam UUID resource, @RequestParam UUID revision, @RequestParam long generation, HttpServletRequest request) {
        source(request); requireOwnMessage(thread, subject, new ContentReference(kind, resource, revision)); moderation.retry(command, new ContentReference(kind, resource, revision), generation); return location(thread, subject);
    }
    @PostMapping("/conversations/cases") public String openCase(@RequestParam UUID command, @RequestParam SurfaceKind kind, @RequestParam UUID resource,
            @RequestParam UUID revision, @RequestParam String caseKind, @RequestParam String reason, @RequestParam(defaultValue = "") String note, HttpServletRequest request) {
        source(request); cases.open(command, new ContentReference(kind, resource, revision), caseKind, reason, note); return "redirect:/moderation/cases";
    }
    @PostMapping("/conversations/archive") public String archive(@RequestParam UUID thread, @RequestParam UUID subject, @RequestParam boolean archived, HttpServletRequest request) {
        source(request); service.archive(thread, new SubjectId(subject), archived); return "redirect:/conversations?subject=" + subject;
    }
    @PostMapping("/conversations/block") public String block(@RequestParam UUID thread, @RequestParam UUID subject, @RequestParam boolean blocked, HttpServletRequest request) {
        source(request); service.block(thread, new SubjectId(subject), blocked); return "redirect:/conversations?subject=" + subject;
    }
    @PostMapping("/conversations/leave") public String leave(@RequestParam UUID thread, @RequestParam UUID subject, HttpServletRequest request) {
        source(request); service.leave(thread, new SubjectId(subject)); return "redirect:/conversations?subject=" + subject;
    }
    @GetMapping("/conversations/files/{id}") public ResponseEntity<byte[]> file(@PathVariable UUID id, @RequestParam SurfaceKind kind, @RequestParam UUID resource,
            @RequestParam UUID revision, @RequestParam String digest) {
        var download = files.read(new FileReference(id, new ContentReference(kind, resource, revision), digest), FileAccess.Purpose.ORDINARY);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(download.mediaType())).header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=archivo-aprobado")
                .header("X-Content-Type-Options", "nosniff").header(HttpHeaders.CACHE_CONTROL, "no-store").body(download.bytes());
    }
    @PostMapping("/conversations/discussion") public String discuss(@RequestParam UUID command, @RequestParam UUID listing, @RequestParam UUID subject,
            @RequestParam String kind, @RequestParam String body, @RequestParam String label, @RequestParam(required = false) UUID resource, @RequestParam(defaultValue = "0") long version, HttpServletRequest request, RedirectAttributes redirect) {
        source(request); var reference = messages.save(new MessageService.Draft(command, Optional.ofNullable(resource), version, Optional.empty(), Optional.of(listing), new SubjectId(subject), kind, body, label, List.of()));
        messages.submit(reference); redirect.addFlashAttribute("notice", "Pregunta o comentario pendiente de revisión. Todavía no es público."); return "redirect:/conversations/authored/" + reference.resourceId() + "?subject=" + subject;
    }
    @GetMapping("/conversations/discussion/mine") public String ownDiscussion(@RequestParam(required = false) UUID subject, @RequestParam(required = false) UUID after,
            @AuthenticationPrincipal AccountPrincipal principal, Model model) {
        UUID side = subject == null ? principal.id() : subject;
        var page = reader.ownDiscussion(new SubjectId(side), Optional.ofNullable(after));
        model.addAttribute("drafts", page.entries()); model.addAttribute("next", page.next()); model.addAttribute("subject", side);
        model.addAttribute("command", UUID.randomUUID()); return "discussion-own";
    }
    @GetMapping("/conversations/authored/{id}") public String ownAuthored(@PathVariable UUID id, @RequestParam UUID subject, Model model) {
        var draft = reader.ownResource(id, new SubjectId(subject));
        model.addAttribute("drafts", List.of(draft)); model.addAttribute("next", Optional.empty()); model.addAttribute("subject", subject);
        model.addAttribute("command", UUID.randomUUID()); model.addAttribute("generation", draft.state().equals("PREPARED_UNSUBMITTED") ? 0 : moderation.generation(draft.reference()));
        return "discussion-own";
    }
    @PostMapping("/conversations/authored/submit") public String submitAuthored(@RequestParam UUID subject, @RequestParam SurfaceKind kind,
            @RequestParam UUID resource, @RequestParam UUID revision, HttpServletRequest request) {
        source(request); var draft = reader.ownResource(resource, new SubjectId(subject));
        if (!draft.reference().equals(new ContentReference(kind, resource, revision))) { throw ConversationFailure.unavailable(); }
        messages.submit(draft.reference()); return "redirect:/conversations/authored/" + resource + "?subject=" + subject;
    }
    @PostMapping("/conversations/authored/retry") public String retryAuthored(@RequestParam UUID command, @RequestParam UUID subject, @RequestParam SurfaceKind kind,
            @RequestParam UUID resource, @RequestParam UUID revision, @RequestParam long generation, HttpServletRequest request) {
        source(request); var draft = reader.ownResource(resource, new SubjectId(subject));
        if (!draft.reference().equals(new ContentReference(kind, resource, revision))) { throw ConversationFailure.unavailable(); }
        moderation.retry(command, draft.reference(), generation); return "redirect:/conversations/authored/" + resource + "?subject=" + subject;
    }
    private void requireOwnMessage(UUID thread, UUID subject, ContentReference reference) {
        var draft = reader.ownResource(reference.resourceId(), new SubjectId(subject));
        if (!draft.thread().filter(thread::equals).isPresent() || !draft.reference().equals(reference)) { throw ConversationFailure.unavailable(); }
    }
    public static List<Upload> uploads(List<MultipartFile> files) throws IOException {
        List<Upload> uploads = new ArrayList<>();
        if (files != null && files.stream().filter(file -> !file.isEmpty()).count() > 4) { throw new IllegalArgumentException("Adjuntá hasta cuatro archivos de 1 MiB cada uno; el formulario completo admite 5 MiB."); }
        if (files != null) { for (var file : files) { if (!file.isEmpty()) { if (file.getSize() > 1048576) { throw new IllegalArgumentException("Cada archivo admite hasta 1 MiB; podés adjuntar hasta cuatro."); } uploads.add(new Upload(Objects.requireNonNullElse(file.getContentType(), "application/octet-stream"), file.getBytes())); } } }
        return List.copyOf(uploads);
    }
    private void source(HttpServletRequest request) { admission.source(request.getRemoteAddr(), "conversations-mutation"); }
    private static String location(UUID thread, UUID subject) { return "redirect:/conversations/" + thread + "?subject=" + subject; }
}
