package ar.changeo.payments;

import ar.changeo.conversations.ConversationFailure;
import ar.changeo.identity.IdentityFailure;
import ar.changeo.moderation.ModerationFailure;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;

@ControllerAdvice(assignableTypes = PaymentController.class)
public class PaymentErrors {
    @ExceptionHandler({PaymentFailure.class, ConversationFailure.class, IdentityFailure.class, ModerationFailure.class, IllegalStateException.class})
    public ModelAndView denied(RuntimeException failure) { return error(failure.getMessage(), HttpStatus.FORBIDDEN); }
    @ExceptionHandler({IllegalArgumentException.class, org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class, org.springframework.web.bind.MissingServletRequestParameterException.class})
    public ModelAndView invalid(Exception failure) { return error("Revisá los campos y el estado financiero actual antes de volver a solicitar una acción.", HttpStatus.BAD_REQUEST); }
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ModelAndView unavailable() { return error("No pudimos confirmar la acción. Consultá el mismo intento antes de reintentar; un error no confirma la ausencia de efecto.", HttpStatus.SERVICE_UNAVAILABLE); }
    private static ModelAndView error(String message, HttpStatus status) { return new ModelAndView("conversation-error", Map.of("message", message), status); }
}
