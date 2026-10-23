package ar.changeo.web;

import ar.changeo.identity.IdentityFailure;
import ar.changeo.identity.GuardianService;
import ar.changeo.identity.api.AccountId;
import ar.changeo.identity.api.IdentityAccess;
import ar.changeo.security.AccountPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.ModelAndView;

@ControllerAdvice
class WebsiteErrors {
    private final IdentityAccess access;
    private final GuardianService guardians;

    WebsiteErrors(IdentityAccess access, GuardianService guardians) {
        this.access = access;
        this.guardians = guardians;
    }

    @ExceptionHandler(IdentityFailure.class)
    ModelAndView identity(IdentityFailure failure, HttpServletRequest request) {
        return recover(request, failure.getMessage(), HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ModelAndView conflict(HttpServletRequest request) {
        return recover(request, "El estado cambió o ya existe. Revisá la cuenta y reintentá.", HttpStatus.CONFLICT);
    }

    ModelAndView recover(HttpServletRequest request, String message, HttpStatus status) {
        String form = form(request);
        ModelAndView result = new ModelAndView(form.isEmpty() ? "failure" : form, Map.of("message", message), status);
        switch (form) {
            case "register", "forgot" -> result.addObject("contact", bounded(request, "contact", 254));
            case "account" -> {
                AccountPrincipal principal = principal();
                result.addObject("status", access.snapshot(new AccountId(principal.id())));
                result.addObject("id", principal.id());
                if (path(request).equals("/account/preferences")) {
                    result.addObject("preferencesFailed", true);
                    result.addObject("sensitive", selected(request, "sensitive"));
                    result.addObject("adult", selected(request, "adult"));
                }
            }
            case "guardian" -> {
                AccountPrincipal principal = principal();
                result.addObject("links", guardians.own(principal.id()));
                result.addObject("consents", guardians.consents(principal.id()));
                result.addObject("id", principal.id());
                result.addObject("other", bounded(request, "other", 128));
                result.addObject("actingAsGuardian", selected(request, "actingAsGuardian"));
            }
            default -> { }
        }
        return result;
    }

    private static String form(HttpServletRequest request) {
        if (!request.getMethod().equals("POST")) { return ""; }
        return switch (path(request)) {
            case "/register" -> "register";
            case "/password/forgot" -> "forgot";
            case "/password/reset" -> "reset";
            case "/account/contact/verify", "/account/contact/resend", "/account/preferences" -> "account";
            case "/account/guardian/request" -> "guardian";
            default -> "";
        };
    }

    private static String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    private static AccountPrincipal principal() {
        return (AccountPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    private static String bounded(HttpServletRequest request, String name, int maximum) {
        String value = request.getParameter(name);
        return value == null ? "" : value.substring(0, Math.min(value.length(), maximum));
    }

    private static boolean selected(HttpServletRequest request, String name) {
        String value = request.getParameter(name);
        return value != null && (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("on") || value.equalsIgnoreCase("yes") || value.equals("1"));
    }

    @ControllerAdvice(assignableTypes = WebsiteController.class)
    static class InvalidAccountFormAdvice {
        private final WebsiteErrors errors;

        InvalidAccountFormAdvice(WebsiteErrors errors) { this.errors = errors; }

        @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
        ModelAndView invalid(Exception failure, HttpServletRequest request) throws Exception {
            if (form(request).isEmpty()) { throw failure; }
            return errors.recover(request, "Revisá los campos requeridos y sus valores antes de reintentar.", HttpStatus.BAD_REQUEST);
        }
    }
}
