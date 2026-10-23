package ar.changeo.security;

import ar.changeo.identity.AbuseLimits;
import ar.changeo.identity.AccountService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestHeaderRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {
    @Bean
    @Order(0)
    @ConditionalOnWebApplication
    SecurityFilterChain readinessSecurity(HttpSecurity http) throws Exception {
        http.securityMatcher(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/readyz"))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                .requestCache(cache -> cache.disable())
                .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'; style-src 'self'; script-src 'self'; frame-ancestors 'none'; form-action 'self'; base-uri 'none'"))
                        .addHeaderWriter((request, response) -> {
                            response.setHeader("Referrer-Policy", "same-origin");
                            response.setHeader("Cache-Control", "no-store");
                        }));
        return http.build();
    }

    @Bean UserDetailsService users(AccountService accounts) {
        return contact -> accounts.credentials(contact)
                .map(a -> new AccountPrincipal(a.id(), a.contact(), a.passwordHash(), a.epoch()))
                .orElseThrow(() -> new UsernameNotFoundException("No se pudo autenticar"));
    }

    @Bean
    @ConditionalOnWebApplication
    SecurityFilterChain security(HttpSecurity http, AccountService accounts, AbuseLimits limits) throws Exception {
        var requestCache = new HttpSessionRequestCache();
        requestCache.setRequestMatcher(navigationRequests());
        requestCache.setMatchingRequestParameterName(null);
        var success = new SavedRequestAwareAuthenticationSuccessHandler();
        success.setDefaultTargetUrl("/account");
        success.setRequestCache(requestCache);
        success.setRedirectStrategy(new LocalLoginRedirectStrategy(requestCache));
        http.authorizeHttpRequests(auth -> auth.requestMatchers("/", "/style.css", "/conversations-poll.js", "/register", "/login", "/password/forgot", "/password/reset", "/error").permitAll()
                        .requestMatchers(HttpMethod.GET, "/content", "/content/{id}", "/content/{id}/preview", "/content/{id}/notification", "/content/files/{id}").permitAll()
                        .requestMatchers("/profiles/create", "/marketplace/create", "/marketplace/mine", "/marketplace/manage/**").authenticated()
                        .requestMatchers(HttpMethod.GET, "/marketplace", "/listings/{id}", "/listings/{id}/preview", "/listings/{id}/share", "/profiles/{id}", "/profiles/{id}/preview", "/profiles/{id}/share", "/profiles/account/{subject}/{type}", "/marketplace/files/{id}").permitAll()
                        .anyRequest().authenticated())
                .requestCache(cache -> cache.requestCache(requestCache))
                .formLogin(form -> form.loginPage("/login").successHandler(success))
                .logout(logout -> logout.logoutSuccessUrl("/"))
                .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'; style-src 'self'; script-src 'self'; frame-ancestors 'none'; form-action 'self'; base-uri 'none'"))
                        .addHeaderWriter((request, response) -> {
                            response.setHeader("Referrer-Policy", "same-origin");
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

    private static RequestMatcher navigationRequests() {
        var backgroundMedia = new MediaTypeRequestMatcher(MediaType.APPLICATION_JSON,
                MediaType.MULTIPART_FORM_DATA, MediaType.TEXT_EVENT_STREAM);
        backgroundMedia.setIgnoredMediaTypes(Set.of(MediaType.ALL));
        return new AndRequestMatcher(request -> request.getMethod().equals("GET"),
                new NegatedRequestMatcher(new OrRequestMatcher(
                        PathPatternRequestMatcher.withDefaults().matcher("/favicon.*"),
                        new RequestHeaderRequestMatcher("X-Requested-With", "XMLHttpRequest"),
                        new RequestHeaderRequestMatcher("Upgrade", "websocket"), backgroundMedia)));
    }
}
