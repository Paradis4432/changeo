package ar.changeo.web;

import ar.changeo.identity.IdentityFailure;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

@ControllerAdvice
class WebsiteErrors {
    @ExceptionHandler(IdentityFailure.class)
    ModelAndView identity(IdentityFailure failure) {
        return new ModelAndView("failure", java.util.Map.of("message", failure.getMessage()), HttpStatus.FORBIDDEN);
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ModelAndView conflict() {
        return new ModelAndView("failure", java.util.Map.of("message", "El estado cambió o ya existe. Revisá la cuenta y reintentá."), HttpStatus.CONFLICT);
    }
}
