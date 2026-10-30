package com.calculator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CalculatorTest {

    private static final double DELTA = 1e-9;

    // ------------------------------------------------------------------
    // Unit tests: arithmetic
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Math operations")
    class MathOperations {

        @ParameterizedTest(name = "add({0}, {1}) = {2}")
        @CsvSource({"2,3,5", "-2,-3,-5", "-2,3,1", "0,0,0", "2.5,0.5,3.0", "1e3,1,1001"})
        void addReturnsSum(double a, double b, double expected) {
            assertEquals(expected, App.add(a, b), DELTA);
        }

        @ParameterizedTest(name = "subtract({0}, {1}) = {2}")
        @CsvSource({"10,4,6", "4,10,-6", "-5,-5,0", "0.3,0.1,0.2", "0,7,-7"})
        void subtractReturnsDifference(double a, double b, double expected) {
            assertEquals(expected, App.subtract(a, b), DELTA);
        }

        @ParameterizedTest(name = "multiply({0}, {1}) = {2}")
        @CsvSource({"3,4,12", "-3,4,-12", "-3,-4,12", "0,99,0", "0.5,0.5,0.25"})
        void multiplyReturnsProduct(double a, double b, double expected) {
            assertEquals(expected, App.multiply(a, b), DELTA);
        }

        @ParameterizedTest(name = "divide({0}, {1}) = {2}")
        @CsvSource({"10,2,5", "1,4,0.25", "-9,3,-3", "0,5,0", "7,-2,-3.5"})
        void divideReturnsQuotient(double a, double b, double expected) {
            assertEquals(expected, App.divide(a, b), DELTA);
        }

        @Test
        void decimalAdditionIsApproximatelyCorrect() {
            assertEquals(0.3, App.add(0.1, 0.2), DELTA);
        }
    }

    @Nested
    @DisplayName("Divide by zero")
    class DivideByZero {

        @Test
        void dividingByZeroThrowsArithmeticException() {
            ArithmeticException ex = assertThrows(ArithmeticException.class, () -> App.divide(10, 0));
            assertEquals("Division by zero is not allowed", ex.getMessage());
        }

        @Test
        void zeroDividedByZeroThrows() {
            assertThrows(ArithmeticException.class, () -> App.divide(0, 0));
        }

        @Test
        void dividingByNegativeZeroThrows() {
            assertThrows(ArithmeticException.class, () -> App.divide(10, -0.0));
        }
    }

    // ------------------------------------------------------------------
    // Unit tests: parsing and formatting helpers
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Request parsing and formatting helpers")
    class Helpers {

        @ParameterizedTest(name = "formatNumber({0}) = {1}")
        @CsvSource({"2,2", "2.0,2", "2.5,2.5", "-4,-4", "-0.0,0", "0.1,0.1", "1e20,1.0E20"})
        void formatNumberEchoesWholeNumbersWithoutDecimals(double value, String expected) {
            assertEquals(expected, App.formatNumber(value));
        }

        @Test
        void parseQueryHandlesNullAndEmpty() {
            assertTrue(App.parseQuery(null).isEmpty());
            assertTrue(App.parseQuery("").isEmpty());
        }

        @Test
        void parseQuerySplitsPairs() {
            Map<String, String> params = App.parseQuery("a=1&b=2");
            assertEquals("1", params.get("a"));
            assertEquals("2", params.get("b"));
        }

        @Test
        void parseQueryDecodesPercentEncoding() {
            assertEquals("+5", App.parseQuery("a=%2B5").get("a"));
        }

        @Test
        void parseQueryKeepsFirstOfDuplicateKeys() {
            assertEquals("1", App.parseQuery("a=1&a=2").get("a"));
        }

        @Test
        void parseQueryTreatsKeyWithoutValueAsEmpty() {
            assertEquals("", App.parseQuery("a").get("a"));
        }

        @Test
        void parseQueryRejectsMalformedEncoding() {
            IllegalArgumentException ex =
                    assertThrows(IllegalArgumentException.class, () -> App.parseQuery("a=%zz"));
            assertEquals("Malformed query string", ex.getMessage());
        }

        @ParameterizedTest
        @ValueSource(strings = {"2", "-2", "+2", "2.5", ".5", "1e3", "1E-3", "007"})
        void requireNumberAcceptsValidNumbers(String raw) {
            assertEquals(Double.parseDouble(raw), App.requireNumber(Map.of("a", raw), "a"), DELTA);
        }

        @ParameterizedTest
        @ValueSource(strings = {"abc", "NaN", "Infinity", "1d", "0x10", "1,5", "--1", "1e", ".", " 5"})
        void requireNumberRejectsInvalidNumbers(String raw) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> App.requireNumber(Map.of("a", raw), "a"));
            assertEquals("Parameter 'a' must be a valid number", ex.getMessage());
        }

        @Test
        void requireNumberRejectsMissingAndEmptyValues() {
            IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                    () -> App.requireNumber(Map.of(), "b"));
            assertEquals("Missing required query parameter: b", missing.getMessage());

            IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                    () -> App.requireNumber(Map.of("b", ""), "b"));
            assertEquals("Missing required query parameter: b", empty.getMessage());
        }

        @Test
        void requireNumberRejectsNumbersThatOverflowToInfinity() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> App.requireNumber(Map.of("a", "1e999"), "a"));
            assertEquals("Parameter 'a' must be a finite number", ex.getMessage());
        }

        @Test
        void escapeJsonEscapesSpecialCharacters() {
            assertEquals("a\\\"b\\\\c\\n", App.escapeJson("a\"b\\c\n"));
        }

        @Test
        void resolvePortUsesDefaultWhenUnset() {
            assertEquals(8080, App.resolvePort(null));
            assertEquals(8080, App.resolvePort(""));
            assertEquals(8080, App.resolvePort("   "));
        }

        @Test
        void resolvePortParsesValidValues() {
            assertEquals(9090, App.resolvePort(" 9090 "));
            assertEquals(0, App.resolvePort("0"));
            assertEquals(65535, App.resolvePort("65535"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"abc", "70000", "-1", "80.5"})
        void resolvePortRejectsInvalidValues(String value) {
            assertThrows(IllegalArgumentException.class, () -> App.resolvePort(value));
        }
    }

    // ------------------------------------------------------------------
    // Integration tests: real HTTP calls against a server on a random port
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("HTTP API")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class HttpApi {

        private HttpServer server;
        private HttpClient client;
        private String baseUrl;

        @BeforeAll
        void startServer() throws IOException {
            server = App.createServer(0);
            server.start();
            baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
        }

        @AfterAll
        void stopServer() {
            server.stop(0);
        }

        private HttpRequest request(String method, String pathAndQuery) {
            return HttpRequest.newBuilder(URI.create(baseUrl + pathAndQuery))
                    .timeout(Duration.ofSeconds(5))
                    .method(method, HttpRequest.BodyPublishers.noBody())
                    .build();
        }

        private HttpResponse<String> call(String method, String pathAndQuery)
                throws IOException, InterruptedException {
            return client.send(request(method, pathAndQuery), HttpResponse.BodyHandlers.ofString());
        }

        private HttpResponse<String> get(String pathAndQuery) throws IOException, InterruptedException {
            return call("GET", pathAndQuery);
        }

        private void assertJson(HttpResponse<String> response, int expectedStatus, String expectedBody) {
            assertEquals(expectedStatus, response.statusCode());
            assertEquals(expectedBody, response.body());
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            assertTrue(contentType.startsWith("application/json"), "Unexpected Content-Type: " + contentType);
        }

        // ---- success cases -------------------------------------------------

        @Test
        void addEndpoint() throws Exception {
            assertJson(get("/api/v1/add?a=2&b=3"), 200,
                    "{\"operation\": \"add\", \"a\": 2, \"b\": 3, \"result\": 5.0}");
        }

        @Test
        void subtractEndpoint() throws Exception {
            assertJson(get("/api/v1/subtract?a=10&b=4"), 200,
                    "{\"operation\": \"subtract\", \"a\": 10, \"b\": 4, \"result\": 6.0}");
        }

        @Test
        void multiplyEndpoint() throws Exception {
            assertJson(get("/api/v1/multiply?a=3&b=4"), 200,
                    "{\"operation\": \"multiply\", \"a\": 3, \"b\": 4, \"result\": 12.0}");
        }

        @Test
        void divideEndpoint() throws Exception {
            assertJson(get("/api/v1/divide?a=10&b=2"), 200,
                    "{\"operation\": \"divide\", \"a\": 10, \"b\": 2, \"result\": 5.0}");
        }

        @Test
        void healthEndpoint() throws Exception {
            assertJson(get("/actuator/health"), 200, "{\"status\": \"UP\"}");
        }

        @Test
        void decimalOperandsAreEchoedAsDecimals() throws Exception {
            assertJson(get("/api/v1/add?a=2.5&b=0.5"), 200,
                    "{\"operation\": \"add\", \"a\": 2.5, \"b\": 0.5, \"result\": 3.0}");
        }

        @Test
        void negativeOperandsAreSupported() throws Exception {
            assertJson(get("/api/v1/subtract?a=-5&b=-8"), 200,
                    "{\"operation\": \"subtract\", \"a\": -5, \"b\": -8, \"result\": 3.0}");
        }

        @Test
        void divisionProducesFractionalResult() throws Exception {
            assertJson(get("/api/v1/divide?a=1&b=4"), 200,
                    "{\"operation\": \"divide\", \"a\": 1, \"b\": 4, \"result\": 0.25}");
        }

        @Test
        void negativeZeroResultIsNormalisedToZero() throws Exception {
            assertJson(get("/api/v1/multiply?a=-1&b=0"), 200,
                    "{\"operation\": \"multiply\", \"a\": -1, \"b\": 0, \"result\": 0.0}");
        }

        @Test
        void largeResultsUseScientificNotation() throws Exception {
            assertJson(get("/api/v1/add?a=5000000&b=5000000"), 200,
                    "{\"operation\": \"add\", \"a\": 5000000, \"b\": 5000000, \"result\": 1.0E7}");
        }

        @Test
        void percentEncodedPlusSignIsAccepted() throws Exception {
            assertJson(get("/api/v1/add?a=%2B5&b=1"), 200,
                    "{\"operation\": \"add\", \"a\": 5, \"b\": 1, \"result\": 6.0}");
        }

        @Test
        void firstOfDuplicateParametersWins() throws Exception {
            assertJson(get("/api/v1/add?a=1&a=100&b=2"), 200,
                    "{\"operation\": \"add\", \"a\": 1, \"b\": 2, \"result\": 3.0}");
        }

        // ---- error cases ---------------------------------------------------

        @Test
        void divideByZeroReturnsBadRequest() throws Exception {
            assertJson(get("/api/v1/divide?a=10&b=0"), 400,
                    "{\"error\": \"Division by zero is not allowed\", \"status\": 400}");
        }

        @Test
        void divideByNegativeZeroReturnsBadRequest() throws Exception {
            assertJson(get("/api/v1/divide?a=10&b=-0"), 400,
                    "{\"error\": \"Division by zero is not allowed\", \"status\": 400}");
        }

        @Test
        void missingParameterReturnsBadRequest() throws Exception {
            assertJson(get("/api/v1/add?a=2"), 400,
                    "{\"error\": \"Missing required query parameter: b\", \"status\": 400}");
            assertJson(get("/api/v1/add?b=2"), 400,
                    "{\"error\": \"Missing required query parameter: a\", \"status\": 400}");
            assertJson(get("/api/v1/add"), 400,
                    "{\"error\": \"Missing required query parameter: a\", \"status\": 400}");
        }

        @Test
        void emptyParameterReturnsBadRequest() throws Exception {
            assertJson(get("/api/v1/add?a=&b=3"), 400,
                    "{\"error\": \"Missing required query parameter: a\", \"status\": 400}");
        }

        @Test
        void nonNumericParameterReturnsBadRequest() throws Exception {
            assertJson(get("/api/v1/add?a=abc&b=3"), 400,
                    "{\"error\": \"Parameter 'a' must be a valid number\", \"status\": 400}");
            assertJson(get("/api/v1/multiply?a=3&b=NaN"), 400,
                    "{\"error\": \"Parameter 'b' must be a valid number\", \"status\": 400}");
        }

        @Test
        void overflowingParameterReturnsBadRequest() throws Exception {
            assertJson(get("/api/v1/add?a=1e999&b=1"), 400,
                    "{\"error\": \"Parameter 'a' must be a finite number\", \"status\": 400}");
        }

        @Test
        void overflowingResultReturnsBadRequest() throws Exception {
            assertJson(get("/api/v1/multiply?a=1e308&b=10"), 400,
                    "{\"error\": \"Result is out of range\", \"status\": 400}");
        }

        @Test
        void unknownEndpointReturnsNotFound() throws Exception {
            assertJson(get("/api/v1/power?a=2&b=3"), 404,
                    "{\"error\": \"Endpoint not found\", \"status\": 404}");
            assertJson(get("/"), 404, "{\"error\": \"Endpoint not found\", \"status\": 404}");
            assertJson(get("/api/v1/add/?a=1&b=2"), 404,
                    "{\"error\": \"Endpoint not found\", \"status\": 404}");
            assertJson(get("/actuator"), 404, "{\"error\": \"Endpoint not found\", \"status\": 404}");
        }

        @Test
        void nonGetMethodsReturnMethodNotAllowed() throws Exception {
            for (String method : List.of("POST", "PUT", "DELETE")) {
                HttpResponse<String> response = call(method, "/api/v1/add?a=2&b=3");
                assertJson(response, 405, "{\"error\": \"Method not allowed\", \"status\": 405}");
                assertEquals("GET", response.headers().firstValue("Allow").orElse(null));
            }
            HttpResponse<String> health = call("POST", "/actuator/health");
            assertEquals(405, health.statusCode());
            assertEquals("GET", health.headers().firstValue("Allow").orElse(null));
        }

        @Test
        void headRequestReturnsHeadersOnly() throws Exception {
            HttpResponse<String> response = call("HEAD", "/api/v1/add?a=2&b=3");
            assertEquals(405, response.statusCode());
            assertEquals("", response.body());
            assertNotNull(response.headers().firstValue("Allow").orElse(null));
        }

        // ---- concurrency ---------------------------------------------------

        @Test
        void serverHandlesConcurrentRequests() {
            List<CompletableFuture<HttpResponse<String>>> futures = IntStream.range(0, 40)
                    .mapToObj(i -> client.sendAsync(
                            request("GET", "/api/v1/add?a=" + i + "&b=1"),
                            HttpResponse.BodyHandlers.ofString()))
                    .toList();

            for (int i = 0; i < futures.size(); i++) {
                HttpResponse<String> response = futures.get(i).join();
                assertEquals(200, response.statusCode());
                assertEquals("{\"operation\": \"add\", \"a\": " + i + ", \"b\": 1, \"result\": "
                        + (i + 1.0) + "}", response.body());
            }
        }
    }
}
