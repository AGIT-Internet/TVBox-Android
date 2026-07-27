package com.github.tvbox.osc.server;

import android.content.Context;
import android.text.TextUtils;
import android.util.Base64;

import com.github.tvbox.osc.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.Semaphore;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.Request;
import okhttp3.ResponseBody;

/**
 * Optional LAN-facing server. Kept separate from RemoteServer because that
 * server is part of the native player's playback and proxy runtime.
 */
final class LanServer extends NanoHTTPD {
    private static final Pattern HLS_URI = Pattern.compile("URI=\"([^\"]+)\"");

    private final Context context;
    private final LanApiBridge api;
    private final Semaphore requestSlots = new Semaphore(16, true);

    LanServer(Context context, int port) {
        super(port);
        this.context = context.getApplicationContext();
        this.api = new LanApiBridge(this.context);
    }

    @Override
    public Response serve(IHTTPSession session) {
        long started = System.currentTimeMillis();
        if (!requestSlots.tryAcquire()) {
            LanDiagnostics.record(session.getMethod().name(), session.getUri(), 503, 0, "请求过多");
            return error(Response.Status.SERVICE_UNAVAILABLE, "服务器繁忙，请稍后重试");
        }
        Response result;
        try {
            result = serveInternal(session);
        } catch (Exception error) {
            result = error(Response.Status.INTERNAL_ERROR, "服务器处理失败");
        } finally {
            requestSlots.release();
        }
        int statusCode = result.getStatus().getRequestStatus();
        LanDiagnostics.record(session.getMethod().name(), session.getUri(), statusCode,
                System.currentTimeMillis() - started, statusCode >= 400 ? result.getStatus().getDescription() : "");
        return result;
    }

