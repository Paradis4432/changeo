package ar.changeo.payments;

import ar.changeo.identity.api.SubjectId;
import ar.changeo.moderation.Admission;
import ar.changeo.payments.api.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@ConditionalOnProperty(name = "changeo.synthetic-payments", havingValue = "true")
public class PaymentController {
    private final PaymentQueryService queries;
    private final PaymentCommandService commands;
    private final Admission admission;

    PaymentController(PaymentQueryService queries, PaymentCommandService commands, Admission admission) {
        this.queries = queries; this.commands = commands; this.admission = admission;
    }
    @GetMapping("/payments/jobs/{job}")
    public String job(@PathVariable UUID job, @RequestParam UUID subject, Model model) {
        model.addAttribute("money", queries.job(job, new SubjectId(subject))); model.addAttribute("command", UUID.randomUUID()); return "payment-job";
    }
    @GetMapping("/payments/credits") public String credits(Model model) { model.addAttribute("balance", queries.balance()); return "payment-credits"; }
    @GetMapping("/payments/audit") public String audit(@RequestParam(required = false) UUID after, HttpServletRequest request, Model model) {
        var session = request.getSession(false);
        if (session == null || !(session.getAttribute("mfaEpoch") instanceof Long epoch)) { throw new PaymentFailure("Verificá MFA y contraseña reciente antes de consultar auditoría"); }
        var entries = queries.audit(epoch, Optional.ofNullable(after));
        model.addAttribute("entries", entries.stream().limit(50).toList());
        model.addAttribute("next", entries.size() > 50 ? entries.get(49).operationId() : null); return "payment-audit";
    }
    @PostMapping("/payments/fund") public String fund(@RequestParam UUID command, @RequestParam UUID job, @RequestParam UUID revision, @RequestParam UUID subject,
            @RequestParam long jobVersion, @RequestParam(defaultValue = "0") long creditMinor, HttpServletRequest request, RedirectAttributes redirect) {
        source(request); commands.fund(job, revision, new FundingSelection(command, jobVersion, creditMinor));
        redirect.addFlashAttribute("notice", "Intento registrado. La financiación se confirma después de conciliar el resultado, sin recargos al cliente."); return location(job, subject);
    }
    @PostMapping("/payments/release") public String release(@RequestParam UUID command, @RequestParam UUID job, @RequestParam UUID revision, @RequestParam UUID subject,
            @RequestParam long jobVersion, @RequestParam long moneyVersion, HttpServletRequest request, RedirectAttributes redirect) {
        source(request); commands.release(job, revision, new SubjectId(subject), new FinalReleaseRequest(command, jobVersion, moneyVersion));
        redirect.addFlashAttribute("notice", "Liberación final solicitada al proveedor original. El pago bancario es una operación posterior y distinta."); return location(job, subject);
    }
    @PostMapping("/payments/propose") public String propose(@RequestParam UUID command, @RequestParam UUID job, @RequestParam UUID revision, @RequestParam UUID subject,
            @RequestParam long jobVersion, @RequestParam long moneyVersion, @RequestParam long refundMinor, @RequestParam RefundDestination election,
            @RequestParam(defaultValue = "false") boolean continueJob, @RequestParam long remainingMinor, HttpServletRequest request, RedirectAttributes redirect) {
        source(request); commands.propose(new SettlementDraft(command, job, revision, new SubjectId(subject), jobVersion, moneyVersion, refundMinor, election, continueJob, remainingMinor));
        redirect.addFlashAttribute("notice", "Propuesta conservada sin mover fondos. Cada parte original debe aceptar exactamente el mismo reembolso, destino y continuación."); return location(job, subject);
    }
    @PostMapping("/payments/payout-retry") public String payoutRetry(@RequestParam UUID command, @RequestParam UUID job, @RequestParam UUID revision, @RequestParam UUID subject,
            @RequestParam long jobVersion, @RequestParam long moneyVersion, HttpServletRequest request, RedirectAttributes redirect) {
        source(request); commands.retryPayout(job, revision, new SubjectId(subject), new FinalReleaseRequest(command, jobVersion, moneyVersion));
        redirect.addFlashAttribute("notice", "Reintento bancario ligado al intento sin efecto, con el mismo proveedor y su derecho vigente. Un resultado incierto conserva su clave."); return location(job, subject);
    }
    @PostMapping("/payments/assent") public String assent(@RequestParam UUID command, @RequestParam UUID job, @RequestParam UUID revision, @RequestParam UUID subject,
            @RequestParam long jobVersion, @RequestParam long moneyVersion, @RequestParam UUID settlement, @RequestParam long refundMinor, @RequestParam RefundDestination election,
            @RequestParam boolean continueJob, @RequestParam long remainingMinor, HttpServletRequest request, RedirectAttributes redirect) {
        source(request); commands.assent(new SettlementAssent(settlement, new SettlementDraft(command, job, revision, new SubjectId(subject), jobVersion, moneyVersion, refundMinor, election, continueJob, remainingMinor)));
        redirect.addFlashAttribute("notice", "Aceptación exacta registrada. El reembolso requiere ambas partes y un resultado financiero confirmado."); return location(job, subject);
    }
    private void source(HttpServletRequest request) { admission.source(request.getRemoteAddr(), "payment-mutation"); }
    private static String location(UUID job, UUID subject) { return "redirect:/payments/jobs/" + job + "?subject=" + subject; }
}
