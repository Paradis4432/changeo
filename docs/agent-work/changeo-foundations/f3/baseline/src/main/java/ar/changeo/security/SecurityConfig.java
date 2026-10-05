package ar.changeo.security;

import ar.changeo.identity.AccountService;
import ar.changeo.identity.AbuseLimits;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {
    @Bean UserDetailsService users(AccountService accounts) {
        return contact -> accounts.credentials(contact)
                .map(a -> new AccountPrincipal(a.id(), a.contact(), a.passwordHash(), a.epoch()))
                .orElseThrow(() -> new UsernameNotFoundException("No se pudo autenticar"));
    }

    @Bean
    @ConditionalOnWebApplication
    SecurityFilterChain security(HttpSecurity http, AccountService accounts, AbuseLimits limits) throws Exception {
        http.authorizeHttpRequests(auth -> auth.requestMatchers("/", "/style.css", "/register", "/login", "/password/forgot", "/password/reset", "/error").permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form.loginPage("/login").defaultSuccessUrl("/account", true))
                .logout(logout -> logout.logoutSuccessUrl("/"))
                .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'; style-src 'self'; script-src 'none'; frame-ancestors 'none'; form-action 'self'; base-uri 'none'"))
                        .addHeaderWriter((request, response) -> {
                            response.setHeader("Referrer-Policy", "no-referrer");
                            response.setHeader("Cache-Control", "no-store");
                        }))
                .addFilterBefore(new OncePerRequestFilter() {
                    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
                        if (request.getMethod().equals("POST") && request.getServletPath().equals("/login")
                                && (!limits.allow("login-source:" + request.getRemoteAddr(), 10)
                                || !limits.allow("login-user:" + AccountService.canonicalContact(request.getParameter("username")), 10))) {
                            response.sendRedirect("/login?limited"); return;
                        }
                        chain.doFilter(request, response);
                    }
                }, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new SessionEpochFilter(accounts), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
