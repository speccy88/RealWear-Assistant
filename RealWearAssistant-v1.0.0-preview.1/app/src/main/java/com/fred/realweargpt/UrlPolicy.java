package com.fred.realweargpt;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;

/** Image fetches cannot reach the LAN and never receive OpenAI credentials. */
public final class UrlPolicy {
    private UrlPolicy() { }
    public static boolean isPublicHttps(String value) {
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getRawUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) return false;
            host = host.toLowerCase(Locale.ROOT);
            if (!host.contains(".") || host.endsWith(".local") || host.endsWith(".localhost") || host.endsWith(".internal") || host.endsWith(".lan")) return false;
            if (host.matches("[0-9.]+") || host.indexOf(':') >= 0) return false;
            return true;
        } catch (Exception e) { return false; }
    }
    public static boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress();
        if (b.length == 4) {
            int a = b[0] & 255, second = b[1] & 255;
            if (a == 0 || a == 127 || a >= 224 || (a == 100 && second >= 64 && second <= 127) || (a == 169 && second == 254) || (a == 198 && (second == 18 || second == 19))) return false;
        } else if (b.length == 16 && ((b[0] & 0xFE) == 0xFC || b[0] == 0)) return false;
        return true;
    }
}
