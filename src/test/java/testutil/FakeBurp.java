package testutil;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.Http;
import burp.api.montoya.http.RequestOptions;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import burp.api.montoya.logging.Logging;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * a minimal in-memory Burp built from java.lang.reflect.Proxy, enough for
 * HttpEngine / BaselineDetector / the enumerators to run without a real Burp
 *
 * the Server callback plays the target: given the request URL and Host header
 * it returns a Resp, returns null for "no response", or throws for a send failure
 */
public final class FakeBurp {

    public record Resp(int status, int length, String location) {
        public static Resp of(int status, int length) { return new Resp(status, length, null); }
        public static Resp redirect(int status, String location) { return new Resp(status, 0, location); }
    }

    @FunctionalInterface
    public interface Server {
        Resp handle(String url, String host) throws Exception;
    }

    /** one sent request as the server saw it */
    public record Sent(String url, Map<String, String> headers) {
        public String header(String name) { return headers.get(name.toLowerCase(Locale.ROOT)); }
    }

    public final List<Sent> sent = new CopyOnWriteArrayList<>();
    public final List<String> out = new CopyOnWriteArrayList<>();
    public final List<String> err = new CopyOnWriteArrayList<>();
    public final MontoyaApi api;
    private final Server server;

    public FakeBurp(Server server) {
        installFactory();
        this.server = server;
        Logging logging = proxy(Logging.class, (p, m, a) -> {
            String name = m.getName();
            if (name.equals("logToOutput")) { out.add(String.valueOf(a[0])); return null; }
            if (name.equals("logToError"))  { err.add(String.valueOf(a[0])); return null; }
            return null;
        });
        Http http = proxy(Http.class, (p, m, a) -> {
            if (!m.getName().equals("sendRequest")) throw new UnsupportedOperationException(m.toString());
            return send((HttpRequest) a[0]);
        });
        this.api = proxy(MontoyaApi.class, (p, m, a) -> switch (m.getName()) {
            case "http" -> http;
            case "logging" -> logging;
            default -> throw new UnsupportedOperationException(m.toString());
        });
    }

    public List<String> sentUrls() {
        List<String> urls = new ArrayList<>();
        for (Sent s : sent) urls.add(s.url());
        return urls;
    }

    private HttpRequestResponse send(HttpRequest req) {
        Map<String, String> headers = headersOf(req);
        sent.add(new Sent(req.url(), headers));
        Resp r;
        try {
            r = server.handle(req.url(), headers.get("host"));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return exchange(req, r);
    }

    // ---- proxies --------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public static <T> T proxy(Class<T> iface, InvocationHandler h) {
        return (T) Proxy.newProxyInstance(FakeBurp.class.getClassLoader(), new Class<?>[]{iface}, (p, m, a) -> {
            if (m.getDeclaringClass() == Object.class) {
                return switch (m.getName()) {
                    case "equals" -> p == a[0];
                    case "hashCode" -> System.identityHashCode(p);
                    default -> iface.getSimpleName() + "@fake";
                };
            }
            return h.invoke(p, m, a);
        });
    }

    private static final Map<HttpRequest, Map<String, String>> HEADERS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static Map<String, String> headersOf(HttpRequest r) {
        Map<String, String> h = HEADERS.get(r);
        return h == null ? Map.of() : h;
    }

    /** request whose withHeader replaces a header of the same name, like Burp's add-or-update */
    static HttpRequest request(String url, Map<String, String> headers) {
        HttpRequest req = proxy(HttpRequest.class, (p, m, a) -> switch (m.getName()) {
            case "url" -> url;
            case "headerValue" -> headers.get(((String) a[0]).toLowerCase(Locale.ROOT));
            case "withHeader" -> {
                if (a.length != 2) throw new UnsupportedOperationException(m.toString());
                Map<String, String> copy = new LinkedHashMap<>(headers);
                copy.put(((String) a[0]).toLowerCase(Locale.ROOT), (String) a[1]);
                yield request(url, copy);
            }
            default -> throw new UnsupportedOperationException(m.toString());
        });
        HEADERS.put(req, headers);
        return req;
    }

    private static HttpRequestResponse exchange(HttpRequest req, Resp r) {
        HttpResponse response = r == null ? null : proxy(HttpResponse.class, (p, m, a) -> switch (m.getName()) {
            case "statusCode" -> (short) r.status();
            case "body" -> proxy(ByteArray.class, (p2, m2, a2) -> {
                if (m2.getName().equals("length")) return r.length();
                throw new UnsupportedOperationException(m2.toString());
            });
            case "headerValue" -> "Location".equalsIgnoreCase((String) a[0]) ? r.location() : null;
            default -> throw new UnsupportedOperationException(m.toString());
        });
        return proxy(HttpRequestResponse.class, (p, m, a) -> switch (m.getName()) {
            case "copyToTempFile" -> { TEMP_COPIES.incrementAndGet(); yield p; }
            case "hasResponse" -> response != null;
            case "response" -> response;
            case "request" -> req;
            default -> throw new UnsupportedOperationException(m.toString());
        });
    }

    /** how many exchanges were moved to a temp file (process wide) */
    public static final java.util.concurrent.atomic.AtomicInteger TEMP_COPIES = new java.util.concurrent.atomic.AtomicInteger();

    private static synchronized void installFactory() {
        if (ObjectFactoryLocator.FACTORY != null) return;
        RequestOptions[] options = new RequestOptions[1];
        options[0] = proxy(RequestOptions.class, (p, m, a) -> options[0]);
        ObjectFactoryLocator.FACTORY = proxy(MontoyaObjectFactory.class, (p, m, a) -> switch (m.getName()) {
            case "requestOptions" -> options[0];
            case "httpRequestFromUrl" -> {
                String url = (String) a[0];
                Map<String, String> h = new LinkedHashMap<>();
                h.put("host", java.net.URI.create(url).getRawAuthority());
                yield request(url, h);
            }
            default -> throw new UnsupportedOperationException(m.toString());
        });
    }

    /** a send failure, as Burp would report a connection error */
    public static Resp fail() throws IOException { throw new IOException("connection refused (fake)"); }
}
