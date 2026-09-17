package petgame.server.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import petgame.server.model.SaveGame;

public interface SaveGameRepository extends JpaRepository<SaveGame, Long> {
}