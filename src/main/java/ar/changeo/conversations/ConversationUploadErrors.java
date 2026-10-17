package ar.changeo.conversations;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.RequestDispatcher;
import java.util.Map;
import java.util.Set;
import org.springframework.core.annotation.Order;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorViewResolver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.ModelAndView;

@Component
@Order(-1)
public class ConversationUploadErrors implements ErrorViewResolver {
    private static final Set<String> ROUTES = Set.of("/conversations/messages", "/agreements/drafts", "/jobs/content");
    @Override public ModelAndView resolveErrorView(HttpServletRequest request, HttpStatus status, Map<String, Object> model) {
        Object route = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        if (status.value() != HttpStatus.CONTENT_TOO_LARGE.value() || !(route instanceof String originalRoute) || !ROUTES.contains(originalRoute)) { return null; }
        return tooLarge();
    }
    static ModelAndView tooLarge() {
        return new ModelAndView("conversation-error", Map.of("message", "Adjuntá hasta cuatro archivos de 1 MiB cada uno. El formulario completo admite hasta 5 MiB."), HttpStatus.CONTENT_TOO_LARGE);
    }
}
