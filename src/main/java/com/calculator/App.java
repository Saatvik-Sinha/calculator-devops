package com.calculator;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleBinaryOperator;
import java.util.regex.Pattern;

/**
 * Lightweight calculator REST API built only on the JDK's built-in
 * {@link com.sun.net.httpserver.HttpServer} (no frameworks).
 *
 * <pre>
 * GET /api/v1/add?a=2&amp;b=3        -> {"operation": "add", "a": 2, "b": 3, "result": 5.0}
 * GET /api/v1/subtract?a=10&amp;b=4  -> {"operation": "subtract", "a": 10, "b": 4, "result": 6.0}
 * GET /api/v1/multiply?a=3&amp;b=4   -> {"operation": "multiply", "a": 3, "b": 4, "result": 12.0}
 * GET /api/v1/divide?a=10&amp;b=2    -> {"operation": "divide", "a": 10, "b": 2, "result": 5.0}
 * GET /actuator/health               -> {"status": "UP"}
 * </pre>
 *
 * The listening port is read from the {@code PORT} environment variable (default 8080).
 */
public final class App {

    static final int DEFAULT_PORT = 8080;
    static final int THREAD_POOL_SIZE = 8;
    static final String API_PREFIX = "/api/v1/";
    static final String HEALTH_PATH = "/actuator/health";
    static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";

    /** Strict decimal number: optional sign, digits with optional fraction, optional exponent. */
    private static final Pattern NUMBER_PATTERN =
            Pattern.compile("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?");

    /** Supported operations, keyed by the last segment of the endpoint path. */
    private static final Map<String, DoubleBinaryOperator> OPERATIONS = Map.of(
            "add", App::add,
            "subtract", App::subtract,
            "multiply", App::multiply,
            "divide", App::divide
    );

    private App() {
        // Utility / entry-point class: not instantiable.
    }

    // ------------------------------------------------------------------
    // Calculator logic (pure functions, no HTTP concerns)
    // ------------------------------------------------------------------

    public static double add(double a, double b) {
        return a + b;
    }

    public static double subtract(double a, double b) {
        return a - b;
    }

    public static double multiply(double a, double b) {
        return a * b;
    }

    /**
     * @throws ArithmeticException if {@code b} is zero
     */
    public static double divide(double a, double b) {
        if (b == 0) {
            throw new ArithmeticException("Division by zero is not allowed");
        }
        return a / b;
    }

    // ------------------------------------------------------------------
    // Routing
    // ------------------------------------------------------------------

    /** Immutable HTTP response description. {@code allow} is only set for 405 responses. */
    record Response(int status, String body, String allow) {
        static Response json(int status, String body) {
            return new Response(status, body, null);
        }
    }

    /**
     * Maps a request (method + URI) to a response. Contains no I/O so it can be unit tested directly.
     */
    static Response route(String method, URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath();

        if (HEALTH_PATH.equals(path)) {
            if (!"GET".equals(method)) {
                return methodNotAllowed();
            }
            return Response.json(200, "{\"status\": \"UP\"}");
        }

        if (path.startsWith(API_PREFIX)) {
            String operation = path.substring(API_PREFIX.length());
            DoubleBinaryOperator function = OPERATIONS.get(operation);
            if (function != null) {
                if (!"GET".equals(method)) {
                    return methodNotAllowed();
                }
                return calculate(operation, function, uri.getRawQuery());
            }
        }

        return error(404, "Endpoint not found");
    }

