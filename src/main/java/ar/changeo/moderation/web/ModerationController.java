package ar.changeo.moderation.web;

import ar.changeo.files.api.*;
import ar.changeo.identity.AdminSecurityService;
import ar.changeo.moderation.*;
import ar.changeo.moderation.api.*;
import ar.changeo.security.AccountPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class ModerationController {
    private final WorkbenchService workbench;
    private final ModerationService moderation;
    private final ContentReader reader;
    private final CaseService cases;
    private final OwnerRegistry owners;
    private final FileAccess files;
    private final AdminSecurityService security;
    private final Clock clock;
    public ModerationController(WorkbenchService workbench,ModerationService moderation,ContentReader reader,CaseService cases,FileAccess files,AdminSecurityService security,Clock clock,OwnerRegistry owners) {
        this.workbench=workbench;this.moderation=moderation;this.reader=reader;this.cases=cases;this.files=files;this.security=security;this.clock=clock;this.owners=owners;
    }
    @GetMapping("/moderation") public String workbench(Model model) {
        model.addAttribute("resources",workbench.own());model.addAttribute("form",ModerationForms.Draft.initial(UUID.randomUUID()));return "moderation-workbench";
    }
    @PostMapping("/moderation/drafts") public String draft(@ModelAttribute("form") ModerationForms.Draft form,@RequestParam org.springframework.util.MultiValueMap<String,String> parameters,
            @RequestParam(required=false) MultipartFile upload,Model model,HttpServletResponse response,RedirectAttributes redirect) throws IOException {
        form=form.withTypedParameters(parameters);
        ModerationForms.Context context;
        try { context=form.context(); }
        catch(IllegalArgumentException failure) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);model.addAttribute("message","No pudimos establecer el contexto del borrador. Consultá el estado actual antes de volver a editar.");return "moderation-draft-error";
        }
        var errors=new BeanPropertyBindingResult(form,"form");form.validate(errors);
        if(errors.hasErrors()) {
            workbench.requireDraftAccess(context.resource(),context.version(),context.audience(),context.subject());
            return draftErrors(form,errors,model,response,HttpServletResponse.SC_BAD_REQUEST);
        }
        ContentReference ref;
        try {
            var uploads=upload==null || upload.isEmpty()?List.<WorkbenchService.Upload>of():List.of(new WorkbenchService.Upload(Objects.requireNonNullElse(upload.getContentType(),"application/octet-stream"),upload.getBytes()));
            ref=workbench.save(new WorkbenchService.Draft(context.command(),context.resource(),context.version(),form.text(),form.label(),form.audience(),form.subjectId(),form.participantId(),uploads));
        } catch(ModerationFailure failure) {
            workbench.requireDraftAccess(context.resource(),context.version(),context.audience(),context.subject());
            errors.reject("save",failure.getMessage());return draftErrors(form,errors,model,response,HttpServletResponse.SC_FORBIDDEN);
        }
        redirect.addFlashAttribute("notice","Borrador guardado en privado. Enviá esta revisión a revisión antes de publicarla.");return "redirect:/moderation/own/"+ref.resourceId();
    }
    private String draftErrors(ModerationForms.Draft form,BindingResult errors,Model model,HttpServletResponse response,int status) {
        var bounded=form.bounded();var displayed=new BeanPropertyBindingResult(bounded,"form");
        for(var error:errors.getFieldErrors()) { displayed.rejectValue(error.getField(),"invalid",error.getDefaultMessage()); }
        for(var error:errors.getGlobalErrors()) { displayed.reject("save",error.getDefaultMessage()); }
        model.addAttribute("form",bounded);model.addAttribute(BindingResult.MODEL_KEY_PREFIX+"form",displayed);
        model.addAttribute("draftRecovery",true);response.setStatus(status);return "moderation-draft-form";
    }
    @GetMapping("/moderation/own/{id}") public String own(@PathVariable UUID id,Model model) {
        var content=reader.own(id);model.addAttribute("content",content);
        Map<UUID,Long> generations=new HashMap<>();for(var revision:content.revisions()) { generations.put(revision.reference().revisionId(),moderation.generation(revision.reference())); }
        model.addAttribute("generations",generations);model.addAttribute("form",new ModerationForms.Draft(UUID.randomUUID().toString(),id.toString(),Long.toString(content.version()),"","GENERAL",content.audience(),"",""));model.addAttribute("retryCommand",UUID.randomUUID());model.addAttribute("appealCommand",UUID.randomUUID());return "moderation-own";
    }
    @PostMapping("/moderation/submit") public String submit(@RequestParam UUID resource,@RequestParam UUID revision,RedirectAttributes redirect) {
        workbench.submit(workbench.reference(resource,revision));redirect.addFlashAttribute("notice","Revisión en cola. La publicación requiere aprobación exacta y permisos actuales.");return "redirect:/moderation/own/"+resource;
    }
    @PostMapping("/moderation/retry") public String retry(@RequestParam UUID command,@RequestParam UUID resource,@RequestParam UUID revision,@RequestParam long generation,RedirectAttributes redirect) {
        moderation.retry(command,workbench.reference(resource,revision),generation);redirect.addFlashAttribute("notice","Nuevo intento técnico registrado.");return "redirect:/moderation/own/"+resource;
    }
    @GetMapping("/moderation/cases") public String ownCases(Model model) {
        model.addAttribute("cases", cases.ownCases().stream().map(item -> {
            var reference = new ContentReference(SurfaceKind.valueOf((String) item.get("content_kind")), (UUID) item.get("resource_id"), (UUID) item.get("revision_id"));
            var row = new LinkedHashMap<String,Object>(item);
            row.put("origin", owners.require(reference).caseOrigin(reference));
            return Map.copyOf(row);
        }).toList());
        return "moderation-cases";
    }
    @GetMapping("/moderation/origin") public String origin(@RequestParam SurfaceKind kind, @RequestParam UUID resource, @RequestParam UUID revision, Model model) {
        model.addAttribute("origin", reader.origin(new ContentReference(kind, resource, revision)));
        return "moderation-origin";
    }
    @PostMapping("/moderation/cases") public String open(@RequestParam UUID command,@RequestParam UUID resource,@RequestParam UUID revision,@RequestParam String kind,@RequestParam(defaultValue="") String reason,@RequestParam(defaultValue="") String note,
            Model model,HttpServletResponse response) {
        if(!Set.of("REPORT","APPEAL").contains(kind)) { throw new ModerationFailure("Motivo o texto de caso inválido"); }
        var form=new ModerationForms.Case(reason,note);
        var errors=new BeanPropertyBindingResult(form,"form");form.validate(errors);
        var reference=workbench.reference(resource,revision);
        if(errors.hasErrors()) {
            if(kind.equals("REPORT")) {
                if(!reader.retrieve(resource).reference().equals(reference)) { throw new ModerationFailure("La revisión visible cambió; consultá el contenido actual"); }
            } else {
                var own=reader.own(resource).revisions();
                if(own.stream().noneMatch(item->item.reference().equals(reference) && item.text()!=null && !item.state().equals("DRAFT_PENDING_REVIEW"))) {
                    throw new ModerationFailure("La revisión propia no está disponible para apelar");
                }
            }
            var bounded=form.bounded();var displayedErrors=new BeanPropertyBindingResult(bounded,"form");
            for(var error:errors.getFieldErrors()) { displayedErrors.rejectValue(error.getField(),"invalid",error.getDefaultMessage()); }
            model.addAttribute("form",bounded);model.addAttribute(BindingResult.MODEL_KEY_PREFIX+"form",displayedErrors);
            model.addAttribute("command",command);model.addAttribute("resource",resource);model.addAttribute("revision",revision);model.addAttribute("kind",kind);
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);return "moderation-case-form";
        }
        cases.open(command,reference,kind,reason,note);return "redirect:/moderation/cases";
    }
    @GetMapping("/moderation/operator/signin") public String signin() { return "moderation-operator-signin"; }
    @PostMapping("/moderation/operator/mfa") public String mfa(@AuthenticationPrincipal AccountPrincipal principal,@RequestParam String code,HttpServletRequest request) {
        long epoch=security.verify(principal.id(),code,request.getRemoteAddr());request.changeSessionId();request.getSession().setAttribute("mfaEpoch",epoch);request.getSession().setAttribute("mfaUntil",clock.instant().plusSeconds(600));return "redirect:/moderation/operator/signin";
    }
    @PostMapping("/moderation/operator/reauth") public String reauth(@AuthenticationPrincipal AccountPrincipal principal,@RequestParam String password,HttpServletRequest request) {
        long epoch=security.reauthenticate(principal.id(),password,request.getRemoteAddr());request.changeSessionId();request.getSession().setAttribute("passwordEpoch",epoch);request.getSession().setAttribute("passwordUntil",clock.instant().plusSeconds(600));return "redirect:/moderation/operator";
    }
    @GetMapping("/moderation/operator") public String operator(Model model) { return operatorModel(model,false); }
    @GetMapping("/moderation/audit") public String audit(Model model) { return operatorModel(model,true); }
    private String operatorModel(Model model,boolean audit) {
        model.addAttribute("audit",audit);model.addAttribute("cases",cases.queue(audit));model.addAttribute("evidence",cases.evidenceMetadata(audit));model.addAttribute("submissions",audit?List.of():cases.reviewQueue());model.addAttribute("newCommand",UUID.randomUUID());return "moderation-operator";
    }
    @GetMapping("/moderation/evidence") public String evidence(@RequestParam UUID resource,@RequestParam UUID revision,@RequestParam SurfaceKind kind,Model model) {
        var content=reader.evidence(new ContentReference(kind,resource,revision));model.addAttribute("content",content);model.addAttribute("files",content.attachments().stream().map(files::metadata).toList());return "moderation-evidence";
    }
    @PostMapping("/moderation/operator/case") public String review(@RequestParam UUID command,@RequestParam UUID resource,@RequestParam UUID revision,@RequestParam SurfaceKind kind) {
        cases.reviewCase(command,new ContentReference(kind,resource,revision));return "redirect:/moderation/operator";
    }
    @PostMapping("/moderation/operator/action") public String act(@RequestParam UUID command,@RequestParam UUID caseId,@RequestParam long version,@RequestParam String action,@RequestParam String label,@RequestParam String reason) {
        cases.act(command,caseId,version,action,label,reason);return "redirect:/moderation/operator";
    }
    @PostMapping("/moderation/operator/withdraw") public String withdraw(@RequestParam UUID command,@RequestParam UUID evidenceId,@RequestParam long version) { cases.withdraw(command,evidenceId,version);return "redirect:/moderation/operator"; }
    @GetMapping("/moderation/files/{id}") public ResponseEntity<byte[]> evidenceFile(@PathVariable UUID id,@RequestParam UUID resource,@RequestParam UUID revision,@RequestParam SurfaceKind kind,@RequestParam String digest) {
        return download(files.read(new FileReference(id,new ContentReference(kind,resource,revision),digest),FileAccess.Purpose.REVIEW));
    }
    static ResponseEntity<byte[]> download(FileAccess.Download download) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(download.mediaType())).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"contenido-seguro\"").header("X-Content-Type-Options","nosniff").header(HttpHeaders.CACHE_CONTROL,"no-store").body(download.bytes());
    }
}
