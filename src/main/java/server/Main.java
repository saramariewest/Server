package server;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class Main {

    public static void main(String[] args) throws Exception {
        Path path = Path.of("server.properties");
        if (!Files.exists(path)) {
            System.err.println("Copy server.properties.example to server.properties and enter your MySQL credentials.");
            System.exit(1);
        }
        Properties config = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            config.load(input);
        }
        Database database = new Database(config.getProperty("db.url"),
                config.getProperty("db.user"), config.getProperty("db.password", ""));
        // Fail before accepting requests if the database or tables are unavailable.
        database.checkConnection();
        GameServer server = new GameServer(Integer.parseInt(config.getProperty("port", "8080")), database);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        server.start();
        System.out.println("Pet Game server listening on http://127.0.0.1:" + server.port());
        System.out.println("Stop the server with Ctrl+C.");
    }
}