    private static Response calculate(String operation, DoubleBinaryOperator function, String rawQuery) {
        try {
            Map<String, String> params = parseQuery(rawQuery);
            double a = requireNumber(params, "a");
            double b = requireNumber(params, "b");

            double result = function.applyAsDouble(a, b);
            if (!Double.isFinite(result)) {
                throw new IllegalArgumentException("Result is out of range");
            }
            // "+ 0.0" normalises negative zero (-0.0) to 0.0
            result = result + 0.0;

            String body = "{\"operation\": \"" + operation + "\""
                    + ", \"a\": " + formatNumber(a)
                    + ", \"b\": " + formatNumber(b)
                    + ", \"result\": " + result + "}";
            return Response.json(200, body);
        } catch (IllegalArgumentException | ArithmeticException e) {
            return error(400, e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Request parsing helpers
    // ------------------------------------------------------------------

    /**
     * Parses a raw (still percent-encoded) query string into a map. The first occurrence of a
     * repeated key wins.
     *
     * @throws IllegalArgumentException if the query string contains malformed percent-encoding
     */
    static Map<String, String> parseQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int separator = pair.indexOf('=');
            String rawKey = separator >= 0 ? pair.substring(0, separator) : pair;
            String rawValue = separator >= 0 ? pair.substring(separator + 1) : "";
            try {
                params.putIfAbsent(
                        URLDecoder.decode(rawKey, StandardCharsets.UTF_8),
                        URLDecoder.decode(rawValue, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Malformed query string");
            }
        }
        return params;
    }

    /**
     * Reads a required finite number from the parameter map.
     *
     * @throws IllegalArgumentException if the parameter is missing, not a number, or not finite
     */
    static double requireNumber(Map<String, String> params, String name) {
        String raw = params.get(name);
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("Missing required query parameter: " + name);
        }
        if (!NUMBER_PATTERN.matcher(raw).matches()) {
            throw new IllegalArgumentException("Parameter '" + name + "' must be a valid number");
        }
        double value = Double.parseDouble(raw);
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Parameter '" + name + "' must be a finite number");
        }
        return value;
    }

    /**
     * Formats an echoed input operand: whole numbers print without a decimal point ("2"),
     * everything else uses Java's shortest round-trip representation ("2.5").
     */
    static String formatNumber(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    // ------------------------------------------------------------------
    // JSON / response helpers
    // ------------------------------------------------------------------

    static Response error(int status, String message) {
        return Response.json(status,
                "{\"error\": \"" + escapeJson(message) + "\", \"status\": " + status + "}");
    }

    private static Response methodNotAllowed() {
        Response body = error(405, "Method not allowed");
        return new Response(body.status(), body.body(), "GET");
    }

    static String escapeJson(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // HTTP server wiring
    // ------------------------------------------------------------------

    /** Single entry point for every request: route, write the response, log one line. */
    private static void handle(HttpExchange exchange) {
        String method = exchange.getRequestMethod();
        URI uri = exchange.getRequestURI();

        Response response;
        try {
            response = route(method, uri);
        } catch (RuntimeException e) {
            System.err.println("Unhandled error for " + method + " " + uri + ": " + e);
            response = error(500, "Internal server error");
        }

        try {
            send(exchange, response);
        } catch (IOException | RuntimeException e) {
            System.err.println("Failed to write response for " + method + " " + uri + ": " + e);
        } finally {
            exchange.close();
        }

        System.out.println(method + " " + uri + " -> " + response.status());
    }

    private static void send(HttpExchange exchange, Response response) throws IOException {
        byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", JSON_CONTENT_TYPE);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if (response.allow() != null) {
            exchange.getResponseHeaders().set("Allow", response.allow());
        }

        if ("HEAD".equals(exchange.getRequestMethod())) {
            // HEAD responses carry headers only (-1 = no body, no content length).
            exchange.sendResponseHeaders(response.status(), -1);
            return;
        }

        exchange.sendResponseHeaders(response.status(), bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /**
     * Creates (but does not start) the HTTP server bound to all interfaces on the given port.
     * Port {@code 0} selects a free ephemeral port (used by the integration tests).
     */
    public static HttpServer createServer(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", App::handle);

        AtomicInteger counter = new AtomicInteger();
        server.setExecutor(Executors.newFixedThreadPool(THREAD_POOL_SIZE, runnable -> {
            Thread thread = new Thread(runnable, "http-worker-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }));
        return server;
    }

    /**
     * Resolves the listening port from the PORT environment value.
     *
     * @throws IllegalArgumentException if the value is not an integer between 0 and 65535
     */
    static int resolvePort(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_PORT;
        }
        try {
            int port = Integer.parseInt(value.trim());
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("PORT must be between 0 and 65535: " + value);
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid PORT value: " + value);
        }
    }

    public static void main(String[] args) {
        int port;
        try {
            port = resolvePort(System.getenv("PORT"));
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(1);
            return;
        }

        HttpServer server;
        try {
            server = createServer(port);
        } catch (IOException e) {
            System.err.println("Failed to bind to port " + port + ": " + e.getMessage());
            System.exit(1);
            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down Calculator API...");
            server.stop(1);
        }, "shutdown-hook"));

        server.start();
        System.out.println("Calculator API started on port " + server.getAddress().getPort());
    }
}