    private Response serveInternal(IHTTPSession session) {
        try {
            String uri = session.getUri();
            if ("/api/security".equals(uri)) return serveSecurity(session);
            if (uri.startsWith("/api/") || "/proxy".equals(uri) || "/image".equals(uri)) {
                if (!isAuthorized(session)) return error(Response.Status.UNAUTHORIZED, "需要配对");
            }
            if ("/api/security/sessions".equals(uri)) {
                if (Method.GET.equals(session.getMethod())) return serveSessions();
                if (Method.DELETE.equals(session.getMethod())) {
                    LanServerManager.get().regenerateCredentials();
                    Response result = json(Response.Status.OK, new JSONObject().put("revoked", true));
                    result.addHeader("Set-Cookie", "tvbox_token=; Path=/; Max-Age=0; SameSite=Strict");
                    return result;
                }
                return error(Response.Status.METHOD_NOT_ALLOWED, "不支持此请求方法");
            }
            if (Method.GET.equals(session.getMethod()) && "/api/health".equals(uri)) {
                return json(Response.Status.OK, new JSONObject()
                        .put("ok", true)
                        .put("name", "TVBox LAN Server")
                        .put("version", BuildConfig.VERSION_NAME));
            }
            if (Method.GET.equals(session.getMethod()) && "/api/diagnostics".equals(uri)) {
                return json(Response.Status.OK, LanDiagnostics.snapshot());
            }
            if (Method.GET.equals(session.getMethod()) && "/api/sites".equals(uri)) {
                return response(Response.Status.OK, "application/json; charset=utf-8", api.sites().toString());
            }
            if (Method.GET.equals(session.getMethod()) && "/api/home".equals(uri)) {
                return json(Response.Status.OK, api.home());
            }
            if (Method.GET.equals(session.getMethod()) && "/api/catalog".equals(uri)) {
                return json(Response.Status.OK, api.catalog());
            }
            if (Method.GET.equals(session.getMethod()) && "/api/library".equals(uri)) {
                return json(Response.Status.OK, api.library());
            }
            if (Method.GET.equals(session.getMethod()) && uri.startsWith("/api/categories/")) {
                String id = decode(uri.substring("/api/categories/".length()));
                int page = parseInt(parameter(session.getParameters(), "page"), 1);
                return json(Response.Status.OK, api.category(id, page));
            }
            if (Method.GET.equals(session.getMethod()) && "/api/history".equals(uri)) {
                return json(Response.Status.OK, api.history());
            }
            if (Method.GET.equals(session.getMethod()) && "/api/favorites".equals(uri)) {
                return json(Response.Status.OK, api.favorites());
            }
            if (Method.GET.equals(session.getMethod()) && "/api/live".equals(uri)) {
                return json(Response.Status.OK, api.live());
            }
            if (Method.GET.equals(session.getMethod()) && "/api/live/play".equals(uri)) {
                Map<String, List<String>> p = session.getParameters();
                return json(Response.Status.OK, api.livePlay(
                        parseInt(parameter(p, "group"), 0),
                        parseInt(parameter(p, "channel"), 0),
                        parseInt(parameter(p, "source"), 0)));
            }
            if ("/api/live/favorite".equals(uri)
                    && (Method.POST.equals(session.getMethod()) || Method.DELETE.equals(session.getMethod()))) {
                Map<String, List<String>> p = session.getParameters();
                return json(Response.Status.OK, api.setLiveFavorite(
                        parseInt(parameter(p, "group"), 0),
                        parseInt(parameter(p, "channel"), 0),
                        Method.POST.equals(session.getMethod())));
            }
            if (Method.GET.equals(session.getMethod()) && "/api/live/epg".equals(uri)) {
                return json(Response.Status.OK, api.epg(
                        parameter(session.getParameters(), "channel"),
                        parameter(session.getParameters(), "date")));
            }
            if (Method.GET.equals(session.getMethod()) && "/api/search/history".equals(uri)) {
                return json(Response.Status.OK, api.searchHistory());
            }
            if (Method.POST.equals(session.getMethod()) && "/api/search/history".equals(uri)) {
                return json(Response.Status.OK, api.rememberSearch(parameter(session.getParameters(), "keyword")));
            }
            if (Method.DELETE.equals(session.getMethod()) && "/api/search/history".equals(uri)) {
                return json(Response.Status.OK, api.clearSearchHistory());
            }
            if (uri.startsWith("/api/favorites/")) return serveFavorite(uri, session);
            if (uri.startsWith("/api/history/") && Method.DELETE.equals(session.getMethod())) {
                String[] parts = uri.substring("/api/history/".length()).split("/", 2);
                if (parts.length != 2) return error(Response.Status.BAD_REQUEST, "缺少历史记录标识");
                return json(Response.Status.OK, api.deleteHistory(decode(parts[0]), decode(parts[1])));
            }
            if (Method.POST.equals(session.getMethod()) && "/api/progress".equals(uri)) {
                Map<String, List<String>> p = session.getParameters();
                return json(Response.Status.OK, api.saveProgress(
                        parameter(p, "sourceKey"), parameter(p, "id"), parameter(p, "title"),
                        parameter(p, "cover"), parameter(p, "flag"), parseInt(parameter(p, "index"), 0),
                        parameter(p, "episode"), parseLong(parameter(p, "position"), 0L),
                        parseLong(parameter(p, "expectedRevision"), -1L),
                        parseLong(parameter(p, "duration"), 0L),
                        "true".equalsIgnoreCase(parameter(p, "completed"))));
            }
            if (Method.GET.equals(session.getMethod()) && uri.startsWith("/api/sites/")) {
                return serveSiteApi(uri, session.getParameters());
            }
            if (Method.GET.equals(session.getMethod()) && ("/proxy".equals(uri) || "/image".equals(uri))) {
                return proxy(session);
            }
            if (Method.GET.equals(session.getMethod())) return asset(uri);
            return error(Response.Status.METHOD_NOT_ALLOWED, "不支持此请求方法");
        } catch (IllegalArgumentException error) {
            return error(Response.Status.BAD_REQUEST, error.getMessage());
        } catch (Exception error) {
            return error(Response.Status.INTERNAL_ERROR,
                    TextUtils.isEmpty(error.getMessage()) ? "服务器处理失败" : error.getMessage());
        }
    }

