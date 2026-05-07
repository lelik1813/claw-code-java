package com.clawcode.agent.tools.web;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Set;

public class WebUrlGuard {

    private final Set<String> allowedSchemes;
    private final Set<String> blockedHosts;

    public WebUrlGuard(WebToolsProperties props) {
        this.allowedSchemes = Set.copyOf(props.allowedSchemes());
        this.blockedHosts = Set.copyOf(props.blockedHosts().stream()
            .map(String::toLowerCase)
            .toList());
    }

    public URI validateAndNormalize(String raw) {
        URI uri = URI.create(raw).normalize();
        String scheme = uri.getScheme();
        if (scheme == null || !allowedSchemes.contains(scheme.toLowerCase())) {
            throw new IllegalArgumentException("Scheme not allowed: " + scheme);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL has no host: " + raw);
        }
        String lowerHost = host.toLowerCase();
        if (blockedHosts.contains(lowerHost)) {
            throw new IllegalArgumentException("Host blocked: " + host);
        }
        if (isPrivateAddress(lowerHost)) {
            throw new IllegalArgumentException("Private/local address not allowed: " + host);
        }
        return uri;
    }

    private boolean isPrivateAddress(String host) {
        if (host.equals("localhost") || host.endsWith(".local") || host.endsWith(".internal")) {
            return true;
        }
        try {
            InetAddress addr = InetAddress.getByName(host);
            return addr.isLoopbackAddress()
                || addr.isSiteLocalAddress()
                || addr.isLinkLocalAddress()
                || addr.isAnyLocalAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
