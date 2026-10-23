package ar.changeo.security;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Enumeration;
import java.util.Objects;
import java.util.Set;

public final class PrivateHttpsOriginFilter implements Filter {
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private final PrivateHttpsOrigin origin;

    public PrivateHttpsOriginFilter(PrivateHttpsOrigin origin) {
        this.origin = Objects.requireNonNull(origin);
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
            throws IOException, ServletException {
        var request = (HttpServletRequest) servletRequest;
        var response = (HttpServletResponse) servletResponse;
        if (!origin.matchesAuthority(singleHeader(request.getHeaders("Host")))
                || !origin.matchesServer(request.getServerName(), request.getServerPort())) {
            reject(response, 400, "bad request\n");
            return;
        }
        if (!request.isSecure() || !"https".equals(request.getScheme())) {
            reject(response, 403, "forbidden\n");
            return;
        }
        if (!SAFE_METHODS.contains(request.getMethod())) {
            Enumeration<String> headers = request.getHeaders("Origin");
            if (headers != null && headers.hasMoreElements() && !origin.matchesOrigin(singleHeader(headers))) {
                reject(response, 403, "forbidden\n");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static String singleHeader(Enumeration<String> headers) {
        if (headers == null || !headers.hasMoreElements()) { return null; }
        String value = headers.nextElement();
        return headers.hasMoreElements() ? null : value;
    }

    private static void reject(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType("text/plain;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.getWriter().write(body);
    }
}
