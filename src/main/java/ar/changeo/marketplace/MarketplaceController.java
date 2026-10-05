package ar.changeo.marketplace;

import ar.changeo.files.api.*;
import ar.changeo.identity.api.JobContext.FulfillmentMode;
import ar.changeo.moderation.*;
import ar.changeo.moderation.api.*;
import ar.changeo.security.AccountPrincipal;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class MarketplaceController {
    private static final String COMPOSITION="marketplaceComposition";
    private final MarketplaceService service;
    private final MarketplaceReader reader;
    private final DiscoveryService discovery;
    private final ModerationService moderation;
    private final CaseService cases;
    private final FileAccess files;
    private final Admission admission;

    MarketplaceController(MarketplaceService service, MarketplaceReader reader, DiscoveryService discovery, ModerationService moderation,
                          CaseService cases, FileAccess files, Admission admission) {
        this.service=service; this.reader=reader; this.discovery=discovery; this.moderation=moderation; this.cases=cases; this.files=files; this.admission=admission;
    }

    @GetMapping("/marketplace") public String search(@RequestParam(defaultValue="OFFER") PresenceType type,
            @RequestParam(defaultValue="") String q, @RequestParam(defaultValue="") String tag, @RequestParam(defaultValue="") String province,
            @RequestParam(defaultValue="") String area, @RequestParam(required=false) FulfillmentMode mode,
            @RequestParam(defaultValue="0") int page, Model model) {
        model.addAttribute("results",discovery.search(new DiscoveryService.Query(type,q,tag,province,area,Optional.ofNullable(mode),page)));
        model.addAttribute("provinces",MarketplaceDetails.PROVINCES);
        return "marketplace-search";
    }
    @GetMapping("/marketplace/mine") public String mine(Model model) { model.addAttribute("resources",reader.ownResources()); return "marketplace-mine"; }

    @GetMapping({"/marketplace/create","/profiles/create"}) public String create(@RequestParam PresenceType type,
            @RequestParam(required=false) UUID subject,@AuthenticationPrincipal AccountPrincipal principal,Model model) {
        model.addAttribute("type",type); model.addAttribute("subject",subject==null ? principal.id() : subject);
        model.addAttribute("resource",null); model.addAttribute("version",0); model.addAttribute("details",null);
        model.addAttribute("label","GENERAL"); return "marketplace-compose";
    }
    @GetMapping("/marketplace/manage/{id}/edit") public String edit(@PathVariable UUID id,Model model) {
        var own=reader.own(id);
        if (own.lifecycle()!=PublicationLifecycle.OPEN || own.current().details().isEmpty()) { throw new ModerationFailure("La edición requiere acceso al texto actual y una publicación abierta; consultá estado y preferencias"); }
        model.addAttribute("type",own.type()); model.addAttribute("subject",own.subject()); model.addAttribute("resource",id);
        model.addAttribute("version",own.version()); model.addAttribute("details",own.current().details().orElseThrow());
        model.addAttribute("label",own.current().details().isPresent() ? own.current().label() : "GENERAL"); return "marketplace-compose";
    }

    @PostMapping("/marketplace/compose") public String compose(@RequestParam PresenceType type,@RequestParam UUID subject,
            @RequestParam(required=false) UUID resource,@RequestParam(defaultValue="0") long version,@RequestParam String title,
            @RequestParam String body,@RequestParam(defaultValue="") String tags,@RequestParam String label,HttpServletRequest request,Model model) {
        source(request);
        if (title.isBlank() || title.length()>160 || body.isBlank() || body.length()>12000 || tags.length()>350 || !Set.of("GENERAL","SENSITIVE","ADULT").contains(label)) {
            throw new ModerationFailure("Revisá título, descripción, etiquetas y clasificación");
        }
        Optional<MarketplaceDetails> previous=Optional.empty();
        if (resource!=null) {
            var own=reader.own(resource);
            if (!own.subject().equals(subject) || own.type()!=type || own.version()!=version || own.lifecycle()!=PublicationLifecycle.OPEN) {
                throw new ModerationFailure("La publicación cambió; volvé a abrir la edición actual");
            }
            previous=own.current().details();
            if (previous.isEmpty()) { throw MarketplaceStore.unavailable(); }
        }
        var composition=new Composition(UUID.randomUUID(),type,subject,Optional.ofNullable(resource),version,title,body,tags,label,previous);
        request.getSession().setAttribute(COMPOSITION,composition);
        model.addAttribute("composition",composition); model.addAttribute("provinces",MarketplaceDetails.PROVINCES); return "marketplace-coverage";
    }

    @PostMapping("/marketplace/drafts") public String save(@RequestParam UUID stage,@RequestParam String province,@RequestParam String area,
            @RequestParam Set<FulfillmentMode> modes,@RequestParam(defaultValue="") String availability,@RequestParam(required=false) BigDecimal amount,
            @RequestParam(required=false) LocalDate deadline,@RequestParam(defaultValue="false") boolean badgeOptIn,
            @RequestParam(required=false) List<MultipartFile> uploads,HttpServletRequest request,RedirectAttributes redirect) throws IOException {
        source(request);
        Object found=request.getSession().getAttribute(COMPOSITION);
        if (!(found instanceof Composition composition) || !composition.stage().equals(stage)) { throw new ModerationFailure("El formulario cambió o venció. Empezá de nuevo"); }
        var tags=composition.tags().isBlank() ? List.<String>of() : Arrays.stream(composition.tags().split(",",-1)).toList();
        var details=new MarketplaceDetails(composition.title(),composition.body(),tags,province,area,modes,availability,Optional.ofNullable(amount),Optional.ofNullable(deadline),badgeOptIn);
        var reference=service.save(new MarketplaceService.Draft(stage,composition.resource(),composition.version(),composition.type(),composition.subject(),details,composition.label(),uploads(uploads)));
        request.getSession().removeAttribute(COMPOSITION);
        redirect.addFlashAttribute("notice","Borrador privado guardado. Revisá la vista previa y prepará una revisión nueva para publicar.");
        return "redirect:/marketplace/manage/"+reference.resourceId();
    }

    @GetMapping("/marketplace/manage/{id}") public String own(@PathVariable UUID id,Model model) {
        var own=reader.own(id); model.addAttribute("own",own);
        Map<UUID,Long> generations=new HashMap<>();
        for (var revision:own.revisions()) { generations.put(revision.reference().revisionId(),moderation.generation(revision.reference())); }
        model.addAttribute("generations",generations); model.addAttribute("command",UUID.randomUUID()); return "marketplace-own";
    }

    @PostMapping("/marketplace/prepare") public String prepare(@RequestParam UUID command,@RequestParam UUID resource,
            @RequestParam UUID revision,@RequestParam long version,@RequestParam(defaultValue="false") boolean omitPreviousMedia,
            @RequestParam(required=false) List<MultipartFile> uploads,HttpServletRequest request,RedirectAttributes redirect) throws IOException {
        source(request);
        var reference=service.prepare(new MarketplaceService.Preparation(command,service.reference(resource,revision),version,omitPreviousMedia,uploads(uploads)));
        redirect.addFlashAttribute("notice","Nueva revisión de publicación preparada en privado. Falta enviarla a revisión; el borrador original se conserva.");
        return "redirect:/marketplace/manage/"+reference.resourceId();
    }
    @PostMapping("/marketplace/submit") public String submit(@RequestParam UUID resource,@RequestParam UUID revision,HttpServletRequest request,RedirectAttributes redirect) {
        source(request); service.submit(service.reference(resource,revision));
        redirect.addFlashAttribute("notice","Revisión exacta en cola. El contenido realista espera revisión humana en este entorno sintético."); return "redirect:/marketplace/manage/"+resource;
    }
    @PostMapping("/marketplace/state") public String state(@RequestParam UUID command,@RequestParam UUID resource,@RequestParam long version,
            @RequestParam String action,HttpServletRequest request,RedirectAttributes redirect) {
        source(request); service.transition(command,resource,version,action); redirect.addFlashAttribute("notice","Estado actualizado. El historial se conserva."); return "redirect:/marketplace/manage/"+resource;
    }
    @PostMapping("/marketplace/retry") public String retry(@RequestParam UUID command,@RequestParam UUID resource,@RequestParam UUID revision,
            @RequestParam long generation,HttpServletRequest request,RedirectAttributes redirect) {
        source(request); moderation.retry(command,service.reference(resource,revision),generation); redirect.addFlashAttribute("notice","Nuevo intento técnico registrado."); return "redirect:/marketplace/manage/"+resource;
    }
    @PostMapping("/marketplace/cases") public String open(@RequestParam UUID command,@RequestParam UUID resource,@RequestParam UUID revision,
            @RequestParam String kind,@RequestParam String reason,@RequestParam(defaultValue="") String note,HttpServletRequest request) {
        source(request); cases.open(command,service.reference(resource,revision),kind,reason,note); return "redirect:/moderation/cases";
    }
    @GetMapping("/listings/{id}") public String listing(@PathVariable UUID id,Model model) { return detail(reader.publicView(id),false,model); }
    @GetMapping("/profiles/{id}") public String profile(@PathVariable UUID id,Model model) { return detail(reader.publicView(id),true,model); }
    @GetMapping("/profiles/account/{subject}/{type}") public String subjectProfile(@PathVariable UUID subject,@PathVariable PresenceType type,Model model) { return detail(reader.publicProfile(subject,type),true,model); }
    @GetMapping({"/listings/{id}/preview","/listings/{id}/share","/profiles/{id}/preview","/profiles/{id}/share"}) @ResponseBody
    public MarketplaceReader.PublicView projection(@PathVariable UUID id) { return reader.publicView(id); }
    @GetMapping("/marketplace/files/{id}") public ResponseEntity<byte[]> file(@PathVariable UUID id,@RequestParam UUID resource,@RequestParam UUID revision,@RequestParam String digest) {
        var download=files.read(new FileReference(id,service.reference(resource,revision),digest),FileAccess.Purpose.ORDINARY);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(download.mediaType())).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"portafolio-aprobado\"")
                .header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff").body(download.bytes());
    }
    private String detail(MarketplaceReader.PublicView view, boolean profile, Model model) {
        if (view.type().profile()!=profile) { throw MarketplaceStore.unavailable(); }
        model.addAttribute("view",view); model.addAttribute("command",UUID.randomUUID()); return profile ? "profile-detail" : "listing-detail";
    }
    private void source(HttpServletRequest request) { admission.source(request.getRemoteAddr(),"marketplace-mutation"); }
    private static List<MarketplaceService.Upload> uploads(List<MultipartFile> files) throws IOException {
        List<MarketplaceService.Upload> uploads=new ArrayList<>();
        if (files!=null) { for (var file:files) { if (!file.isEmpty()) { uploads.add(new MarketplaceService.Upload(Objects.requireNonNullElse(file.getContentType(),"application/octet-stream"),file.getBytes())); } } }
        return List.copyOf(uploads);
    }
    public record Composition(UUID stage, PresenceType type, UUID subject, Optional<UUID> resource, long version, String title, String body, String tags, String label, Optional<MarketplaceDetails> previous) {}
}
