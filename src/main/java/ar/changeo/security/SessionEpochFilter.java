package ar.changeo.security;

import ar.changeo.identity.AccountService;
import ar.changeo.identity.IdentityFailure;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

final class SessionEpochFilter extends OncePerRequestFilter {
    private final AccountService accounts;
    SessionEpochFilter(AccountService accounts) { this.accounts = accounts; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AccountPrincipal principal) {
            boolean valid;
            try { valid = accounts.epoch(principal.id()) == principal.epoch(); }
            catch (IdentityFailure failure) { valid = false; }
            if (!valid) {
                SecurityContextHolder.clearContext();
                if (request.getSession(false) != null) { request.getSession(false).invalidate(); }
                response.sendRedirect("/login?expired"); return;
            }
        }
        chain.doFilter(request, response);
    }
}
