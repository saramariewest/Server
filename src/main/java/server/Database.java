package server;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.stream.Collectors;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

// Only this class talks to MySQL. Column names are fixed, values use placeholders.
public class Database {

    static final String[] SAVE_COLUMNS = {
        "id", "save_name", "saved_at", "pet_name", "hunger", "thirst", "mood", "energy",
        "level", "experience", "alive", "critical_ticks", "init_timestamp", "death_timestamp",
        "pet_save_time", "highscore_recorded", "coins", "player_save_time"
    };
    private final String url;
    private final String user;
    private final String password;

    public Database(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    public void checkConnection() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            for (String table : new String[]{"save_games", "inventory_entries", "highscores"}) {
                // Check table access without reading rows, then close the empty result.
                statement.executeQuery("SELECT * FROM " + table + " WHERE 1 = 0").close();
            }
        }
    }

    public JsonObject save(String id, JsonObject input) throws SQLException {
        JsonObject game = input.deepCopy();
        game.addProperty("id", id);
        game.addProperty("saved_at", LocalDateTime.now().toString());
        String columns = String.join(", ", SAVE_COLUMNS);
        String placeholders = Arrays.stream(SAVE_COLUMNS).map(column -> "?").collect(Collectors.joining(", "));
        String updates = Arrays.stream(SAVE_COLUMNS).skip(1).map(column -> column + " = ?")
                .collect(Collectors.joining(", "));
        String sql = "INSERT INTO save_games (" + columns + ") VALUES (" + placeholders
                + ") ON DUPLICATE KEY UPDATE " + updates;
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    int index = 1;
                    for (String column : SAVE_COLUMNS) {
                        bind(statement, index++, column, game.get(column));
                    }
                    for (int i = 1; i < SAVE_COLUMNS.length; i++) {
                        bind(statement, index++, SAVE_COLUMNS[i], game.get(SAVE_COLUMNS[i]));
                    }
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM inventory_entries WHERE save_game_id = ?")) {
                    statement.setString(1, id);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO inventory_entries (save_game_id, item_name, quantity) VALUES (?, ?, ?)")) {
                    for (JsonElement element : game.getAsJsonArray("inventory")) {
                        JsonObject item = element.getAsJsonObject();
                        statement.setString(1, id);
                        statement.setString(2, item.get("item_name").getAsString());
                        statement.setInt(3, item.get("quantity").getAsInt());
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                throw exception;
            }
        }
        return game;
    }

    private void bind(PreparedStatement statement, int index, String column, JsonElement value) throws SQLException {
        if (column.equals("saved_at")) {
            statement.setTimestamp(index, Timestamp.valueOf(LocalDateTime.parse(value.getAsString())));
        } else if (value.getAsJsonPrimitive().isBoolean()) {
            statement.setBoolean(index, value.getAsBoolean());
        } else if (value.getAsJsonPrimitive().isNumber()) {
            statement.setLong(index, value.getAsLong());
        } else {
            statement.setString(index, value.getAsString());
        }
    }

    public JsonArray saves(String id) throws SQLException {
        String sql = "SELECT * FROM save_games" + (id == null ? " ORDER BY saved_at DESC, id" : " WHERE id = ?");
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            if (id != null) {
                statement.setString(1, id);
            }
            JsonArray games = new JsonArray();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    JsonObject game = new JsonObject();
                    for (String column : SAVE_COLUMNS) {
                        if (column.equals("saved_at")) {
                            game.addProperty(column, rows.getTimestamp(column).toLocalDateTime().toString());
                        } else if (column.equals("alive") || column.equals("highscore_recorded")) {
                            game.addProperty(column, rows.getBoolean(column));
                        } else if (rows.getObject(column) instanceof Number number) {
                            game.addProperty(column, number);
                        } else {
                            game.addProperty(column, rows.getString(column));
                        }
                    }
                    games.add(game);
                }
            }
            for (JsonElement element : games) {
                JsonObject game = element.getAsJsonObject();
                try (PreparedStatement inventory = connection.prepareStatement(
                        "SELECT item_name, quantity FROM inventory_entries WHERE save_game_id = ? ORDER BY item_name")) {
                    inventory.setString(1, game.get("id").getAsString());
                    JsonArray items = new JsonArray();
                    try (ResultSet rows = inventory.executeQuery()) {
                        while (rows.next()) {
                            JsonObject item = new JsonObject();
                            item.addProperty("item_name", rows.getString("item_name"));
                            item.addProperty("quantity", rows.getInt("quantity"));
                            items.add(item);
                        }
                    }
                    game.add("inventory", items);
                }
            }
            return games;
        }
    }

    public boolean delete(String id) throws SQLException {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM save_games WHERE id = ?")) {
            statement.setString(1, id);
            return statement.executeUpdate() > 0;
        }
    }

    public JsonObject addHighscore(JsonObject input) throws SQLException {
        JsonObject entry = new JsonObject();
        String name = input.get("player_name").getAsString();
        int level = input.get("level").getAsInt();
        long duration = input.get("survival_time_millis").getAsLong();
        long score = (long) level * 1000L + duration / 1000L;
        long createdAt = System.currentTimeMillis();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO highscores (player_name, level, survival_time_millis, score, created_at) VALUES (?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, name);
            statement.setInt(2, level);
            statement.setLong(3, duration);
            statement.setLong(4, score);
            statement.setLong(5, createdAt);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    entry.addProperty("id", keys.getLong(1));
                }
            }
        }
        entry.addProperty("player_name", name);
        entry.addProperty("level", level);
        entry.addProperty("survival_time_millis", duration);
        entry.addProperty("score", score);
        entry.addProperty("created_at", createdAt);
        return entry;
    }

    public JsonArray highscores() throws SQLException {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM highscores ORDER BY score DESC, level DESC, survival_time_millis DESC, id DESC LIMIT 10"); ResultSet rows = statement.executeQuery()) {
            JsonArray entries = new JsonArray();
            while (rows.next()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("player_name", rows.getString("player_name"));
                for (String column : new String[]{"id", "level", "survival_time_millis", "score", "created_at"}) {
                    entry.addProperty(column, rows.getLong(column));
                }
                entries.add(entry);
            }
            return entries;
        }
    }
}
