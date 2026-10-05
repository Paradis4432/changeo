package ar.changeo.marketplace;

import ar.changeo.moderation.ModerationFailure;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.ModelAndView;

@ControllerAdvice(assignableTypes=MarketplaceController.class)
public class MarketplaceErrors {
    @ExceptionHandler(ModerationFailure.class) public ModelAndView denied(ModerationFailure failure) { return error(failure.getMessage(),HttpStatus.FORBIDDEN); }
    @ExceptionHandler({IllegalArgumentException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,org.springframework.web.bind.MissingServletRequestParameterException.class})
    public ModelAndView validation() { return error("Revisá los campos y la referencia; conservá tu borrador y volvé a probar.",HttpStatus.BAD_REQUEST); }
    @ExceptionHandler(MaxUploadSizeExceededException.class) public ModelAndView upload() { return error("Archivo demasiado grande. El borrador guardado se conserva; probá un archivo de hasta 1 MiB.",HttpStatus.PAYLOAD_TOO_LARGE); }
    @ExceptionHandler(org.springframework.dao.DataAccessException.class) public ModelAndView unavailable() { return error("No pudimos completar la consulta o guardar el cambio. Revisá el estado antes de reintentar; no hay publicación confirmada.",HttpStatus.SERVICE_UNAVAILABLE); }
    private static ModelAndView error(String message, HttpStatus status) { return new ModelAndView("marketplace-error",Map.of("message",message),status); }
}
