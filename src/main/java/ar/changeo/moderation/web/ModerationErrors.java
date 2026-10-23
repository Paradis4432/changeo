package ar.changeo.moderation.web;

import ar.changeo.moderation.ModerationFailure;
import ar.changeo.identity.IdentityFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.ModelAndView;

@ControllerAdvice(assignableTypes={ModerationController.class,ContentController.class})
public class ModerationErrors {
    @ExceptionHandler(IdentityFailure.class) public ModelAndView identity(IdentityFailure failure,HttpServletRequest request) {
        String path=request.getRequestURI().substring(request.getContextPath().length());
        String field=request.getMethod().equals("POST")?switch(path) {
            case "/moderation/operator/mfa" -> "code";
            case "/moderation/operator/reauth" -> "password";
            default -> "";
        }:"";
        return field.isEmpty()?new ModelAndView(draftRoute(request)?"moderation-draft-error":"failure",Map.of("message",failure.getMessage()),HttpStatus.FORBIDDEN)
                :new ModelAndView("moderation-operator-signin",Map.of("message",failure.getMessage(),"errorField",field),HttpStatus.FORBIDDEN);
    }
    @ExceptionHandler(ModerationFailure.class) public ModelAndView denied(ModerationFailure failure,HttpServletRequest request) { return new ModelAndView(draftRoute(request)?"moderation-draft-error":"failure",Map.of("message",failure.getMessage()),HttpStatus.FORBIDDEN); }
    private static boolean draftRoute(HttpServletRequest request) {
        String path=request.getRequestURI().substring(request.getContextPath().length());
        return request.getMethod().equals("POST") && (path.equals("/moderation/drafts") || path.equals("/moderation/submit"));
    }
    @ExceptionHandler(MaxUploadSizeExceededException.class) public ModelAndView upload(HttpServletRequest request) {
        return request.getMethod().equals("POST") && request.getRequestURI().equals(request.getContextPath()+"/moderation/drafts")?WorkbenchUploadErrors.tooLarge():new ModelAndView("failure",Map.of("message","Archivo o solicitud supera el límite local. Conservá el borrador y probá un archivo menor."),HttpStatus.PAYLOAD_TOO_LARGE);
    }
    @ExceptionHandler({IllegalArgumentException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class}) public ModelAndView reference() { return new ModelAndView("failure",Map.of("message","Referencia inválida. Consultá el estado actual y usá su enlace de contenido."),HttpStatus.BAD_REQUEST); }
}
