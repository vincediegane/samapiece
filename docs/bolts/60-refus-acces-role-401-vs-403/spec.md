# Spec — #60 Refus d'accès par rôle renvoie 401 au lieu de 403

## Résumé

Durcissement explicite du contrat 401 (non authentifié) / 403 (authentifié, rôle insuffisant) dans `SecurityConfig`, précédé d'une reproduction manuelle obligatoire et suivi de deux tests d'intégration qui ancrent ce contrat indépendamment de la logique métier de chaque contrôleur, après vérification que la couverture par contrôleur exigée par le ticket est déjà complète.

## Écarts identifiés (à lire avant de commencer)

- **Le design affirme un trou de couverture qui n'existe plus.** `design.md` indique que `GET /api/v1/agents` (`AgentAdminController.lister()`) n'a aucun test de rôle insuffisant. Lecture effective de `backend/src/test/java/sn/samapiece/iam/AgentAdminIntegrationTest.java` (état actuel de la branche `bolt/issue-60-refus-acces-role-401-vs-403`) : les tests `lister_commeAgent_shouldRetourner403` (ligne 441) et `lister_commeAuditeur_shouldRetourner403` (ligne 451) existent déjà et couvrent les deux seuls rôles insuffisants possibles pour cet endpoint (`hasAnyRole('CHEF_POSTE','ADMIN_REGIONAL','ADMIN_NATIONAL')`, donc `AGENT` et `AUDITEUR` sont les rôles à tester — les deux le sont). **Conséquence : ne pas ajouter de nouveau test sur `AgentAdminController.lister()`, cette tâche du design est déjà faite.** Le codeur doit simplement lancer la suite de tests pour confirmer que ces deux tests passent bien en l'état actuel (voir Tâche 5).
- Le critère d'acceptation "au moins un test d'intégration par contrôleur protégé par rôle" est donc déjà satisfait pour les 5 contrôleurs `@PreAuthorize` du dépôt (`PieceController`, `PhotoController`, `AuditController`, `StatistiquesPosteController`/reporting, `AgentAdminController`) — voir Plan de tests. Le travail de ce ticket ne porte donc pas sur l'ajout de nouveaux tests de couverture par contrôleur, mais sur (a) le durcissement de `SecurityConfig`, (b) un test dédié niveau filtre/config qui ancre le contrat indépendamment des contrôleurs métier, et (c) la reproduction manuelle + documentation exigées par le ticket.

## Tâches

