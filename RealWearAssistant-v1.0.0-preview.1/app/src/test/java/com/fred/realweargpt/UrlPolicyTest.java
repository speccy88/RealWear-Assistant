package com.fred.realweargpt;

import org.junit.Test;
import java.net.InetAddress;
import static org.junit.Assert.*;

public class UrlPolicyTest {
    @Test public void permitsPublicHttpsButNotCredentialsOrOtherSchemes() {
        assertTrue(UrlPolicy.isPublicHttps("https://example.com/photo.jpg?a=1"));
        assertFalse(UrlPolicy.isPublicHttps("http://example.com/photo.jpg"));
        assertFalse(UrlPolicy.isPublicHttps("https://user:password@example.com/photo.jpg"));
        assertFalse(UrlPolicy.isPublicHttps("file:///sdcard/secret"));
        assertFalse(UrlPolicy.isPublicHttps("javascript:alert(1)"));
    }
    @Test public void rejectsLocalTargetsAndUnexpectedPorts() {
        for (String url : new String[]{"https://127.0.0.1/image", "https://192.168.1.2/a", "https://athena.local/photo", "https://localhost/photo", "https://[::1]/a", "https://example.com:8443/a"}) assertFalse(url, UrlPolicy.isPublicHttps(url));
    }
    @Test public void rejectsDnsAnswersThatResolveToPrivateAndMetadataAddresses() throws Exception {
        for (String ip : new String[]{"127.0.0.1", "10.0.0.1", "172.16.1.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0", "::1", "fd00::1", "fe80::1"}) assertFalse(ip, UrlPolicy.isPublicAddress(InetAddress.getByName(ip)));
        assertTrue(UrlPolicy.isPublicAddress(InetAddress.getByName("8.8.8.8")));
        assertTrue(UrlPolicy.isPublicAddress(InetAddress.getByName("2606:4700:4700::1111")));
    }
}
