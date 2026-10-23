package ar.changeo.security;

public final class PrivateHttpsOrigin {
    private static final String PREFIX = "https://";
    private final String host;
    private final int port;

    private PrivateHttpsOrigin(Authority authority) {
        this.host = authority.host();
        this.port = authority.port();
    }

    public static PrivateHttpsOrigin parse(String value) {
        Authority authority = originAuthority(value);
        if (authority == null) {
            throw new IllegalArgumentException("Invalid private HTTPS origin");
        }
        return new PrivateHttpsOrigin(authority);
    }

    public String host() { return host; }

    public int port() { return port; }

    public boolean matchesAuthority(String value) {
        return matches(authority(value));
    }

    public boolean matchesOrigin(String value) {
        return matches(originAuthority(value));
    }

    public boolean matchesServer(String serverName, int serverPort) {
        return port == serverPort && (host.equals(serverName)
                || host.equals("::1") && "[::1]".equals(serverName));
    }

    private boolean matches(Authority authority) {
        return authority != null && host.equals(authority.host()) && port == authority.port();
    }

    private static Authority originAuthority(String value) {
        return boundedAscii(value) && value.startsWith(PREFIX)
                ? authority(value.substring(PREFIX.length())) : null;
    }

    private static Authority authority(String value) {
        if (!boundedAscii(value)) { return null; }
        String literal;
        String host;
        if (value.startsWith("127.0.0.1")) {
            literal = "127.0.0.1";
            host = literal;
        } else if (value.startsWith("[::1]")) {
            literal = "[::1]";
            host = "::1";
        } else {
            return null;
        }
        if (value.length() == literal.length()) { return new Authority(host, 443); }
        if (value.charAt(literal.length()) != ':') { return null; }
        String digits = value.substring(literal.length() + 1);
        if (digits.isEmpty() || digits.length() > 5 || digits.charAt(0) == '0') { return null; }
        int port = 0;
        for (int index = 0; index < digits.length(); index++) {
            char digit = digits.charAt(index);
            if (digit < '0' || digit > '9') { return null; }
            port = port * 10 + digit - '0';
        }
        return port > 0 && port <= 65535 ? new Authority(host, port) : null;
    }

    private static boolean boundedAscii(String value) {
        if (value == null || value.isEmpty() || value.length() > 128) { return false; }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character <= 32 || character >= 127) { return false; }
        }
        return true;
    }

    private record Authority(String host, int port) {}
}