- [ ] **Tâche 0 — Reproduction manuelle obligatoire, à faire en premier, avant tout changement de code.** Suivre exactement la procédure de la section "Procédure de reproduction manuelle" ci-dessous. Noter precisement le résultat (401 reproduit ou non, sur quel(s) endpoint(s)) : ce constat sert de matière première à la Tâche 6 (documentation de la cause racine dans la PR). Ne pas écrire de fichier dans le dépôt pour cette étape (le constat va dans la description de la PR, pas dans un fichier versionné).
- [ ] **Tâche 1** — `backend/src/main/java/sn/samapiece/config/SecurityConfig.java` : ajouter un `accessDeniedHandler` explicite (`AccessDeniedHandlerImpl`) dans le bloc `.exceptionHandling(...)`, symétrique à l'`authenticationEntryPoint` existant. Voir Contrat technique pour le diff exact.
- [ ] **Tâche 2** — `backend/src/test/java/sn/samapiece/iam/AuthIntegrationTest.java` : ajouter le test `routeProtegee_avecJwtValideMaisRoleInsuffisant_shouldRetourner403`, qui ancre le contrat 403 sur un endpoint réel (`GET /api/v1/agents`) indépendamment de la logique métier de `AgentAdminController` (ce test vérifie le filtre/la config de sécurité, pas les règles RBAC spécifiques d'un contrôleur — celles-ci sont déjà testées ailleurs, voir Écarts identifiés).
- [ ] **Tâche 3** — même fichier : ajouter le test `routeProtegee_avecAccessTokenExpire_shouldRetourner401`. Trou de couverture réel identifié en lisant le fichier : `refresh_avecTokenExpire_shouldRetourner401` (ligne 223) teste un **refresh token** expiré, mais aucun test existant n'exerce un **access token** expiré sur une route protégée par `@PreAuthorize` — alors que le critère d'acceptation #2 du ticket cite explicitement "jeton expiré" à côté de "absent" et "invalide" (déjà couverts par `routeProtegee_sansJwt_shouldRetourner401` et `routeProtegee_avecJwtMalforme_shouldRetourner401`).
- [ ] **Tâche 4** — `backend/src/main/java/sn/samapiece/config/SecurityConfig.java` : mettre à jour le Javadoc de classe (lignes 33-39) pour refléter l'ajout de l'`accessDeniedHandler` explicite (actuellement il ne documente que l'`authenticationEntryPoint`). Voir Contrat technique.
- [ ] **Tâche 5** — Exécuter la suite de tests backend complète (`mvn test` depuis `backend/`, Docker actif pour Testcontainers) et confirmer que les tests suivants, déjà présents, passent tous en 403 : `AgentAdminIntegrationTest.lister_commeAgent_shouldRetourner403`, `AgentAdminIntegrationTest.lister_commeAuditeur_shouldRetourner403`, ainsi que les tests de rôle insuffisant listés dans le Plan de tests pour `PieceController`, `PhotoController`, `AuditController` et le contrôleur de statistiques. Aucune modification de code n'est attendue ici — seulement une confirmation, à mentionner dans la description de la PR.
- [ ] **Tâche 6** — Rédiger la section "Cause racine" de la description de la PR selon le format défini dans "Documentation attendue dans la PR" ci-dessous, en s'appuyant sur le résultat effectif de la Tâche 0.

## Procédure de reproduction manuelle (Tâche 0)

Aucun compte `DEMO-AGENT-001` / `DEMO-ADMIN-001` n'existe dans ce dépôt (ce sont des exemples illustratifs du ticket, pas des comptes seedés). Il n'y a ni script de seed ni endpoint REST pour créer une `Region`/un `Poste` (uniquement `GET /api/v1/postes`, public). Le seul mécanisme de création d'un premier compte est `AdminBootstrapRunner` (`samapiece.bootstrap-admin.*`, désactivé par défaut, cf. `.env.example` qui ne le configure pas). La procédure ci-dessous construit donc les comptes nécessaires à partir de zéro. Toutes les commandes `curl` supposent Windows + `curl.exe` (pas l'alias PowerShell `Invoke-WebRequest` — appeler explicitement `curl.exe`, ou utiliser Git Bash/WSL) ; adapter si le codeur travaille depuis un shell POSIX (juste `curl`).

1. `cp .env.example .env` (si `.env` n'existe pas déjà).
2. Démarrer toute la stack : `docker-compose up -d --build`. Attendre que `backend` soit `healthy` (`docker-compose ps`) — cela garantit que Flyway a appliqué les migrations V1-V12 (schéma créé, aucune donnée).
3. Insérer une `Region` et un `Poste` directement en base (aucun autre moyen de le faire) :
   ```
   docker-compose exec postgres psql -U samapiece -d samapiece_dev -c "INSERT INTO region (id, nom) VALUES ('11111111-1111-1111-1111-111111111111', 'Dakar');"
   docker-compose exec postgres psql -U samapiece -d samapiece_dev -c "INSERT INTO poste (id, region_id, nom, type, adresse, telephone, horaires) VALUES ('22222222-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111', 'Commissariat Test', 'police', 'Adresse Test', '+221338210000', '{}');"
   ```
4. Éditer `.env` pour activer le bootstrap admin, en réutilisant le `poste_id` de l'étape 3 :
   ```
   BOOTSTRAP_ADMIN_ENABLED=true
   BOOTSTRAP_ADMIN_MATRICULE=TEST-ADMIN-001
   BOOTSTRAP_ADMIN_NOM=Admin Repro
   BOOTSTRAP_ADMIN_POSTE_ID=22222222-2222-2222-2222-222222222222
   ```
5. Redémarrer le backend pour déclencher `AdminBootstrapRunner` (`agentRepository.count()` vaut encore 0, aucun agent n'a été créé) :
   ```
   docker-compose up -d --force-recreate backend
   docker-compose logs backend | findstr "administrateur"
   ```
   Récupérer le `mot de passe temporaire=...` affiché dans le log.
6. Login admin :
   ```
   curl.exe -s -X POST http://localhost:8080/api/v1/auth/login -H "Content-Type: application/json" -d '{"matricule":"TEST-ADMIN-001","motDePasse":"<mot de passe temporaire du log>"}'
   ```
   Récupérer `accessToken` dans la réponse JSON (variable `TOKEN_ADMIN_TMP` ci-dessous).
7. **Point de vigilance critique (signalé par le design) : ce compte a `doitChangerMotDePasse=true`** (`AdminBootstrapRunner` crée l'agent avec ce flag actif, cf. `Agent.java` constructeur 6 arguments). `ForcerChangementMotDePasseFilter` s'exécute pour **toute** requête authentifiée sauf `PUT /api/v1/agents/moi/mot-de-passe`, `GET /api/v1/agents/moi`, `POST /api/v1/auth/refresh` et `/actuator/*` — donc `POST /api/v1/pieces` et `GET /api/v1/agents` n'en font pas partie. Sans l'étape suivante, toute requête ultérieure renverrait un 403 `MOT_DE_PASSE_TEMPORAIRE_NON_CHANGE`, un motif totalement différent du rôle insuffisant que ce ticket cherche à reproduire — à ne pas confondre avec le 401 du ticket ni avec le 403 attendu par ce ticket. Changer immédiatement le mot de passe :
   ```
   curl.exe -s -X PUT http://localhost:8080/api/v1/agents/moi/mot-de-passe -H "Authorization: Bearer <TOKEN_ADMIN_TMP>" -H "Content-Type: application/json" -d '{"motDePasseActuel":"<mot de passe temporaire du log>","nouveauMotDePasse":"MotDePasse123!"}'
   ```
8. Re-login admin avec le nouveau mot de passe pour obtenir un token propre (`doitChangerMotDePasse=false`) :
   ```
   curl.exe -s -X POST http://localhost:8080/api/v1/auth/login -H "Content-Type: application/json" -d '{"matricule":"TEST-ADMIN-001","motDePasse":"MotDePasse123!"}'
   ```
   → `TOKEN_ADMIN`.
9. **Repro n°1 — `POST /api/v1/pieces` avec un rôle insuffisant** (`ADMIN_NATIONAL`, l'endpoint exige `AGENT` ou `CHEF_POSTE`, cf. `PieceController.creer()`) :
   ```
   curl.exe -i -X POST http://localhost:8080/api/v1/pieces -H "Authorization: Bearer <TOKEN_ADMIN>" -H "Content-Type: application/json" -d '{"typeDocument":"CNI","nomTitulaire":"Test","prenomTitulaire":"Test","numeroDocument":"REPRO-001","dateDepot":"2026-09-23","confirmerMalgreDoublon":false}'
   ```
   Observer le code de statut (`-i` affiche les en-têtes) et le corps.
10. Créer un agent de rôle `AGENT` via l'admin (nécessaire pour le repro n°2) :
    ```
    curl.exe -s -X POST http://localhost:8080/api/v1/agents -H "Authorization: Bearer <TOKEN_ADMIN>" -H "Content-Type: application/json" -d '{"posteId":"22222222-2222-2222-2222-222222222222","matricule":"TEST-AGENT-001","nom":"Agent Repro","role":"AGENT"}'
    ```
    Récupérer `motDePasseTemporaire` dans la réponse.
11. Login agent puis changement de mot de passe (même raison qu'à l'étape 7 — cet agent a aussi `doitChangerMotDePasse=true`, `AgentAdminService.creer()` suit le même flux de mot de passe temporaire), puis re-login pour obtenir un token propre `TOKEN_AGENT` (même séquence que les étapes 6-8, en remplaçant les identifiants).
12. **Repro n°2 — `GET /api/v1/agents` avec un rôle insuffisant** (`AGENT`, l'endpoint exige `CHEF_POSTE`, `ADMIN_REGIONAL` ou `ADMIN_NATIONAL`, cf. `AgentAdminController.lister()`) :
    ```
    curl.exe -i -X GET http://localhost:8080/api/v1/agents -H "Authorization: Bearer <TOKEN_AGENT>"
    ```
13. Pour référence, reproduire aussi les deux cas de non-régression (AC2), sur `GET /api/v1/agents` :
    - sans en-tête `Authorization` → attendu 401.
    - avec `Authorization: Bearer token-invalide` → attendu 401.
14. Noter pour chacun des 4 cas (9, 12, 13×2) : code de statut observé, corps de la réponse, en-têtes pertinents. Ce relevé alimente directement la section "Cause racine" de la PR (Tâche 6).

## Contrat technique

### `SecurityConfig.java` — accessDeniedHandler explicite

Import à ajouter (ordre alphabétique, à côté des autres imports `org.springframework.security.web.*`) :
```java
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
```

Bloc `.exceptionHandling(...)` (actuellement lignes 71-72) — remplacer :
```java
.exceptionHandling(exceptions ->
        exceptions.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
```
par :
```java
.exceptionHandling(exceptions -> exceptions
        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
        .accessDeniedHandler(new AccessDeniedHandlerImpl()))
```
`new AccessDeniedHandlerImpl()` tel quel (pas de configuration supplémentaire) : corps vide, code 403 — reproduit exactement le comportement par défaut actuel de Spring Security, ne change le comportement observable d'aucun test existant. Ne pas ajouter de corps JSON structuré (hors périmètre, cf. `design.md`).

Javadoc de classe (lignes 33-39) — remplacer la dernière phrase :
```
 * inséré avant le filtre standard de Spring Security, et {@link HttpStatusEntryPoint} explicite
 * pour renvoyer 401 (le comportement par défaut de Spring Security sans entry point configuré
 * est 403).
 */
```
par :
```
 * inséré avant le filtre standard de Spring Security, {@link HttpStatusEntryPoint} explicite
 * pour renvoyer 401 sur une requête non authentifiée, et {@link AccessDeniedHandlerImpl} explicite
 * pour renvoyer 403 sur un rôle insuffisant (corps vide dans les deux cas ; le 403 explicite
 * documente un comportement qui était déjà celui par défaut de Spring Security, pour ne plus en
 * dépendre implicitement).
 */
```

### `AuthIntegrationTest.java` — nouveaux tests

Fichier : `backend/src/test/java/sn/samapiece/iam/AuthIntegrationTest.java`. Ajouter les deux tests suivants à la suite de `routeProtegee_avecAccessTokenValide_shouldPasserLeFiltreJwt` (dernier test du fichier), en réutilisant les helpers déjà présents (`creerAgentActif`, `loginJson`, `OBJECT_MAPPER`, `jwtProperties`) — ne pas ajouter d'import supplémentaire, tout ce qui est nécessaire (`Clock`, `Duration`, `Instant`, `ZoneOffset`, `JwtService`, `Role`, `get`) est déjà importé dans ce fichier.

```java
@Test
void routeProtegee_avecJwtValideMaisRoleInsuffisant_shouldRetourner403() throws Exception {
    creerAgentActif("PN-2024-00132", Role.AGENT);

    String reponseLogin = mockMvc.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(loginJson("PN-2024-00132", MOT_DE_PASSE_CLAIR)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String accessToken = OBJECT_MAPPER.readTree(reponseLogin).get("accessToken").asText();

    mockMvc.perform(get("/api/v1/agents")
                    .header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isForbidden());
}

@Test
void routeProtegee_avecAccessTokenExpire_shouldRetourner401() throws Exception {
    Agent agent = creerAgentActif("PN-2024-00133", Role.CHEF_POSTE);
    Clock horlogeDansLePasse = Clock.fixed(Instant.now().minus(Duration.ofMinutes(20)), ZoneOffset.UTC);
    JwtService jwtServiceExpire = new JwtService(jwtProperties, horlogeDansLePasse);
    String accessTokenExpire = jwtServiceExpire.genererAccessToken(
            agent.getId(), agent.getMatricule(), agent.getRole(), false);

    mockMvc.perform(get("/api/v1/agents")
                    .header("Authorization", "Bearer " + accessTokenExpire))
            .andExpect(status().isUnauthorized());
}
```
Notes :
- `GET /api/v1/agents` est choisi comme endpoint représentatif car c'est un endpoint réel `@PreAuthorize`, déjà exercé par le reste de la suite ; l'objectif de ces deux tests est d'ancrer le contrat 401/403 au niveau filtre/config (`AuthIntegrationTest` est le fichier qui teste déjà `routeProtegee_sansJwt_shouldRetourner401` et `routeProtegee_avecJwtMalforme_shouldRetourner401`), pas de dupliquer les tests RBAC détaillés de `AgentAdminIntegrationTest`.
- `creerAgentActif` utilise le constructeur `Agent` à 5 arguments (`doitChangerMotDePasse=false` par défaut) — donc pas d'interférence avec `ForcerChangementMotDePasseFilter` dans ces tests. Conserver ce helper tel quel, ne pas passer par le flux de mot de passe temporaire.
- `Clock.fixed(Instant.now().minus(Duration.ofMinutes(20)), ...)` avec `ACCESS_TOKEN_TTL = 15 minutes` (cf. `JwtService.ACCESS_TOKEN_TTL`) garantit un token déjà expiré au moment de l'appel — même pattern que `refresh_avecTokenExpire_shouldRetourner401` (ligne 223) qui utilise 8 jours de recul contre un TTL refresh de 7 jours.

## Documentation attendue dans la PR (critère d'acceptation "cause racine identifiée et documentée")

La description de la PR doit contenir une section "Cause racine" avec, dans l'ordre :
1. **Résultat de la reproduction manuelle (Tâche 0)** : pour chacun des 4 cas testés (`POST /api/v1/pieces` en `ADMIN_NATIONAL`, `GET /api/v1/agents` en `AGENT`, `GET /api/v1/agents` sans jeton, `GET /api/v1/agents` avec jeton malformé), le code de statut réellement observé.
2. **Constat** : le 401 décrit par le ticket se reproduit-il ou non sur l'état actuel de `main` ? Si non (résultat attendu d'après l'audit de code du design, à confirmer empiriquement), l'indiquer explicitement et expliquer pourquoi ce n'est pas une invalidation du ticket : `SecurityConfig` déclare un `authenticationEntryPoint` explicite mais aucun `accessDeniedHandler` explicite ; Spring Security comble ce vide avec son `AccessDeniedHandlerImpl` par défaut (403) tant que l'`Authentication` courante n'est pas anonyme — et `JwtAuthenticationFilter` pose bien une authentification non anonyme avant l'évaluation de `@PreAuthorize`. Le comportement observé est donc déjà correct, mais reposait sur un défaut implicite de Spring Security, pas sur une configuration explicite.
3. **Correctif appliqué malgré tout** : ajout d'un `accessDeniedHandler` explicite (`AccessDeniedHandlerImpl`) dans `SecurityConfig`, symétrique à l'`authenticationEntryPoint`, pour rendre le contrat 401/403 explicite et non tributaire d'un défaut implicite (protection contre une régression future si `exceptionHandling()` est retouché).
4. **Couverture de test** : rappel que le critère "au moins un test par contrôleur protégé par rôle" était déjà satisfait avant ce ticket (lister les 5 contrôleurs et leurs fichiers de test, cf. Plan de tests) — ce ticket ajoute deux tests supplémentaires au niveau filtre/config (`AuthIntegrationTest`) qui ancrent le contrat indépendamment de la logique métier, plus un test comblant un trou réel (access token expiré, jusque-là seul le refresh token expiré était testé).

## Plan de tests

| Critère d'acceptation | Test(s) | Statut |
|---|---|---|
| AC1 — jeton valide mais rôle insuffisant → 403 | `AuthIntegrationTest.routeProtegee_avecJwtValideMaisRoleInsuffisant_shouldRetourner403` (nouveau, Tâche 2) ; corroboré par les tests de rôle déjà existants ci-dessous | Nouveau + déjà couvert |
| AC2 — jeton absent/invalide/expiré → 401 (non-régression) | `AuthIntegrationTest.routeProtegee_sansJwt_shouldRetourner401` (existant), `AuthIntegrationTest.routeProtegee_avecJwtMalforme_shouldRetourner401` (existant), `AuthIntegrationTest.routeProtegee_avecAccessTokenExpire_shouldRetourner401` (nouveau, Tâche 3) | Nouveau + déjà couvert |
| AC3 — cause racine identifiée et documentée dans la PR | Manuel : procédure de reproduction (Tâche 0) + rédaction de la section "Cause racine" de la description de la PR (Tâche 6). Pas de test automatisé pertinent pour ce critère. | Manuel |
| AC4 — au moins un test d'intégration par contrôleur protégé par rôle, cas rôle insuffisant, assertion 403 | Déjà satisfait avant ce ticket (à confirmer par exécution, Tâche 5) : `PieceIntegrationTest.creer_commeAdminNational_shouldRetourner403` et consorts (`backend/src/test/java/sn/samapiece/enregistrement/web/PieceIntegrationTest.java`) ; `PhotoIntegrationTest` (`backend/src/test/java/sn/samapiece/enregistrement/photo/PhotoIntegrationTest.java`) ; `AuditEndpointIntegrationTest.lister_commeAgent_shouldRetourner403` et consorts (`backend/src/test/java/sn/samapiece/audit/AuditEndpointIntegrationTest.java`) ; `StatistiquesPosteIntegrationTest.consulter_commeAuditeur_shouldRetourner403` et consorts (`backend/src/test/java/sn/samapiece/reporting/StatistiquesPosteIntegrationTest.java`) ; `AgentAdminIntegrationTest.lister_commeAgent_shouldRetourner403` et `lister_commeAuditeur_shouldRetourner403` (`backend/src/test/java/sn/samapiece/iam/AgentAdminIntegrationTest.java`) | Déjà couvert, aucun nouveau test requis |

## Hors périmètre (rappel du design)

- Réécriture de `JwtAuthenticationFilter` ou de la gestion des jetons absents/invalides/expirés.
- Corps JSON structuré sur les réponses 401/403 de sécurité.
- Toute modification de `ForcerChangementMotDePasseFilter` / flux `doitChangerMotDePasse` (ticket #58, déjà mergé).
- Modification frontend.
- Ajout de tests de rôle insuffisant sur des contrôleurs déjà couverts (voir Écarts identifiés) — y compris ne pas ajouter de nouveau test spécifique sur `AgentAdminController.lister()`, déjà couvert.
