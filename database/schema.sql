CREATE DATABASE IF NOT EXISTS petgame
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE petgame;

CREATE TABLE IF NOT EXISTS save_games (
    id CHAR(36) NOT NULL,
    save_name VARCHAR(100) NOT NULL,
    saved_at DATETIME(6) NOT NULL,
    pet_name VARCHAR(100) NOT NULL,
    hunger INT NOT NULL,
    thirst INT NOT NULL,
    mood INT NOT NULL,
    energy INT NOT NULL,
    level INT NOT NULL,
    experience INT NOT NULL,
    alive BOOLEAN NOT NULL,
    critical_ticks INT NOT NULL,
    init_timestamp BIGINT NOT NULL,
    death_timestamp BIGINT NOT NULL,
    pet_save_time BIGINT NOT NULL,
    highscore_recorded BOOLEAN NOT NULL,
    coins INT NOT NULL,
    player_save_time BIGINT NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS inventory_entries (
    id BIGINT NOT NULL AUTO_INCREMENT,
    save_game_id CHAR(36) NOT NULL,
    item_name VARCHAR(50) NOT NULL,
    quantity INT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_inventory_save_game
        FOREIGN KEY (save_game_id) REFERENCES save_games(id)
        ON DELETE CASCADE,
    CONSTRAINT uq_inventory_item_per_save UNIQUE (save_game_id, item_name)
);

CREATE TABLE IF NOT EXISTS highscores (
    id BIGINT NOT NULL AUTO_INCREMENT,
    player_name VARCHAR(100) NOT NULL,
    level INT NOT NULL,
    survival_time_millis BIGINT NOT NULL,
    score BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id)
);
