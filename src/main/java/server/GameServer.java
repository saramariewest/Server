package server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

// HTTP translates requests into database operations and returns JSON responses.
public class GameServer implements AutoCloseable {
    private final HttpServer server;
    private final Database database;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();

    public GameServer(int port, Database database) throws IOException {
        this.database = database;
        // This first version is a local development server without login.
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(executor);
    }

    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            try {
                route(exchange);
            } catch (BadRequest exception) {
                send(exchange, exception.status, Map.of("error", exception.getMessage()));
            } catch (JsonParseException | IllegalArgumentException exception) {
                send(exchange, 400, Map.of("error", "Invalid JSON or request value."));
            } catch (SQLException exception) {
                System.err.println("Database error: SQL state " + exception.getSQLState() + ", code " + exception.getErrorCode());
                send(exchange, 503, Map.of("error", "Database operation failed. Check the server configuration and schema."));
            } catch (RuntimeException exception) {
                System.err.println("Request failed: " + exception.getClass().getSimpleName());
                send(exchange, 500, Map.of("error", "Internal server error."));
            }
        }
    }

    private void route(HttpExchange exchange) throws IOException, SQLException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        if (path.equals("/health")) {
            allow(exchange, "GET");
            database.checkConnection();
            send(exchange, 200, Map.of("status", "ok"));
        } else if (path.equals("/saves")) {
            allow(exchange, "GET", "POST");
            if (method.equals("GET")) {
                send(exchange, 200, database.saves(null));
            } else {
                JsonObject body = body(exchange);
                validateGame(body);
                String id = UUID.randomUUID().toString();
                JsonObject result = database.save(id, body);
                exchange.getResponseHeaders().set("Location", "/saves/" + id);
                send(exchange, 201, result);
            }
        } else if (path.startsWith("/saves/") && !path.substring(7).contains("/")) {
            allow(exchange, "GET", "PUT", "DELETE");
            String id = path.substring(7);
            if (!UUID.fromString(id).toString().equalsIgnoreCase(id)) throw new BadRequest(400, "Invalid save ID.");
            switch (method) {
                case "GET" -> {
                    JsonArray games = database.saves(id);
                    if (games.isEmpty()) throw new BadRequest(404, "Save not found.");
                    send(exchange, 200, games.get(0));
                }
                case "PUT" -> {
                    JsonObject body = body(exchange);
                    validateGame(body);
                    send(exchange, 200, database.save(id, body));
                }
                case "DELETE" -> {
                    if (!database.delete(id)) throw new BadRequest(404, "Save not found.");
                    send(exchange, 204, null);
                }
                default -> throw new BadRequest(405, "Method not allowed.");
            }
        } else if (path.equals("/highscores")) {
            allow(exchange, "GET", "POST");
            if (method.equals("GET")) {
                send(exchange, 200, database.highscores());
            } else {
                JsonObject body = body(exchange);
                text(body, "player_name", 100);
                integer(body, "level", 1, Integer.MAX_VALUE);
                integer(body, "survival_time_millis", 0, Long.MAX_VALUE);
                send(exchange, 201, database.addHighscore(body));
            }
        } else {
            throw new BadRequest(404, "Endpoint not found.");
        }
    }

    private void allow(HttpExchange exchange, String... methods) {
        if (!Arrays.asList(methods).contains(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", String.join(", ", methods));
            throw new BadRequest(405, "Method not allowed.");
        }
    }

    private JsonObject body(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
            throw new BadRequest(415, "Use Content-Type: application/json.");
        }
        byte[] bytes = exchange.getRequestBody().readNBytes(65_537);
        if (bytes.length > 65_536) throw new BadRequest(413, "Request body exceeds 64 KiB.");
        JsonElement value = JSON.fromJson(new String(bytes, StandardCharsets.UTF_8), JsonElement.class);
        if (value == null || !value.isJsonObject()) throw new BadRequest(400, "Expected a JSON object.");
        return value.getAsJsonObject();
    }

    private void validateGame(JsonObject body) {
        text(body, "save_name", 100);
        text(body, "pet_name", 100);
        for (String field : new String[] {"hunger", "thirst", "mood", "energy"}) integer(body, field, 0, 100);
        integer(body, "level", 1, Integer.MAX_VALUE);
        for (String field : new String[] {"experience", "critical_ticks", "coins"}) integer(body, field, 0, Integer.MAX_VALUE);
        for (String field : new String[] {"init_timestamp", "death_timestamp", "pet_save_time", "player_save_time"}) {
            integer(body, field, 0, Long.MAX_VALUE);
        }
        for (String field : new String[] {"alive", "highscore_recorded"}) {
            if (!primitive(body, field).isBoolean()) throw new BadRequest(400, field + " must be a boolean.");
        }
        if (!body.has("inventory") || !body.get("inventory").isJsonArray()) {
            throw new BadRequest(400, "inventory must be an array.");
        }
        Set<String> names = new HashSet<>();
        for (JsonElement element : body.getAsJsonArray("inventory")) {
            if (!element.isJsonObject()) throw new BadRequest(400, "Invalid inventory entry.");
            JsonObject item = element.getAsJsonObject();
            String name = text(item, "item_name", 50);
            if (!name.matches("[A-Z][A-Z0-9_]*")) throw new BadRequest(400, "Use item names such as WATER or CEREAL.");
            if (!names.add(name)) throw new BadRequest(400, "Duplicate inventory item.");
            integer(item, "quantity", 1, Integer.MAX_VALUE);
        }
    }

    private JsonPrimitive primitive(JsonObject body, String field) {
        if (!body.has(field) || !body.get(field).isJsonPrimitive()) throw new BadRequest(400, "Missing or invalid " + field + ".");
        return body.getAsJsonPrimitive(field);
    }

    private String text(JsonObject body, String field, int maxLength) {
        JsonPrimitive value = primitive(body, field);
        if (!value.isString() || value.getAsString().isBlank() || value.getAsString().length() > maxLength) {
            throw new BadRequest(400, field + " must be a nonempty string of at most " + maxLength + " characters.");
        }
        return value.getAsString();
    }

    private void integer(JsonObject body, String field, long min, long max) {
        JsonPrimitive value = primitive(body, field);
        try {
            if (!value.isNumber()) throw new ArithmeticException();
            long number = value.getAsBigDecimal().longValueExact();
            if (number < min || number > max) throw new ArithmeticException();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new BadRequest(400, field + " must be an integer between " + min + " and " + max + ".");
        }
    }

    private void send(HttpExchange exchange, int status, Object body) throws IOException {
        if (status == 204) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        byte[] bytes = JSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static class BadRequest extends RuntimeException {
        final int status;
        BadRequest(int status, String message) { super(message); this.status = status; }
    }
}
