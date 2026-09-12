# Review - Ticket #4 : Configuration multi-environnements (dev/staging/prod)

APPROVE

## Critères d'acceptation

| # | Critère | Statut | Preuve |
|---|---------|--------|--------|
| 1 | Profils Spring `dev`, `staging`, `prod` avec `application-{profil}.yml` | Couvert | `backend/src/main/resources/application-dev.yml`, `application-staging.yml`, `application-prod.yml` existent (hérités du ticket #1, non modifiés sur cette branche). `application.yml:5` — `active: ${SPRING_PROFILES_ACTIVE:dev}` — le profil actif est bien piloté par variable d'environnement, défaut `dev`. Vérifié par lecture directe des trois fichiers. |
| 2 | Aucune valeur sensible en clair (DB, JWT) — lue depuis l'environnement | Couvert | `application-dev.yml:6-7` : `username: ${DB_USER}` / `password: ${DB_PASSWORD}` sans défaut (seuls `DB_HOST`/`DB_PORT`/`DB_NAME` ont un défaut non sensible en dev, ligne 5). `application-staging.yml:3-5` et `application-prod.yml:3-5` : aucun défaut du tout, y compris host/port/nom. Aucune clé JWT dans le repo — le module `iam` se limite à un `package-info.java` (`grep -rn "jwt" backend/src/main/resources` ne remonte rien). Point d'attention pré-existant hors périmètre de ce ticket : `.env.example` contient des exemples de secrets en clair (`DB_PASSWORD=samapiece_dev_password`, `MEILI_MASTER_KEY=...`) — c'est un fichier d'exemple versionné intentionnellement (convention documentée dans son en-tête, ticket #3), pas un fichier de config Spring, donc hors du critère et hors périmètre du bolt #4 ; à surveiller si un futur ticket copie ces valeurs telles quelles en environnement réel. |
| 3 | Documentation courte dans `README.md` sur comment basculer de profil | Couvert | Nouveau paragraphe "## Profils Spring (dev/staging/prod)" ajouté dans `README.md` racine, entre la section Docker Compose et "## Conventions" — position conforme à la spec. |

## Vérifications complémentaires

- **Diff réel** (`git diff main..HEAD --name-only`) : seuls `README.md`, `docs/bolts/4-config-multi-env/design.md` et `docs/bolts/4-config-multi-env/spec.md` sont modifiés. Aucun fichier hors périmètre touché : `application-*.yml`, `backend/README.md`, `.env.example`, `docker-compose.yml` sont bien identiques à `main` (`git diff` vide sur ces chemins).
- **Contenu du paragraphe** (`README.md`, nouvelles lignes 48-61) : reprend le texte attendu de la spec quasi mot pour mot.
  - Nomme explicitement les trois profils `dev` (défaut), `staging`, `prod`. ✓
  - Mentionne `SPRING_PROFILES_ACTIVE` comme variable pilotant le profil actif, définie dans `.env` et propagée par `docker-compose.yml`. ✓
  - Mentionne explicitement l'absence de valeur en clair pour `DB_USER`/`DB_PASSWORD`. ✓
  - Nommage des variables cohérent avec `.env.example`/`docker-compose.yml` (`SPRING_PROFILES_ACTIVE`, `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`) — aucune variable inventée. ✓
  - Ne prétend pas que changer de profil modifie aujourd'hui le comportement réel de connexion DB (le datasource reste exclu via `spring.autoconfigure.exclude`, `application.yml:12-13`) — le paragraphe reste sur le registre documentaire/config. ✓
  - Lien `[« Profils disponibles »](./backend/README.md#profils-disponibles)` : ancre valide, `backend/README.md:39` contient bien `## Profils disponibles` (l'ancre GitHub générée à partir de ce titre est `#profils-disponibles`). ✓

## Findings

Aucun. Rien à signaler au-delà du point d'attention hors périmètre déjà noté dans le tableau ci-dessus (secrets d'exemple en clair dans `.env.example`, pré-existant depuis le ticket #3, non concerné par le critère 2 de ce ticket qui porte sur les fichiers de config Spring).

## Build/tests

Ticket purement documentaire : le diff ne touche aucun fichier Java, YAML de config Spring ni composant frontend (`git diff main..HEAD --name-only` confirme que seuls des `.md` sont modifiés). Exécution de la suite backend par prudence malgré l'absence de code impacté :

```
mvn -q -pl backend test
```

Résultat : succès (`SamaPieceApplicationTests` démarre le contexte Spring Boot avec le profil `dev`, aucun échec).

Aucun test frontend pertinent (aucun fichier `frontend/` modifié).

## Conclusion

Le scope annoncé par la spec (un seul paragraphe README) est respecté à la lettre, sans dérive ni régression. Les critères 1 et 2, hérités du ticket #1, sont vérifiés vrais par lecture directe des fichiers de configuration et non simplement pris pour acquis. Le lien vers `backend/README.md#profils-disponibles` est fonctionnel. Build backend au vert.
