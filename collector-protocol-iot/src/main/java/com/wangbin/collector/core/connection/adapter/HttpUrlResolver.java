package com.wangbin.collector.core.connection.adapter;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Append a relative endpoint to an HTTP base URL without moving it behind the query string. */
public final class HttpUrlResolver {
    private HttpUrlResolver() { }

    public static URI resolve(String baseUrl, String endpoint, Map<String, Object> queryParams) {
        URI base = URI.create(baseUrl);
        if (base.getFragment() != null || base.getHost() == null) {
            throw new IllegalArgumentException("HTTP base URL must have a host and no fragment");
        }
        String suffix = endpoint == null ? "" : endpoint.trim();
        if (suffix.contains("?") || suffix.contains("#") || suffix.contains("://")) {
            throw new IllegalArgumentException("HTTP endpoint must be a relative path without query or fragment");
        }
        String path = base.getRawPath();
        if (!suffix.isEmpty()) {
            path = (path == null ? "" : path).replaceAll("/+$", "") + "/" + suffix.replaceAll("^/+", "");
        }
        StringBuilder url = new StringBuilder(base.getScheme()).append("://").append(base.getRawAuthority());
        if (path != null) url.append(path);
        StringBuilder query = new StringBuilder(base.getRawQuery() == null ? "" : base.getRawQuery());
        if (queryParams != null) {
            for (Map.Entry<String, Object> entry : queryParams.entrySet()) {
                if (entry.getValue() == null) throw new IllegalArgumentException("HTTP query parameter value cannot be null");
                if (query.length() > 0) query.append('&');
                query.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                        .append('=').append(URLEncoder.encode(entry.getValue().toString(), StandardCharsets.UTF_8));
            }
        }
        if (query.length() > 0) url.append('?').append(query);
        return URI.create(url.toString());
    }
}