    private Response serveSecurity(IHTTPSession session) throws Exception {
        LanServerManager manager = LanServerManager.get();
        if (Method.GET.equals(session.getMethod())) {
            String token = token(session);
            return json(Response.Status.OK, new JSONObject()
                    .put("required", manager.isSecure())
                    .put("authenticated", !manager.isSecure() || manager.authenticate(token))
                    .put("transportSecure", false)
                    .put("pairCodeExpiresAt", manager.isSecure() ? manager.getPairCodeExpiresAt() : 0)
                    .put("sessionCount", manager.getSessionCount()));
        }
        if (Method.POST.equals(session.getMethod())) {
            String code = parameter(session.getParameters(), "code");
            if (!manager.isSecure()) {
                return json(Response.Status.OK, new JSONObject().put("token", ""));
            }
            String address = session.getRemoteIpAddress();
            long retryAfter = manager.pairRetryAfter(address);
            if (retryAfter > 0) {
                return response(status(429), "application/json; charset=utf-8",
                        new JSONObject().put("error", "配对尝试过多，请稍后重试")
                                .put("retryAfter", (retryAfter + 999) / 1000).toString());
            }
            String issuedToken = manager.pair(code, address,
                    parameter(session.getParameters(), "device"), session.getHeaders().get("user-agent"));
            if (!TextUtils.isEmpty(issuedToken)) {
                String token = issuedToken;
                Response result = json(Response.Status.OK, new JSONObject().put("token", token));
                if (!TextUtils.isEmpty(token)) {
                    result.addHeader("Set-Cookie", "tvbox_token=" + token + "; Path=/; HttpOnly; SameSite=Strict");
                }
                return result;
            }
            retryAfter = manager.pairRetryAfter(address);
            if (retryAfter > 0) {
                return response(status(429), "application/json; charset=utf-8",
                        new JSONObject().put("error", "连续输错，已限制配对 60 秒")
                                .put("retryAfter", (retryAfter + 999) / 1000).toString());
            }
            return error(Response.Status.UNAUTHORIZED, "配对码错误");
        }
        return error(Response.Status.METHOD_NOT_ALLOWED, "不支持此请求方法");
    }

    private boolean isAuthorized(IHTTPSession session) {
        LanServerManager manager = LanServerManager.get();
        if (!manager.isSecure()) return true;
        return manager.authenticate(token(session));
    }

    private String token(IHTTPSession session) {
        String token = session.getHeaders().get("x-tvbox-token");
        if (TextUtils.isEmpty(token)) token = parameter(session.getParameters(), "token");
        if (TextUtils.isEmpty(token)) {
            String cookie = session.getHeaders().get("cookie");
            if (!TextUtils.isEmpty(cookie)) {
                for (String part : cookie.split(";")) {
                    String value = part.trim();
                    if (value.startsWith("tvbox_token=")) {
                        token = value.substring("tvbox_token=".length());
                        break;
                    }
                }
            }
        }
        return token;
    }

    private Response serveSessions() throws Exception {
        JSONArray sessions = new JSONArray();
        for (LanServerManager.SessionInfo session : LanServerManager.get().getSessions()) {
            sessions.put(new JSONObject()
                    .put("device", session.device)
                    .put("address", session.address)
                    .put("userAgent", session.userAgent)
                    .put("createdAt", session.createdAt)
                    .put("lastSeenAt", session.lastSeenAt));
        }
        return json(Response.Status.OK, new JSONObject().put("items", sessions));
    }

