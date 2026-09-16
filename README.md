# Pet Game Server

This is a separate Java application with its own Maven build. It does not import,
start or modify the Swing game. The game will be connected in a later step.

## Start

Requirements: Java JDK 26, Maven 3.9+, and a running MySQL server.

1. In MySQL Workbench, execute `database/schema.sql`. It is a copy of the game's
   existing schema. It creates missing tables without deleting data, but does not
   migrate tables that already have a different structure.
2. Open a terminal in this `server` directory and run:

   ```powershell
   Copy-Item server.properties.example server.properties
   ```

3. Edit `server.properties` and enter the username and password from your MySQL
   connection. Adjust the URL if necessary. This file is ignored by Git.
   Do not surround values with quotes; double literal backslashes in passwords.
4. Start the server:

   ```powershell
   .\run.ps1
   ```

The server listens at `http://127.0.0.1:8080`. Keep this terminal open.
Stop with Ctrl+C. You can also open this directory as a separate VS Code project
and run `server.Main` with this directory as the working directory.
On this Windows JDK, use the VM argument `-Djdk.net.unixdomain.tmpdir=target`
when launching directly from an editor. The script and tests set this themselves
because the JDK's internal socket connection fails with the short Windows TEMP path.

## Try it without the game

In a second PowerShell terminal, also in the `server` directory:

```powershell
Invoke-RestMethod http://127.0.0.1:8080/health

$saved = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/saves `
    -ContentType 'application/json' -InFile examples/save.json
$saved

Invoke-RestMethod "http://127.0.0.1:8080/saves/$($saved.id)"
Invoke-RestMethod http://127.0.0.1:8080/saves

Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/highscores `
    -ContentType 'application/json' -InFile examples/highscore.json
Invoke-RestMethod http://127.0.0.1:8080/highscores
```

These POST requests create demo records in MySQL. The example timestamps are
fixed sample values. Later the game will provide its actual timestamps.
To delete only the demo save you just created:

```powershell
Invoke-RestMethod -Method Delete -Uri "http://127.0.0.1:8080/saves/$($saved.id)"
```

## Endpoints

| Method | Path | Result |
| --- | --- | --- |
| GET | `/health` | Check database and table access |
| GET | `/saves` | All save games with inventory |
| POST | `/saves` | Create a save with a new server-generated UUID (201) |
| GET | `/saves/{id}` | One save, or 404 |
| PUT | `/saves/{id}` | Save a full replacement at this UUID, creating it if absent (200) |
| DELETE | `/saves/{id}` | Delete save and its inventory (204), or 404 |
| GET | `/highscores` | Best ten scores |
| POST | `/highscores` | Add a highscore (201) |

Send JSON with `Content-Type: application/json`. Save fields match the SQL column
names; `inventory` is an array of `item_name` and `quantity` objects.
`id` and `saved_at` are assigned by the server, overriding supplied values.
PUT replaces the entire inventory, so send an empty array to clear it.
The server calculates highscore `score = level * 1000 + survival_time_millis / 1000`
and sets `created_at` itself. See `examples/` for complete request bodies.

Errors return JSON with an `error` field. Invalid input returns 400, unsupported
methods 405, oversized bodies 413, incorrect content types 415 and database
failures 503. Database requests are not automatically retried.

## Understand the structure

`HTTP request -> GameServer -> Database -> MySQL -> JSON response`

- `Main` reads configuration and starts the server.
- `GameServer` matches URLs, validates input and chooses the database operation.
- `Database` runs SQL through JDBC. It uses `?` placeholders for supplied values.
  Save and inventory writes share a transaction: both succeed, or both roll back.
- Gson reads and writes JSON. The HTTP server itself is included in the JDK.

This first version binds only to localhost. It has no accounts or authentication;
all callers on this computer share the same saves. Network deployment and the
game's HTTP client are later steps.

## Tests

```powershell
mvn test
```

Tests send real HTTP requests to the server using a disposable H2 database in
MySQL compatibility mode and the supplied table schema. They cover save/load,
updates, inventory deletion, rollback, validation and highscore ordering.
They never connect to your MySQL database. A successful test run does not replace
the live MySQL check via `/health` and the manual example requests above.
