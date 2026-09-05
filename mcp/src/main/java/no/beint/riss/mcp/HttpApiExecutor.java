package no.beint.riss.mcp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Fixed-origin HTTP execution, with no redirects, credential forwarding, or application reflection. */
public final class HttpApiExecutor implements McpExecutor, AutoCloseable {
    private final URI origin;
    private final String basePath;
    private final Map<String, String> headers;
    private final HttpClient client;
    private final Duration timeout;
    private final int maxResponseBytes;

    public HttpApiExecutor(URI upstream, Map<String, String> headers) {
        this(upstream, headers, Duration.ofSeconds(30), 8 * 1024 * 1024);
    }

    public HttpApiExecutor(URI upstream, Map<String, String> headers, Duration timeout, int maxResponseBytes) {
        if (!List.of("http", "https").contains(upstream.getScheme()) || upstream.getHost() == null
                || upstream.getRawUserInfo() != null || upstream.getRawQuery() != null || upstream.getRawFragment() != null)
            throw new IllegalArgumentException("Upstream must be an HTTP(S) origin with an optional base path");
        if (timeout.isNegative() || timeout.isZero() || maxResponseBytes < 1) throw new IllegalArgumentException("Invalid HTTP limits");
        this.origin = URI.create(upstream.getScheme() + "://" + upstream.getRawAuthority());
        var path = upstream.getRawPath();
        basePath = path == null || path.equals("/") ? "" : path.replaceFirst("/+$", "");
        if (!basePath.isEmpty()) McpCompiler.validatePath(basePath);
        headers.forEach((name, value) -> {
            if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || value == null || value.chars().anyMatch(c -> c < 0x20 || c >= 0x7f))
                throw new IllegalArgumentException("Invalid configured HTTP header");
        });
        this.headers = Map.copyOf(headers);
        this.timeout = timeout;
        this.maxResponseBytes = maxResponseBytes;
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(timeout).build();
    }

    @Override public McpResponse execute(McpRequest request) throws IOException, InterruptedException {
        if (!request.path().startsWith("/") || request.path().startsWith("//") || request.path().contains("#") || request.path().contains("\\"))
            throw new IllegalArgumentException("Unsafe request path");
        var uri = URI.create(origin + basePath + request.path());
        var body = request.body();
        var builder = HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/json")
                .method(request.method(), body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        request.headers().forEach(builder::setHeader);
        headers.forEach(builder::setHeader);
        var future = client.sendAsync(builder.build(), _ -> new LimitedBody(maxResponseBytes));
        try {
            var response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return new McpResponse(response.statusCode(), response.headers().firstValue("Content-Type").orElse("application/octet-stream"), response.body());
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IOException("API response timed out");
        } catch (ExecutionException e) {
            throw new IOException("API request failed", e.getCause());
        } catch (InterruptedException e) {
            future.cancel(true);
            throw e;
        }
    }

    @Override public void close() { client.shutdownNow(); }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        LimitedBody(int limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        @Override public void onNext(List<ByteBuffer> items) {
            for (var item : items) {
                if (item.remaining() > limit - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new IOException("API response exceeds byte limit"));
                    return;
                }
                var chunk = new byte[item.remaining()];
                item.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable throwable) { result.completeExceptionally(throwable); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
