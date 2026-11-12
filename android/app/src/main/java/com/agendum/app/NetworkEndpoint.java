package com.agendum.app;

import java.net.URI;

/** 配对和后台同步共用的入口校验，发送任何凭据前执行。 */
public final class NetworkEndpoint {
    private NetworkEndpoint() {}
    public static String normalize(String value) {
        if (value == null) return "";
        String text = value.trim();
        try {
            URI uri = new URI(text);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535))) return "";
            if (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/")) return "";
            return text.endsWith("/") ? text.substring(0, text.length() - 1) : text;
        } catch (Exception ignored) { return ""; }
    }
}
