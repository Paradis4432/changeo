package ar.changeo.marketplace;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorViewResolver;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.ModelAndView;

@Component
@Order(-1)
public class MarketplaceUploadErrors implements ErrorViewResolver {
    private static final Set<String> ROUTES=Set.of("/marketplace/drafts","/marketplace/back","/marketplace/prepare");
    private static final Pattern RECOVERY=Pattern.compile("recovery=([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");

    @Override public ModelAndView resolveErrorView(HttpServletRequest request, HttpStatus status, Map<String,Object> model) {
        Object route=request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        if (status!=HttpStatus.CONTENT_TOO_LARGE || !(route instanceof String originalRoute) || !ROUTES.contains(originalRoute)) { return null; }
        return tooLarge(request,originalRoute);
    }

    static ModelAndView tooLarge(HttpServletRequest request, String route) {
        Map<String,Object> model=new HashMap<>();
        model.put("message","Archivo o solicitud demasiado grande. Adjuntá hasta cuatro archivos de 1 MiB cada uno. El formulario completo admite hasta 5 MiB. No pudimos leer los campos ni los archivos de este envío; volvé a seleccionar los archivos.");
        String query=request.getQueryString();
        if ((route.equals("/marketplace/drafts") || route.equals("/marketplace/back")) && query!=null && query.length()==45) {
            var match=RECOVERY.matcher(query);
            if (match.matches()) { model.put("recoveryStage",match.group(1)); }
        }
        return new ModelAndView("marketplace-error",model,HttpStatus.CONTENT_TOO_LARGE);
    }
}
