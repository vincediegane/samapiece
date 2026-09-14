package sn.samapiece.alertes;

import java.util.ArrayList;
import java.util.List;
import sn.samapiece.notifications.NumeroTelephone;
import sn.samapiece.notifications.PasserelleSms;

public class FakePasserelleSms implements PasserelleSms {

    public final List<Envoi> envois = new ArrayList<>();

    @Override
    public void envoyer(NumeroTelephone destinataire, String message) {
        envois.add(new Envoi(destinataire, message));
    }

    public Envoi dernierEnvoi() {
        return envois.get(envois.size() - 1);
    }

    public record Envoi(NumeroTelephone destinataire, String message) {}
}
