package ar.changeo.conversations;

import ar.changeo.agreements.AgreementController;
import ar.changeo.identity.IdentityFailure;
import ar.changeo.jobs.JobController;
import ar.changeo.moderation.ModerationFailure;
import ar.changeo.notifications.NotificationController;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.ModelAndView;

@ControllerAdvice(assignableTypes = {ConversationController.class, AgreementController.class, JobController.class, NotificationController.class})
public class ConversationErrors {
    @ExceptionHandler({ConversationFailure.class, IdentityFailure.class, ModerationFailure.class, IllegalStateException.class})
    public ModelAndView denied(RuntimeException failure) { return error(failure.getMessage(), HttpStatus.FORBIDDEN); }
    @ExceptionHandler({IllegalArgumentException.class, org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class, org.springframework.web.bind.MissingServletRequestParameterException.class})
    public ModelAndView validation(Exception failure) { return error(failure instanceof IllegalArgumentException && failure.getMessage() != null && failure.getMessage().startsWith("Adjuntá") ? failure.getMessage() : "Revisá los campos y el estado actual. El borrador guardado se conserva.", HttpStatus.BAD_REQUEST); }
    @ExceptionHandler(MaxUploadSizeExceededException.class) public ModelAndView upload() { return ConversationUploadErrors.tooLarge(); }
    @ExceptionHandler(org.springframework.dao.DataAccessException.class) public ModelAndView unavailable() { return error("No pudimos completar la acción. Consultá el estado antes de reintentar; no hay una entrega ni una aceptación confirmada.", HttpStatus.SERVICE_UNAVAILABLE); }
    private static ModelAndView error(String message, HttpStatus status) { return new ModelAndView("conversation-error", Map.of("message", message), status); }
}
