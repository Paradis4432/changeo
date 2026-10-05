package ar.changeo.moderation.web;

import ar.changeo.files.api.*;
import ar.changeo.identity.AdminSecurityService;
import ar.changeo.moderation.*;
import ar.changeo.moderation.api.*;
import ar.changeo.security.AccountPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Clock;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class ModerationController {
    private final WorkbenchService workbench;
    private final ModerationService moderation;
    private final ContentReader reader;
    private final CaseService cases;
    private final FileAccess files;
    private final AdminSecurityService security;
    private final Clock clock;
    public ModerationController(WorkbenchService workbench,ModerationService moderation,ContentReader reader,CaseService cases,FileAccess files,AdminSecurityService security,Clock clock) {
        this.workbench=workbench;this.moderation=moderation;this.reader=reader;this.cases=cases;this.files=files;this.security=security;this.clock=clock;
    }
    @GetMapping("/moderation") public String workbench(Model model) {
        model.addAttribute("resources",workbench.own());model.addAttribute("command",UUID.randomUUID());return "moderation-workbench";
    }
    @PostMapping("/moderation/drafts") public String draft(@RequestParam UUID command,@RequestParam(required=false) UUID resource,@RequestParam(defaultValue="0") long version,
            @RequestParam String text,@RequestParam String label,@RequestParam String audience,@RequestParam(required=false) UUID subject,@RequestParam(required=false) UUID participant,
            @RequestParam(required=false) MultipartFile upload,RedirectAttributes redirect) throws IOException {
        var uploads=upload==null || upload.isEmpty()?List.<WorkbenchService.Upload>of():List.of(new WorkbenchService.Upload(Objects.requireNonNullElse(upload.getContentType(),"application/octet-stream"),upload.getBytes()));
        var ref=workbench.save(new WorkbenchService.Draft(command,Optional.ofNullable(resource),version,text,label,audience,Optional.ofNullable(subject),Optional.ofNullable(participant),uploads));
        redirect.addFlashAttribute("notice","Borrador guardado en privado. Enviá esta revisión a revisión antes de publicarla.");return "redirect:/moderation/own/"+ref.resourceId();
    }
    @GetMapping("/moderation/own/{id}") public String own(@PathVariable UUID id,Model model) {
        var content=reader.own(id);model.addAttribute("content",content);
        Map<UUID,Long> generations=new HashMap<>();for(var revision:content.revisions()) { generations.put(revision.reference().revisionId(),moderation.generation(revision.reference())); }
        model.addAttribute("generations",generations);model.addAttribute("editCommand",UUID.randomUUID());model.addAttribute("retryCommand",UUID.randomUUID());model.addAttribute("appealCommand",UUID.randomUUID());return "moderation-own";
    }
    @PostMapping("/moderation/submit") public String submit(@RequestParam UUID resource,@RequestParam UUID revision,RedirectAttributes redirect) {
        workbench.submit(workbench.reference(resource,revision));redirect.addFlashAttribute("notice","Revisión en cola. La publicación requiere aprobación exacta y permisos actuales.");return "redirect:/moderation/own/"+resource;
    }
    @PostMapping("/moderation/retry") public String retry(@RequestParam UUID command,@RequestParam UUID resource,@RequestParam UUID revision,@RequestParam long generation,RedirectAttributes redirect) {
        moderation.retry(command,workbench.reference(resource,revision),generation);redirect.addFlashAttribute("notice","Nuevo intento técnico registrado.");return "redirect:/moderation/own/"+resource;
    }
    @GetMapping("/moderation/cases") public String ownCases(Model model) { model.addAttribute("cases",cases.ownCases());return "moderation-cases"; }
    @PostMapping("/moderation/cases") public String open(@RequestParam UUID command,@RequestParam UUID resource,@RequestParam UUID revision,@RequestParam String kind,@RequestParam String reason,@RequestParam(defaultValue="") String note) {
        cases.open(command,workbench.reference(resource,revision),kind,reason,note);return "redirect:/moderation/cases";
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
