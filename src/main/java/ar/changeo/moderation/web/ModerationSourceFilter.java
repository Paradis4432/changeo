package ar.changeo.moderation.web;

import ar.changeo.moderation.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(-110)
public class ModerationSourceFilter extends OncePerRequestFilter {
    private final Admission admission;
    public ModerationSourceFilter(Admission admission) { this.admission=admission; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        if(request.getMethod().equals("POST") && request.getRequestURI().startsWith(request.getContextPath()+"/moderation/")) {
            try { admission.source(request.getRemoteAddr(),"mutation"); }
            catch(ModerationFailure denied) { response.setStatus(429);response.setHeader("Retry-After","60");response.setContentType("text/plain;charset=UTF-8");response.getWriter().write("Demasiados intentos; reintentá en un minuto. Tus estados y ayuda siguen disponibles.");return; }
        }
        chain.doFilter(request,response);
    }
}
