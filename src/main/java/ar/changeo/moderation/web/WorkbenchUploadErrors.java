package ar.changeo.moderation.web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorViewResolver;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.ModelAndView;

@Component
@Order(-1)
public class WorkbenchUploadErrors implements ErrorViewResolver {
    @Override public ModelAndView resolveErrorView(HttpServletRequest request,HttpStatus status,Map<String,Object> model) {
        Object route=request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        Object method=request.getAttribute(RequestDispatcher.ERROR_METHOD);
        if(status.value()!=HttpStatus.CONTENT_TOO_LARGE.value() || !"POST".equals(method) || !"/moderation/drafts".equals(route)) { return null; }
        return tooLarge();
    }
    static ModelAndView tooLarge() {
        return new ModelAndView("moderation-draft-error",Map.of("message","Archivo o solicitud demasiado grande: hasta 1 MiB por archivo y 5 MiB por formulario. No pudimos leer los campos ni los archivos de este envío; no se guardó un borrador. Los archivos deben seleccionarse de nuevo."),HttpStatus.CONTENT_TOO_LARGE);
    }
}