    private Response serveFavorite(String uri, IHTTPSession session) throws Exception {
        String[] parts = uri.substring("/api/favorites/".length()).split("/", 2);
        if (parts.length != 2) return error(Response.Status.BAD_REQUEST, "缺少收藏标识");
        boolean favorite;
        if (Method.POST.equals(session.getMethod())) favorite = true;
        else if (Method.DELETE.equals(session.getMethod())) favorite = false;
        else return error(Response.Status.METHOD_NOT_ALLOWED, "不支持此请求方法");
        Map<String, List<String>> p = session.getParameters();
        return json(Response.Status.OK, api.setFavorite(
                decode(parts[0]), decode(parts[1]), parameter(p, "title"), parameter(p, "cover"), favorite));
    }

    private Response serveSiteApi(String uri, Map<String, List<String>> parameters) throws Exception {
        String[] parts = uri.substring(1).split("/", 6);
        if (parts.length < 4 || !"api".equals(parts[0]) || !"sites".equals(parts[1])) {
            return error(Response.Status.NOT_FOUND, "接口不存在");
        }
        String sourceKey = decode(parts[2]);
        String action = parts[3];
        if ("search".equals(action)) {
            String keyword = parameter(parameters, "keyword");
            if (TextUtils.isEmpty(keyword)) throw new IllegalArgumentException("搜索词不能为空");
            return json(Response.Status.OK, api.search(sourceKey, keyword));
        }
        if ("detail".equals(action) && parts.length >= 5) {
            return json(Response.Status.OK, api.detail(sourceKey, decode(parts[4])));
        }
        if ("play".equals(action)) {
            String url = parameter(parameters, "url");
            if (TextUtils.isEmpty(url)) throw new IllegalArgumentException("播放地址不能为空");
            return json(Response.Status.OK, api.play(sourceKey, parameter(parameters, "flag"), url));
        }
        return error(Response.Status.NOT_FOUND, "接口不存在");
    }

    private Response asset(String uri) throws IOException {
        String path = "/".equals(uri) || "/index.html".equals(uri) ? "web/index.html" : "web" + uri;
        if (path.contains("..")) return error(Response.Status.FORBIDDEN, "禁止访问");
        InputStream input = context.getAssets().open(path);
        byte[] bytes = readAll(input);
        Response response = newFixedLengthResponse(Response.Status.OK, mime(path),
                new ByteArrayInputStream(bytes), bytes.length);
        response.addHeader("Cache-Control", path.endsWith("index.html") ? "no-cache" : "public, max-age=31536000");
        response.addHeader("X-Content-Type-Options", "nosniff");
        return response;
    }

    private Response proxy(IHTTPSession session) throws Exception {
        String target = parameter(session.getParameters(), "url");
        if (!isHttpUrl(target)) throw new IllegalArgumentException("无效媒体地址");

        Request.Builder request = new Request.Builder().url(target);
        String range = session.getHeaders().get("range");
        if (!TextUtils.isEmpty(range)) request.header("Range", range);
        String encodedHeaders = parameter(session.getParameters(), "headers");
        if (!TextUtils.isEmpty(encodedHeaders)) {
            String headerJson = new String(Base64.decode(encodedHeaders, Base64.URL_SAFE), StandardCharsets.UTF_8);
            JSONObject headers = new JSONObject(headerJson);
            Iterator<String> keys = headers.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = headers.optString(key, "");
                if (key.matches("[A-Za-z0-9-]+") && !TextUtils.isEmpty(value)) request.header(key, value);
            }
        }

        okhttp3.Response upstream = com.github.catvod.net.OkHttp.client().newCall(request.build()).execute();
        ResponseBody body = upstream.body();
        if (body == null) {
            upstream.close();
            throw new IOException("媒体服务器未返回内容");
        }
        String contentType = body.contentType() == null
                ? "application/octet-stream" : body.contentType().toString();
        if (contentType.contains("mpegurl") || target.toLowerCase(Locale.US).contains(".m3u8")) {
            String manifest = body.string();
            String rewritten = rewriteManifest(manifest, upstream.request().url().toString(), encodedHeaders);
            upstream.close();
            return response(status(upstream.code()), "application/vnd.apple.mpegurl; charset=utf-8", rewritten);
        }

