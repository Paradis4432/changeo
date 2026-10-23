package ar.changeo.marketplace;

import ar.changeo.files.api.*;
import ar.changeo.identity.api.JobContext.FulfillmentMode;
import ar.changeo.identity.GuardianService;
import ar.changeo.identity.SecurityProof;
import ar.changeo.conversations.MessageReader;
import ar.changeo.jobs.api.ReputationCursor;
import ar.changeo.moderation.*;
import ar.changeo.moderation.api.*;
import ar.changeo.security.AccountPrincipal;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import ar.changeo.marketplace.MarketplaceForms.Description;
import ar.changeo.marketplace.MarketplaceForms.Coverage;
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
    private final MessageReader discussions;
    private final GuardianService guardians;
    private final boolean syntheticSubscriptions;
    private final SecurityProof proof;

    MarketplaceController(MarketplaceService service, MarketplaceReader reader, DiscoveryService discovery, ModerationService moderation,
                          CaseService cases, FileAccess files, Admission admission, MessageReader discussions, GuardianService guardians,
                          @Value("${changeo.synthetic-subscriptions:false}") boolean syntheticSubscriptions, SecurityProof proof) {
        this.service=service; this.reader=reader; this.discovery=discovery; this.moderation=moderation; this.cases=cases; this.files=files; this.admission=admission;
        this.discussions=discussions; this.guardians=guardians;
        this.syntheticSubscriptions=syntheticSubscriptions; this.proof=proof;
    }

    @GetMapping("/marketplace") public String search(@RequestParam(defaultValue="OFFER") PresenceType type,
            @RequestParam(defaultValue="") String q, @RequestParam(defaultValue="") String tag, @RequestParam(defaultValue="") String province,
            @RequestParam(defaultValue="") String area, @RequestParam(required=false) FulfillmentMode mode,
            @RequestParam(defaultValue="0") int page, Model model) {
        model.addAttribute("results",discovery.search(new DiscoveryService.Query(type,q,tag,province,area,Optional.ofNullable(mode),page)));
        model.addAttribute("provinces",MarketplaceDetails.PROVINCES);
        return "marketplace-search";
    }
    @GetMapping("/marketplace/origin") public String origin(@RequestParam SurfaceKind kind, @RequestParam UUID resource, @RequestParam UUID revision, Model model) {
        model.addAttribute("origin", reader.origin(new ContentReference(kind, resource, revision)));
        return "marketplace-origin";
    }

    @GetMapping("/marketplace/mine") public String mine(Model model) {
        model.addAttribute("resources",reader.ownResources()); model.addAttribute("syntheticSubscriptions",syntheticSubscriptions); return "marketplace-mine";
    }

    @GetMapping({"/marketplace/create","/profiles/create"}) public String create(@RequestParam PresenceType type,
            @RequestParam(required=false) UUID subject,@AuthenticationPrincipal AccountPrincipal principal,HttpServletRequest request,Model model) {
        var composition=new Composition(UUID.randomUUID(),principal.id(),type,subject==null ? principal.id() : subject,Optional.empty(),0,
                new Description("","","","GENERAL"),Coverage.initial(Optional.empty()),true);
        request.getSession().setAttribute(COMPOSITION,composition);
        return description(composition,model);
    }
    @GetMapping("/marketplace/manage/{id}/edit") public String edit(@PathVariable UUID id,HttpServletRequest request,Model model) {
        var own=reader.own(id);
        if (own.lifecycle()!=PublicationLifecycle.OPEN || own.current().details().isEmpty()) { throw new ModerationFailure("La edición requiere acceso al texto actual y una publicación abierta; consultá estado y preferencias"); }
        var details=own.current().details().orElseThrow();
        var composition=new Composition(UUID.randomUUID(),actor(),own.type(),own.subject(),Optional.of(id),own.version(),
                new Description(details.title(),details.body(),String.join(", ",details.tags()),own.current().label()),Coverage.initial(Optional.of(details)),true);
        request.getSession().setAttribute(COMPOSITION,composition);
        return description(composition,model);
    }

    @PostMapping("/marketplace/compose") public String compose(@RequestParam(required=false) UUID stage,
            @RequestParam PresenceType type,@RequestParam UUID subject,@RequestParam(required=false) UUID resource,
            @RequestParam(defaultValue="0") long version,@ModelAttribute("form") Description form,BindingResult errors,HttpServletRequest request,Model model) {
        source(request);
        Composition composition;
        if (stage!=null) {
            composition=current(stage,true,request);
        } else {
            composition=new Composition(UUID.randomUUID(),actor(),type,subject,Optional.ofNullable(resource),version,form,Coverage.initial(Optional.empty()),true);
            if (resource!=null) {
                var own=reader.own(resource);
                if (!own.subject().equals(subject) || own.type()!=type) { throw MarketplaceStore.unavailable(); }
                composition=new Composition(composition.stage(),composition.actor(),type,subject,Optional.of(resource),version,form,Coverage.initial(own.current().details()),true);
            }
            currentResource(composition);
        }
        composition=new Composition(composition.stage(),composition.actor(),composition.type(),composition.subject(),composition.resource(),composition.version(),form,composition.coverage(),true);
        form.validate(errors);
        if (errors.hasErrors()) {
            request.getSession().setAttribute(COMPOSITION,composition);
            return description(composition,model);
        }
        composition=new Composition(UUID.randomUUID(),composition.actor(),composition.type(),composition.subject(),composition.resource(),composition.version(),form,composition.coverage(),false);
        request.getSession().setAttribute(COMPOSITION,composition);
        model.asMap().remove(BindingResult.MODEL_KEY_PREFIX+"form");
        model.addAttribute("form",composition.coverage());
        return coverage(composition,model);
    }

    @PostMapping("/marketplace/back") public String back(@RequestParam UUID stage,@ModelAttribute("form") Coverage form,
            BindingResult errors,HttpServletRequest request,Model model) {
        source(request);
        var current=current(stage,false,request);
        var composition=new Composition(UUID.randomUUID(),current.actor(),current.type(),current.subject(),current.resource(),current.version(),current.description(),form,true);
        request.getSession().setAttribute(COMPOSITION,composition);
        model.asMap().remove(BindingResult.MODEL_KEY_PREFIX+"form");
        model.addAttribute("form",composition.description());
        model.addAttribute("reselectFiles",true);
        return description(composition,model);
    }

    @PostMapping("/marketplace/drafts") public String save(@RequestParam UUID stage,@ModelAttribute("form") Coverage form,BindingResult errors,
            @RequestParam(required=false) List<MultipartFile> uploads,HttpServletRequest request,Model model,RedirectAttributes redirect) throws IOException {
        source(request);
        var composition=current(stage,false,request);
        var details=form.details(composition.description(),composition.type(),errors);
        var selected=uploads==null ? List.<MultipartFile>of() : uploads.stream().filter(file -> !file.isEmpty()).toList();
        if (selected.size()>4 || selected.stream().anyMatch(file -> file.getSize()>1048576)) { errors.reject("uploads","Elegí hasta cuatro archivos nuevos de hasta 1 MiB cada uno"); }
        if (errors.hasErrors()) {
            model.addAttribute("reselectFiles",true);
            return coverage(composition,model);
        }
        var reference=service.save(new MarketplaceService.Draft(stage,composition.resource(),composition.version(),composition.type(),composition.subject(),details,composition.description().label(),uploads(selected)));
        request.getSession().removeAttribute(COMPOSITION);
        redirect.addFlashAttribute("notice","Borrador privado guardado. Revisá la vista previa y prepará una revisión nueva para publicar.");
        return "redirect:/marketplace/manage/"+reference.resourceId();
    }

    @GetMapping("/marketplace/recover") public String recover(@RequestParam UUID stage, HttpServletRequest request, Model model) {
        String[] stages=request.getParameterValues("stage");
        if (stages==null || stages.length!=1 || !stage.toString().equals(stages[0])) { throw new IllegalArgumentException("Referencia de recuperación inválida"); }
        var composition=current(stage,false,request);
        model.addAttribute("form",composition.coverage());
        model.addAttribute("uploadRecovery",true);
        model.addAttribute("reselectFiles",true);
        return coverage(composition,model);
    }

    private UUID actor() { return proof.actor().orElseThrow(MarketplaceStore::unavailable); }
    private Composition current(UUID stage, boolean description, HttpServletRequest request) {
        var session=request.getSession(false);
        Object found=session==null ? null : session.getAttribute(COMPOSITION);
        if (!(found instanceof Composition composition) || !composition.actor().equals(actor()) || !composition.stage().equals(stage) || composition.editingDescription()!=description) {
            throw new ModerationFailure("El formulario cambió o venció. Volvé a Mis publicaciones para consultar el estado o empezar de nuevo");
        }
        currentResource(composition);
        return composition;
    }
    private void currentResource(Composition composition) {
        composition.resource().ifPresent(id -> {
            var own=reader.own(id);
            if (!own.subject().equals(composition.subject()) || own.type()!=composition.type() || own.version()!=composition.version()
                    || own.lifecycle()!=PublicationLifecycle.OPEN || own.current().details().isEmpty()) {
                throw new ModerationFailure("La publicación cambió. Volvé a Mis publicaciones y abrí la edición actual antes de continuar");
            }
        });
    }
    private static String description(Composition composition, Model model) {
        model.addAttribute("composition",composition);
        if (!model.containsAttribute("form")) { model.addAttribute("form",composition.description()); }
        return "marketplace-compose";
    }
    private static String coverage(Composition composition, Model model) {
        model.addAttribute("composition",composition); model.addAttribute("provinces",MarketplaceDetails.PROVINCES);
        return "marketplace-coverage";
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
    @GetMapping("/listings/{id}") public String listing(@PathVariable UUID id,@AuthenticationPrincipal AccountPrincipal principal,Model model) {
        var view=reader.publicView(id);model.addAttribute("discussion",discussions.discussion(view.reference()));model.addAttribute("self",principal==null ? null : principal.id());
        model.addAttribute("guardianSubjects",principal==null ? List.of() : guardians.own(principal.id()));return detail(view,false,model);
    }
    @GetMapping("/profiles/{id}") public String profile(@PathVariable UUID id,@RequestParam(required=false) Long offset,@RequestParam(required=false) Long cohort,Model model) {
        if ((offset==null)!=(cohort==null)) { throw new IllegalArgumentException("Continuación incompleta"); }
        var composed=reader.composedProfile(id,offset==null ? Optional.empty() : Optional.of(new ReputationCursor(offset,cohort)));
        model.addAttribute("reputation",composed.reputation());model.addAttribute("achievement",composed.achievement());return detail(composed.profile(),true,model);
    }
    @GetMapping("/profiles/account/{subject}/{type}") public String subjectProfile(@PathVariable UUID subject,@PathVariable PresenceType type,Model model) {
        var found=reader.publicProfile(subject,type);var composed=reader.composedProfile(found.reference().resourceId(),Optional.empty());
        model.addAttribute("reputation",composed.reputation());model.addAttribute("achievement",composed.achievement());return detail(composed.profile(),true,model);
    }
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
    public record Composition(UUID stage, UUID actor, PresenceType type, UUID subject, Optional<UUID> resource, long version,
                              Description description, Coverage coverage, boolean editingDescription) {
        public String title() { return description.title(); }
    }
}
