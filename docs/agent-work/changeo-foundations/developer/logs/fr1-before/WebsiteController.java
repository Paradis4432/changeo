package ar.changeo.web;

import ar.changeo.identity.*;
import ar.changeo.identity.api.*;
import ar.changeo.security.AccountPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class WebsiteController {
    private final AccountService accounts;
    private final IdentityAccess access;
    private final GuardianService guardians;
    private final AdminService admin;
    private final AdminSecurityService adminSecurity;
    private final AbuseLimits limits;
    private final Clock clock;

    WebsiteController(AccountService accounts, IdentityAccess access, GuardianService guardians,
                      AdminService admin, AdminSecurityService adminSecurity, AbuseLimits limits, Clock clock) {
        this.accounts = accounts; this.access = access; this.guardians = guardians; this.admin = admin;
        this.adminSecurity = adminSecurity; this.limits = limits; this.clock = clock;
    }

    @GetMapping("/") String home() { return "home"; }
    @GetMapping("/register") String register() { return "register"; }
    @GetMapping("/login") String login() { return "login"; }
    @GetMapping("/password/forgot") String forgot() { return "forgot"; }
    @GetMapping("/password/reset") String reset() { return "reset"; }

    @PostMapping("/register")
    String register(@RequestParam String contact, @RequestParam String password, HttpServletRequest request, RedirectAttributes redirect) {
        limit("register:" + request.getRemoteAddr(), 5);
        AccountService.Registration registration = accounts.register(contact, password);
        redirect.addFlashAttribute("notice", registration.delivered()
                ? "Cuenta creada. Ingresá y verificá el contacto con el código del buzón local privado."
                : "Cuenta creada, entrega pendiente. Ingresá para reintentar la entrega.");
        return "redirect:/login";
    }

    @PostMapping("/password/forgot")
    String forgot(@RequestParam String contact, HttpServletRequest request, RedirectAttributes redirect) {
        limit("recovery-source:" + request.getRemoteAddr(), 5);
        limit("recovery-contact:" + contact, 5);
        accounts.forgot(contact);
        redirect.addFlashAttribute("notice", "Si existe una cuenta de prueba, se intentó entregar un código al buzón local privado.");
        return "redirect:/password/forgot";
    }

    @PostMapping("/password/reset")
    String reset(@RequestParam String token, @RequestParam String password, HttpServletRequest request, RedirectAttributes redirect) {
        limit("reset:" + request.getRemoteAddr(), 5);
        accounts.reset(token, password);
        if (request.getSession(false) != null) { request.getSession(false).invalidate(); }
        redirect.addFlashAttribute("notice", "Contraseña actualizada. Todas las sesiones anteriores dejaron de ser válidas; MFA se conserva.");
        return "redirect:/login";
    }

    @GetMapping({"/account", "/account/contact", "/account/preferences"})
    String account(@AuthenticationPrincipal AccountPrincipal principal, Model model) {
        model.addAttribute("status", access.snapshot(new AccountId(principal.id())));
        model.addAttribute("id", principal.id());
        return "account";
    }

    @PostMapping("/account/contact/verify")
    String verify(@AuthenticationPrincipal AccountPrincipal principal, @RequestParam String token, RedirectAttributes redirect) {
        limit("verify:" + principal.id(), 5); accounts.verifyContact(principal.id(), token);
        redirect.addFlashAttribute("notice", "Contacto sintético verificado. La elegibilidad de producción sigue pendiente.");
        return "redirect:/account";
    }

    @PostMapping("/account/contact/resend")
    String resend(@AuthenticationPrincipal AccountPrincipal principal, RedirectAttributes redirect) {
        limit("resend:" + principal.id(), 5);
        redirect.addFlashAttribute("notice", accounts.resend(principal.id()) ? "Nuevo código entregado en privado; los anteriores vencieron." : "Entrega pendiente. Reintentar luego de revisar el buzón local.");
        return "redirect:/account";
    }

    @PostMapping("/account/preferences")
    String preferences(@AuthenticationPrincipal AccountPrincipal principal,
                       @RequestParam(defaultValue = "false") boolean sensitive,
                       @RequestParam(defaultValue = "false") boolean adult, RedirectAttributes redirect) {
        accounts.preferences(principal.id(), sensitive, adult);
        redirect.addFlashAttribute("notice", "Preferencias guardadas."); return "redirect:/account";
    }

    @GetMapping("/account/guardian")
    String guardian(@AuthenticationPrincipal AccountPrincipal principal, Model model) {
        model.addAttribute("links", guardians.own(principal.id()));
        model.addAttribute("consents", guardians.consents(principal.id()));
        model.addAttribute("id", principal.id()); return "guardian";
    }

    @PostMapping("/account/guardian/request")
    String requestGuardian(@AuthenticationPrincipal AccountPrincipal principal, @RequestParam UUID other,
                          @RequestParam boolean actingAsGuardian, RedirectAttributes redirect) {
        UUID guardian = actingAsGuardian ? principal.id() : other;
        UUID minor = actingAsGuardian ? other : principal.id();
        guardians.request(principal.id(), guardian, minor);
        redirect.addFlashAttribute("notice", "Relación pendiente. Requiere revisión independiente de evidencias sintéticas.");
        return "redirect:/account/guardian";
    }

    @PostMapping("/account/guardian/{link}/revoke")
    String revoke(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable UUID link, @RequestParam long version) {
        guardians.revoke(principal.id(), link, version); return "redirect:/account/guardian";
    }

    @PostMapping("/account/guardian/{link}/consent")
    String consent(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable UUID link, @RequestParam UUID syntheticJob) {
        guardians.grant(principal.id(), link, syntheticJob); return "redirect:/account/guardian";
    }

    @PostMapping("/account/guardian/{link}/consent/{consent}/revoke")
    String revokeConsent(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable UUID link, @PathVariable UUID consent) {
        guardians.revokeConsent(principal.id(), link, consent); return "redirect:/account/guardian";
    }

    @GetMapping({"/support", "/rights"})
    String support(@AuthenticationPrincipal AccountPrincipal principal, HttpServletRequest request, Model model) {
        Capability capability = request.getServletPath().equals("/rights") ? Capability.ACCESS_FINANCIAL_RIGHTS : Capability.ACCESS_SUPPORT;
        PermissionDecision decision = access.decide(PermissionRequest.self(new AccountId(principal.id()), capability));
        if (!decision.allowed()) { throw new IdentityFailure("Acceso propio requerido"); }
        model.addAttribute("rights", capability == Capability.ACCESS_FINANCIAL_RIGHTS); return "support";
    }

    @GetMapping("/admin/mfa") String mfa() { return "mfa"; }

    @PostMapping("/admin/mfa")
    String mfa(@AuthenticationPrincipal AccountPrincipal principal, @RequestParam String code, HttpServletRequest request) {
        long epoch = adminSecurity.verify(principal.id(), code, request.getRemoteAddr());
        request.changeSessionId();
        request.getSession().setAttribute("mfaEpoch", epoch);
        request.getSession().setAttribute("mfaUntil", clock.instant().plusSeconds(600));
        return "redirect:/admin/reauth";
    }

    @GetMapping("/admin/reauth") String reauth() { return "reauth"; }

    @PostMapping("/admin/reauth")
    String reauth(@AuthenticationPrincipal AccountPrincipal principal, @RequestParam String password, HttpServletRequest request) {
        long epoch = adminSecurity.reauthenticate(principal.id(), password, request.getRemoteAddr());
        request.changeSessionId();
        request.getSession().setAttribute("passwordEpoch", epoch);
        request.getSession().setAttribute("passwordUntil", clock.instant().plusSeconds(600));
        return "redirect:/admin/accounts";
    }

    @GetMapping("/admin/accounts")
    String admins(@AuthenticationPrincipal AccountPrincipal principal, Model model) {
        model.addAttribute("accounts", admin.accounts(principal.id()));
        boolean identity = access.decide(PermissionRequest.self(new AccountId(principal.id()), Capability.ADMIN_IDENTITY)).allowed();
        model.addAttribute("identityAdmin", identity);
        model.addAttribute("restrictionAdmin", access.decide(PermissionRequest.self(new AccountId(principal.id()), Capability.ADMIN_RESTRICTIONS)).allowed());
        model.addAttribute("links", identity ? admin.links(principal.id()) : java.util.List.of()); return "admin";
    }

    @PostMapping("/admin/accounts/{target}/eligibility")
    String evidence(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable UUID target, @RequestParam String kind,
                    @RequestParam(required = false) Integer age) {
        admin.evidence(principal.id(), target, kind, age); return "redirect:/admin/accounts";
    }

    @PostMapping("/admin/accounts/{target}/restriction")
    String restrict(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable UUID target, @RequestParam boolean active) {
        admin.restriction(principal.id(), target, active); return "redirect:/admin/accounts";
    }

    @PostMapping("/admin/accounts/{target}/role/remove")
    String removeRole(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable UUID target, @RequestParam String role) {
        admin.removeRole(principal.id(), target, role); return "redirect:/admin/accounts";
    }

    @PostMapping("/admin/guardian/{link}/review")
    String review(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable UUID link, @RequestParam long version, @RequestParam boolean verified) {
        admin.reviewGuardian(principal.id(), link, version, verified); return "redirect:/admin/accounts";
    }

    @GetMapping("/admin/audit") String audit(@AuthenticationPrincipal AccountPrincipal principal, Model model) {
        model.addAttribute("events", admin.audit(principal.id())); return "audit";
    }

    private void limit(String key, int maximum) {
        if (!limits.allow(key, maximum)) { throw new IdentityFailure("Demasiados intentos; reintentar en un minuto"); }
    }
}
