package petgame.server.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SaveGameDTO {

    private long id;
    private String petName;
    private int hunger;
}
