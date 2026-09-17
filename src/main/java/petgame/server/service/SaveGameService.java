package petgame.server.service;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import petgame.server.model.SaveGame;
import petgame.server.repository.SaveGameRepository;

@Service
public class SaveGameService {

    private final SaveGameRepository repository;

    public SaveGameService(SaveGameRepository repository) {
        this.repository = repository;
    }

    public SaveGame createSaveGame(String petName) {
        SaveGame saveGame = new SaveGame(petName);
        return repository.save(saveGame);
    }

    public Optional<SaveGame> findSaveGame(Long id) {
        return repository.findById(id);
    }

    public List<SaveGame> findAllSaveGames() {
        return repository.findAll();
    }
}
