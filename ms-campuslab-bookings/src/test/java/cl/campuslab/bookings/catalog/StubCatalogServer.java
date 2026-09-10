package cl.campuslab.bookings.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A concurrency-safe stand-in for ms-campuslab-catalog's decrement/increment endpoints,
 * used only for bookings-side saga tests where a genuine second running Spring Boot
 * service (with its own Postgres) would be out of scope for this slice's test
 * infrastructure (design doc §9 AC3/§10 Open Question 3). Real HTTP, real threads, real
 * network races against this real (embedded, JDK {@code HttpServer}-backed) server - only
 * catalog's own {@code @Version}/ledger mechanics are reproduced with simple concurrent
 * Java primitives instead of a second Postgres instance. Catalog's OWN atomic-decrement
 * race is proven separately, against real Postgres, by
 * {@code cl.campuslab.catalog.web.ConcurrentDecrementRaceTest} in ms-campuslab-catalog
 * itself.
 *
 * <p>Defaults to "abundant stock, ordinary idempotent ledger" for any resourceId not
 * explicitly configured, so most tests need no per-resourceId setup at all; individual
 * tests configure a specific resourceId's behavior (zero stock, not-found, a delayed
 * first response) via the setters below.
 */
public class StubCatalogServer implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern PATH_PATTERN =
            Pattern.compile("/api/catalog/resources/([0-9a-fA-F-]{36})/(decrement|increment)");
    private static final int ABUNDANT_STOCK = 1_000_000;

    private final HttpServer server;
    private final int port;

    private final Map<String, Integer> stockByResource = new ConcurrentHashMap<>();
    private final Set<String> notFoundResources = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> delayFirstDecrementMsByResource = new ConcurrentHashMap<>();
    private final Set<String> delayedOnceKeys = ConcurrentHashMap.newKeySet();
    private final Set<String> appliedLedgerKeys = ConcurrentHashMap.newKeySet();
    private final List<String> decrementCalls = new CopyOnWriteArrayList<>();
    private final List<String> incrementCalls = new CopyOnWriteArrayList<>();

    public StubCatalogServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        server.createContext("/api/catalog/resources", this::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        port = server.getAddress().getPort();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    public void setStock(UUID resourceId, int stock) {
        stockByResource.put(resourceId.toString(), stock);
    }

    public int getStock(UUID resourceId) {
        return stockByResource.getOrDefault(resourceId.toString(), ABUNDANT_STOCK);
    }

    public void setNotFound(UUID resourceId) {
        notFoundResources.add(resourceId.toString());
    }

    /** Applies the decrement server-side but delays the HTTP response by {@code millis} - only on the first call for that resourceId (AC6). */
    public void delayFirstDecrementBy(UUID resourceId, long millis) {
        delayFirstDecrementMsByResource.put(resourceId.toString(), millis);
    }

    public int decrementCallCount() {
        return decrementCalls.size();
    }

    public int incrementCallCount() {
        return incrementCalls.size();
    }

    public List<String> incrementCalls() {
        return List.copyOf(incrementCalls);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            handleInternal(exchange);
        } catch (RuntimeException ex) {
            sendJson(exchange, 500, "{}");
        }
    }

    private void handleInternal(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        Matcher matcher = PATH_PATTERN.matcher(path);
        if (!"POST".equals(exchange.getRequestMethod()) || !matcher.matches()) {
            sendJson(exchange, 404, "{}");
            return;
        }
        String resourceId = matcher.group(1);
        String operation = matcher.group(2);
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String bookingId = extractBookingId(body);
        if (bookingId == null) {
            sendJson(exchange, 400, "{\"detail\":\"bookingId is required\"}");
            return;
        }

        if (notFoundResources.contains(resourceId)) {
            sendJson(exchange, 404, "{\"detail\":\"No resource with id " + resourceId + "\"}");
            return;
        }

        String key = resourceId + ":" + bookingId;
        if ("decrement".equals(operation)) {
            handleDecrement(exchange, resourceId, key);
        } else {
            handleIncrement(exchange, resourceId, key);
        }
    }

    private void handleDecrement(HttpExchange exchange, String resourceId, String key) throws IOException {
        decrementCalls.add(key);
        Long delayMs = delayFirstDecrementMsByResource.get(resourceId);
        if (delayMs != null && delayedOnceKeys.add(key)) {
            sleepQuietly(delayMs);
        }

        if (appliedLedgerKeys.contains(key)) {
            sendJson(exchange, 200, stockJson(resourceId));
            return;
        }

        synchronized (internedLock(resourceId)) {
            if (appliedLedgerKeys.contains(key)) {
                sendJson(exchange, 200, stockJson(resourceId));
                return;
            }
            int currentStock = stockByResource.computeIfAbsent(resourceId, id -> ABUNDANT_STOCK);
            if (currentStock <= 0) {
                sendJson(exchange, 409, "{\"detail\":\"Insufficient stock/cupo to approve this booking.\"}");
                return;
            }
            stockByResource.put(resourceId, currentStock - 1);
            appliedLedgerKeys.add(key);
        }
        sendJson(exchange, 200, stockJson(resourceId));
    }

    private void handleIncrement(HttpExchange exchange, String resourceId, String key) throws IOException {
        incrementCalls.add(key);
        synchronized (internedLock(resourceId)) {
            if (!appliedLedgerKeys.remove(key)) {
                sendJson(exchange, 200, stockJson(resourceId));
                return;
            }
            int currentStock = stockByResource.computeIfAbsent(resourceId, id -> ABUNDANT_STOCK);
            stockByResource.put(resourceId, currentStock + 1);
        }
        sendJson(exchange, 200, stockJson(resourceId));
    }

    /** Per-resourceId monitor so concurrent decrement/increment calls on the SAME resource don't interleave, without serializing calls to DIFFERENT resources. */
    private String internedLock(String resourceId) {
        return resourceId.intern();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static String extractBookingId(String body) {
        try {
            JsonNode node = MAPPER.readTree(body);
            JsonNode bookingId = node.get("bookingId");
            return bookingId != null && !bookingId.isNull() ? bookingId.asText() : null;
        } catch (IOException ex) {
            return null;
        }
    }

    private static String stockJson(String resourceId) {
        return "{\"id\":\"" + resourceId + "\",\"stock\":1,\"cupo\":null,\"version\":0}";
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        try {
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException ex) {
            // The client already gave up (e.g. AC6's timeout scenario) - the state
            // mutation above already happened server-side; nothing more to do.
        } finally {
            exchange.close();
        }
    }
}
