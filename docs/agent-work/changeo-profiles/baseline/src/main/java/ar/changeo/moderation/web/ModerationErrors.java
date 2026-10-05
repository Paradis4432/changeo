package ar.changeo.moderation.web;

import ar.changeo.moderation.ModerationFailure;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.ModelAndView;

@ControllerAdvice(assignableTypes={ModerationController.class,ContentController.class})
public class ModerationErrors {
    @ExceptionHandler(ModerationFailure.class) public ModelAndView denied(ModerationFailure failure) { return new ModelAndView("failure",Map.of("message",failure.getMessage()),HttpStatus.FORBIDDEN); }
    @ExceptionHandler(MaxUploadSizeExceededException.class) public ModelAndView upload() { return new ModelAndView("failure",Map.of("message","Archivo o solicitud supera el límite local. Conservá el borrador y probá un archivo menor."),HttpStatus.PAYLOAD_TOO_LARGE); }
    @ExceptionHandler({IllegalArgumentException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class}) public ModelAndView reference() { return new ModelAndView("failure",Map.of("message","Referencia inválida. Consultá el estado actual y usá su enlace de contenido."),HttpStatus.BAD_REQUEST); }
}
