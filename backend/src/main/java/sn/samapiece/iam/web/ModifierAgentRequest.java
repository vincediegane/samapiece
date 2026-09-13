package sn.samapiece.iam.web;

import java.util.UUID;

public record ModifierAgentRequest(String nom, UUID posteId) {
    // Les deux champs sont optionnels (null = inchangé). Ni mot de passe ni rôle : hors
    // périmètre de cet endpoint.
}