        InputStream stream = new FilterInputStream(body.byteStream()) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    upstream.close();
                }
            }
        };
        long length = body.contentLength();
        Response result = length >= 0
                ? newFixedLengthResponse(status(upstream.code()), contentType, stream, length)
                : newChunkedResponse(status(upstream.code()), contentType, stream);
        copyHeader(upstream, result, "Accept-Ranges");
        copyHeader(upstream, result, "Content-Range");
        result.addHeader("Access-Control-Allow-Origin", "*");
        result.addHeader("X-Content-Type-Options", "nosniff");
        result.addHeader("Cache-Control", "/image".equals(session.getUri())
                ? "public, max-age=86400" : "no-store");
        return result;
    }

    private String rewriteManifest(String manifest, String baseUrl, String encodedHeaders) throws Exception {
        URI base = URI.create(baseUrl);
        StringBuilder output = new StringBuilder();
        for (String line : manifest.split("\\r?\\n")) {
            String rewritten = line;
            if (!line.isEmpty() && !line.startsWith("#")) {
                rewritten = proxyPath(base.resolve(line).toString(), encodedHeaders);
            } else if (line.contains("URI=\"")) {
                Matcher matcher = HLS_URI.matcher(line);
                StringBuffer buffer = new StringBuffer();
                while (matcher.find()) {
                    String proxied = proxyPath(base.resolve(matcher.group(1)).toString(), encodedHeaders);
                    matcher.appendReplacement(buffer, "URI=\"" + Matcher.quoteReplacement(proxied) + "\"");
                }
                matcher.appendTail(buffer);
                rewritten = buffer.toString();
            }
            output.append(rewritten).append('\n');
        }
        return output.toString();
    }

    private String proxyPath(String url, String encodedHeaders) throws Exception {
        String result = "/proxy?url=" + URLEncoder.encode(url, "UTF-8");
        if (!TextUtils.isEmpty(encodedHeaders)) result += "&headers=" + URLEncoder.encode(encodedHeaders, "UTF-8");
        return result;
    }

    private byte[] readAll(InputStream input) throws IOException {
        try (InputStream stream = input; java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private String parameter(Map<String, List<String>> parameters, String name) {
        List<String> values = parameters.get(name);
        return values == null || values.isEmpty() ? "" : values.get(0);
    }

    private String decode(String value) throws Exception {
        return java.net.URLDecoder.decode(value, "UTF-8");
    }

    private boolean isHttpUrl(String value) {
        return value != null && (value.startsWith("http://") || value.startsWith("https://"));
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private void copyHeader(okhttp3.Response source, Response target, String name) {
        String value = source.header(name);
        if (!TextUtils.isEmpty(value)) target.addHeader(name, value);
    }

    private Response.IStatus status(int code) {
        Response.Status value = Response.Status.lookup(code);
        if (value != null) return value;
        return new Response.IStatus() {
            @Override
            public int getRequestStatus() {
                return code;
            }

            @Override
            public String getDescription() {
                return code + " Upstream";
            }
        };
    }

    private Response json(Response.Status status, Object value) {
        return response(status, "application/json; charset=utf-8", value.toString());
    }

    private Response error(Response.Status status, String message) {
        try {
            return json(status, new JSONObject().put("error", message));
        } catch (Exception ignored) {
            return response(status, "application/json; charset=utf-8", "{\"error\":\"服务器错误\"}");
        }
    }

    private Response response(Response.IStatus status, String mimeType, String body) {
        Response response = newFixedLengthResponse(status, mimeType, body);
        response.addHeader("Cache-Control", "no-store");
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("X-Content-Type-Options", "nosniff");
        return response;
    }

    private String mime(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }
}
