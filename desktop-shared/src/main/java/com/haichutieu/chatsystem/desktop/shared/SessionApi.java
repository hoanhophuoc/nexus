package com.haichutieu.chatsystem.desktop.shared;

import com.haichutieu.chatsystem.sdk.ApiClient;
import com.haichutieu.chatsystem.sdk.api.DefaultApi;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.http.HttpClient;
import java.net.URI;
import java.net.http.WebSocket;
import java.time.Duration;

/** Holds one user's API client and session cookies; never shared between accounts. */
public final class SessionApi {
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    private final DefaultApi api;
    private final URI serverOrigin;
    private final URI webSocketUriOverride;
    private final HttpClient httpClient;

    public SessionApi(URI serverOrigin) {
        this(serverOrigin, null);
    }

    /** Allows a deployment to serve the realtime endpoint from a different origin than the API. */
    public SessionApi(URI serverOrigin, URI webSocketUriOverride) {
        if (serverOrigin == null || serverOrigin.getHost() == null || serverOrigin.getUserInfo() != null
                || serverOrigin.getQuery() != null || serverOrigin.getFragment() != null
                || (serverOrigin.getPath() != null && !serverOrigin.getPath().isEmpty()
                && !"/".equals(serverOrigin.getPath()))
                || !("https".equalsIgnoreCase(serverOrigin.getScheme())
                || "http".equalsIgnoreCase(serverOrigin.getScheme()))) {
            throw new IllegalArgumentException("Server origin must be an HTTP(S) origin.");
        }
        if (webSocketUriOverride != null && (webSocketUriOverride.getHost() == null
                || webSocketUriOverride.getUserInfo() != null || webSocketUriOverride.getQuery() != null
                || webSocketUriOverride.getFragment() != null
                || !("ws".equalsIgnoreCase(webSocketUriOverride.getScheme())
                || "wss".equalsIgnoreCase(webSocketUriOverride.getScheme())))) {
            throw new IllegalArgumentException("WebSocket URI must be a WS(S) URI.");
        }
        this.serverOrigin = serverOrigin;
        this.webSocketUriOverride = webSocketUriOverride;
        ApiClient client = new ApiClient();
        client.updateBaseUri(serverOrigin.resolve("/api/v1").toString());
        HttpClient.Builder httpBuilder = HttpClient.newBuilder().cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(10));
        client.setHttpClientBuilder(httpBuilder);
        URI apiOrigin = serverOrigin.resolve("/api/v1");
        client.setRequestInterceptor(request -> cookies.getCookieStore().get(apiOrigin).stream()
                .filter(cookie -> "XSRF-TOKEN".equals(cookie.getName()))
                .map(HttpCookie::getValue).findFirst()
                .ifPresent(token -> request.header("X-XSRF-TOKEN", token)));
        this.api = new DefaultApi(client);
        this.httpClient = httpBuilder.build();
    }

    public DefaultApi api() { return api; }

    public CookieManager cookies() { return cookies; }

    public URI webSocketUri() {
        if (webSocketUriOverride != null) return webSocketUriOverride;
        String scheme = "https".equalsIgnoreCase(serverOrigin.getScheme()) ? "wss" : "ws";
        try {
            return new URI(scheme, null, serverOrigin.getHost(), serverOrigin.getPort(), "/ws/v1", null, null);
        } catch (java.net.URISyntaxException exception) {
            throw new IllegalStateException("Invalid configured WebSocket URI.", exception);
        }
    }

    public WebSocket.Builder webSocketBuilder() { return httpClient.newWebSocketBuilder(); }

    public void clear() { cookies.getCookieStore().removeAll(); }
}
