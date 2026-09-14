package sn.samapiece.enregistrement;

import java.util.List;

public class DoublonPotentielException extends RuntimeException {

    private final List<String> numerosFicheCandidats;

    public DoublonPotentielException(List<String> numerosFicheCandidats) {
        super("Un doublon potentiel a ete detecte pour ce numero de document.");
        this.numerosFicheCandidats = List.copyOf(numerosFicheCandidats);
    }

    public List<String> getNumerosFicheCandidats() {
        return numerosFicheCandidats;
    }
}
