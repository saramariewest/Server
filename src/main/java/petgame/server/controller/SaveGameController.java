package petgame.server.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import petgame.server.dto.SaveGameDTO;
import petgame.server.model.SaveGame;
import petgame.server.service.SaveGameService;

@RestController
@RequestMapping("/saves")
public class SaveGameController {

    private final SaveGameService service;

    public SaveGameController(SaveGameService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SaveGame createSaveGame(@RequestBody SaveGameDTO request) {
        return service.createSaveGame(request.getPetName());
    }

    @GetMapping("/{id}")
    public SaveGame getSaveGame(@PathVariable Long id) {
        return service.findSaveGame(id)
                .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Save game not found"));
    }

    @GetMapping
    public List<SaveGame> getAllSaveGames() {
        return service.findAllSaveGames();
    }
}
