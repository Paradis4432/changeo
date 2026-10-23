package ar.changeo.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.savedrequest.RequestCache;

final class LocalLoginRedirectStrategy implements RedirectStrategy {
    private final RequestCache requestCache;

    LocalLoginRedirectStrategy(RequestCache requestCache) {
        this.requestCache = requestCache;
    }

    @Override
    public void sendRedirect(HttpServletRequest request, HttpServletResponse response, String url) throws IOException {
        String destination = localDestination(url, request.getContextPath());
        requestCache.removeRequest(request, response);
        response.sendRedirect(response.encodeRedirectURL(destination));
    }

    private static String localDestination(String url, String contextPath) {
        String fallback = contextPath + "/account";
        try {
            URI uri = URI.create(url);
            boolean webOrigin = "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
            if (uri.getRawFragment() != null || uri.getRawUserInfo() != null
                    || uri.isAbsolute() && (!webOrigin || uri.getHost() == null)
                    || !uri.isAbsolute() && uri.getRawAuthority() != null) {
                return fallback;
            }
            String path = uri.getPath();
            if (path == null || !path.startsWith("/") || path.contains("//") || path.contains("%")
                    || unsafeCharacters(path) || unsafeCharacters(uri.getQuery())
                    || Arrays.stream(path.split("/", -1)).anyMatch(segment -> segment.equals(".") || segment.equals(".."))) {
                return fallback;
            }
            String local = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            return uri.isAbsolute() ? local : contextPath + local;
        } catch (IllegalArgumentException failure) {
            return fallback;
        }
    }

    private static boolean unsafeCharacters(String value) {
        return value != null && value.chars().anyMatch(character -> character == '\\' || Character.isISOControl(character));
    }
}
