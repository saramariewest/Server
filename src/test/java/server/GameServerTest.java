package server;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class GameServerTest {

    private String url;
    private Database database;
    private GameServer server;
    private HttpClient client;

    @BeforeEach
    @SuppressWarnings("unused") // JUnit invokes this method before each test.
    void start() throws Exception {
        // Every test gets a fresh disposable database, never the user's MySQL data.
        url = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        String schema = Files.readString(Path.of("database/schema.sql"));
        schema = schema.substring(schema.indexOf("CREATE TABLE"));
        try (Connection connection = DriverManager.getConnection(url, "sa", ""); Statement statement = connection.createStatement()) {
            for (String sql : schema.split(";")) {
                if (!sql.isBlank()) {
                    statement.execute(sql);
                }
            }
        }
        database = new Database(url, "sa", "");
        server = new GameServer(0, database);
        server.start();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @AfterEach
    @SuppressWarnings("unused") // JUnit invokes this method after each test.
    void stop() throws Exception {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
        try (Connection connection = DriverManager.getConnection(url, "sa", ""); Statement statement = connection.createStatement()) {
            statement.execute("SHUTDOWN");
        }
    }

    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonObject example() throws Exception {
        return JsonParser.parseString(Files.readString(Path.of("examples/save.json"))).getAsJsonObject();
    }

    @Test
    void saveRoundTripUpdateAndCascadeDelete() throws Exception {
        assertEquals(200, request("GET", "/health", null).statusCode());
        JsonObject body = example();
        body.addProperty("pet_name", "Milo's pet 🐾");
        HttpResponse<String> created = request("POST", "/saves", body.toString());
        assertEquals(201, created.statusCode(), created.body());
        JsonObject saved = JsonParser.parseString(created.body()).getAsJsonObject();
        String path = "/saves/" + saved.get("id").getAsString();
        assertEquals(path, created.headers().firstValue("Location").orElseThrow());
        JsonObject loaded = JsonParser.parseString(request("GET", path, null).body()).getAsJsonObject();
        for (String field : body.keySet()) {
            assertEquals(body.get(field), loaded.get(field), field);
        }
        assertTrue(loaded.has("saved_at"));
        body.addProperty("coins", 42);
        body.add("inventory", new JsonArray());
        assertEquals(200, request("PUT", path, body.toString()).statusCode());
        loaded = JsonParser.parseString(request("GET", path, null).body()).getAsJsonObject();
        assertEquals(42, loaded.get("coins").getAsInt());
        assertTrue(loaded.getAsJsonArray("inventory").isEmpty());
        // Put the item back, then verify the foreign key removes it on delete.
        assertEquals(200, request("PUT", path, example().toString()).statusCode());
        assertEquals(1, JsonParser.parseString(request("GET", "/saves", null).body()).getAsJsonArray().size());
        assertEquals(204, request("DELETE", path, null).statusCode());
        assertEquals(404, request("GET", path, null).statusCode());
        try (Connection connection = DriverManager.getConnection(url, "sa", ""); Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM inventory_entries")) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1));
        }
    }

    @Test
    void failedInventoryWriteRollsBackTheWholeSave() throws Exception {
        String id = UUID.randomUUID().toString();
        database.save(id, example());
        JsonObject invalid = example();
        invalid.addProperty("coins", 999);
        JsonArray items = invalid.getAsJsonArray("inventory");
        items.add(items.get(0).deepCopy()); // Violates the database's unique constraint.
        SQLException exception = assertThrows(SQLException.class, () -> database.save(id, invalid));
        assertEquals("23505", exception.getSQLState()); // H2 reports a unique constraint violation.
        JsonObject loaded = database.saves(id).get(0).getAsJsonObject();
        assertEquals(100, loaded.get("coins").getAsInt());
        assertEquals(1, loaded.getAsJsonArray("inventory").size());
    }

    @Test
    void invalidRequestsReturnUsefulHttpStatuses() throws Exception {
        assertEquals(400, request("POST", "/saves", "{").statusCode());
        assertEquals(400, request("POST", "/saves", "{}").statusCode());
        assertEquals(400, request("GET", "/saves/not-a-uuid", null).statusCode());
        assertEquals(404, request("GET", "/unknown", null).statusCode());
        assertEquals(405, request("DELETE", "/saves", null).statusCode());
        assertEquals(413, request("POST", "/saves", " ".repeat(65_537)).statusCode());
        JsonObject invalid = example();
        invalid.addProperty("hunger", 101);
        assertEquals(400, request("POST", "/saves", invalid.toString()).statusCode());
        invalid = example();
        invalid.addProperty("coins", 1.5);
        assertEquals(400, request("POST", "/saves", invalid.toString()).statusCode());
        assertTrue(JsonParser.parseString(request("GET", "/saves", null).body()).getAsJsonArray().isEmpty());
    }

    @Test
    void highscoresAreCalculatedAndLimitedToTheBestTen() throws Exception {
        for (int level = 1; level <= 12; level++) {
            String body = "{\"player_name\":\"Demo\",\"level\":" + level
                    + ",\"survival_time_millis\":120000,\"score\":999999}";
            assertEquals(201, request("POST", "/highscores", body).statusCode());
        }
        JsonArray entries = JsonParser.parseString(request("GET", "/highscores", null).body()).getAsJsonArray();
        assertEquals(10, entries.size());
        assertEquals(12120, entries.get(0).getAsJsonObject().get("score").getAsLong());
        assertEquals(3, entries.get(9).getAsJsonObject().get("level").getAsInt());
    }
}
