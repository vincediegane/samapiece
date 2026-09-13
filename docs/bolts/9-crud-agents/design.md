# Design -- Ticket #9 : CRUD comptes agents (creation/desactivation) cote admin

## Approche

Ajouter un module web `sn.samapiece.iam.web` (endpoints `AgentAdminController` distinct d'AuthController) qui expose `POST/PATCH/DELETE /api/v1/agents{/id}`, protege par `@PreAuthorize` (premiere utilisation de method security dans le projet - necessite d'ajouter `@EnableMethodSecurity` a `SecurityConfig`). `DELETE` est mappe semantiquement sur une desactivation logique (pas de suppression physique), via une nouvelle methode metier `Agent.desactiver()` coherente avec le style deja pose par #6/#7 (pas de setter generique). Le controle d'acces a la creation/desactivation est un `hasAnyRole(...)` global, sans scoping par poste/region - c'est le choix assume de ce ticket, le scoping fin etant explicitement le perimetre du ticket #8. Le point le plus interessant du ticket (login qui rejette un compte desactive) est deja couvert par `AuthService.login()`/`refresh()` (verification `agent.isActif()` avant toute autre logique) : ce ticket n'ajoute donc aucune nouvelle logique d'authentification, seulement un test d'integration bout-en-bout de regression. Le prix de cette approche : le RBAC restera grossier jusqu'au ticket #8 (un chef de poste pourrait creer un agent pour un poste qui n'est pas le sien), ce qui est documente comme limitation connue plutot que masque.

Cote frontend, comme aucun ticket frontend n'a encore ete traite (pas de client HTTP, pas de store d'auth, pas de routeur), le scope est volontairement minimal : une seule page `AgentsPage` avec liste (tableau) + formulaire de creation inline, un client `fetch` fin sans lib d'etat (pas de Redux/React Query), pas de gestion de token/login UI (le ticket ne le demande pas - on suppose un token porteur configure manuellement pour ce pilote, a documenter comme limitation).

## Fichiers/modules impactes

Backend (a creer, sauf mention contraire) :
- `backend/src/main/java/sn/samapiece/iam/Agent.java` (modifie) - ajout de la methode metier `desactiver()`.
- `backend/src/main/java/sn/samapiece/config/SecurityConfig.java` (modifie) - ajout de `@EnableMethodSecurity` ; verification que `@PreAuthorize` s'applique correctement en mode stateless JWT (les `GrantedAuthority` viennent du `Role` porte par le principal construit dans `JwtAuthenticationFilter` - a verifier a l'implementation). Les routes `/api/v1/agents/**` restent couvertes par `anyRequest().authenticated()`, deja en place.
- `backend/src/main/java/sn/samapiece/iam/AgentAdminService.java` (nouveau) - logique creation/desactivation/modification, generation du mot de passe temporaire, encodage via le bean `PasswordEncoder` existant.
- `backend/src/main/java/sn/samapiece/iam/web/AgentAdminController.java` (nouveau) - `POST/PATCH/DELETE /api/v1/agents{/id}`.
- `backend/src/main/java/sn/samapiece/iam/web/CreerAgentRequest.java`, `AgentResponse.java`, `CreerAgentResponse.java`, `ModifierAgentRequest.java` (nouveaux records DTO, style identique a `iam/web/LoginRequest`/`LoginResponse`).
- `backend/src/main/java/sn/samapiece/iam/AgentIntrouvableException.java` (nouveau) - pour un 404 explicite sur PATCH/DELETE d'un id inconnu.
- `backend/src/main/java/sn/samapiece/iam/web/AgentAdminExceptionHandler.java` (nouveau, `@RestControllerAdvice`) - 404 agent introuvable, 409 matricule deja utilise, 404/400 poste inconnu.
- Migration Flyway : aucune necessaire a priori, `agent.actif` existe deja (V2). Si le besoin de tracabilite de la desactivation (`desactive_le`, `desactive_par`) est retenu pour l'audit (section 10.4), prevoir `V4__ajoute_tracabilite_desactivation_agent.sql` - decision : ne pas l'ajouter dans ce ticket, hors perimetre explicite des criteres d'acceptation, a confirmer par le spec-writer.
- `backend/src/test/java/sn/samapiece/iam/AgentAdminIntegrationTest.java` (nouveau) - test bout-en-bout : creer un agent (201 + mot de passe temporaire), login OK avec ce mot de passe, desactiver via `DELETE`, nouveau login -> 401 explicite ; test complementaire documentant qu'un access token deja emis avant desactivation reste valide jusqu'a expiration.
- `backend/src/test/java/sn/samapiece/iam/AgentTest.java` (modifie) - cas unitaire pour `desactiver()`.

Frontend (a creer - aucun fichier existant dans ce perimetre, `frontend/src/` ne contient que `app/App.tsx` et `features/home/HomePage.tsx`) :
- `frontend/src/features/agents/AgentsPage.tsx` (nouveau) - liste + formulaire de creation.
- `frontend/src/features/agents/agentsApi.ts` (nouveau) - wrapper `fetch` pour `GET/POST/PATCH/DELETE /api/v1/agents`.
- `frontend/src/features/agents/types.ts` (nouveau) - types `Agent`, `CreerAgentPayload`.
- `frontend/src/app/App.tsx` (modifie) - ajout d'un point d'entree simple vers `AgentsPage` (pas de routeur installe - `react-router` absent de `package.json` - decision : affichage conditionnel simple sans lib de routage, pour rester dans le scope minimal).

Note : il n'existe pas de `GET /api/v1/agents` (liste) dans les criteres d'acceptation explicites, mais le frontend en a besoin pour afficher la liste. A ajouter comme endpoint complementaire necessaire (`GET /api/v1/agents`, meme controle de role) - a confirmer avec le spec-writer.

## Decisions cles

1. Desactivation logique, pas suppression : `DELETE /api/v1/agents/{id}` declenche `agent.desactiver()` (met `actif=false`), aucune ligne supprimee. Un vrai DELETE casserait d'eventuelles FK (`piece.agent_id`) et la tracabilite (section 10.4).
2. Nouvelle methode metier `Agent.desactiver()` (pas de setter generique) : decision de la rendre idempotente (desactiver un agent deja inactif ne leve pas d'erreur), plus simple pour une semantique HTTP DELETE idempotente.
3. Generation du mot de passe temporaire : `SecureRandom`, 12+ caracteres alphanumeriques et symboles, hache immediatement avec le `PasswordEncoder` (BCrypt) existant et stocke dans `hash_mot_de_passe`. Le mot de passe en clair n'est jamais persiste ; il est retourne une seule fois dans le corps de la reponse HTTP 201 Created (`CreerAgentResponse.motDePasseTemporaire`). Aucun canal d'envoi (SMS/email) n'existe dans le systeme - documente explicitement comme limitation du pilote, pas un oubli.
4. Controle de role simple : `@PreAuthorize("hasAnyRole('CHEF_POSTE','ADMIN_REGIONAL','ADMIN_NATIONAL')")` sur les trois endpoints d'ecriture, sans verification que l'agent cree/desactive appartient au poste/a la region de l'administrateur appelant. Limite explicitement documentee, a traiter par le ticket #8 (RBAC fin).
5. Login deja resistant a la desactivation : `AuthService.login()` (verification `agent.isActif()` avant toute autre logique) et `refresh()` (meme verification) renvoient deja `AuthenticationException` -> 401 via `AuthExceptionHandler`. Aucune modification de `AuthService` n'est necessaire ; seul un test de regression bout-en-bout est ajoute.
6. Frontend sans state management ni routeur : `fetch` direct + `useState`/`useEffect` locaux dans `AgentsPage`, coherent avec le stade actuel du frontend (une seule page existante, aucune dependance de routing installee).
7. `PATCH /api/v1/agents/{id}` reserve a la modification de champs non sensibles (nom, poste) - pas de changement de mot de passe ni de role via cet endpoint dans ce ticket (non demande par les criteres d'acceptation).

## Risques / points d'attention

- Mot de passe temporaire dans la reponse HTTP : acceptable comme limitation documentee pour ce pilote (pas de canal SMS/email disponible), mais a ne surtout pas logger cote serveur (verifier qu'aucun logger ne trace le corps de requete/reponse de cet endpoint).
- Latence de la desactivation vis-a-vis d'un access token deja emis : un agent desactive reste authentifie avec un access token JWT valide jusqu'a son expiration (15 min, cf. ticket #7) - le stateless JWT ne permet pas de revocation immediate sans blacklist, hors perimetre de ce ticket. Le refresh echoue immediatement (verifie `isActif()` en base), donc l'agent perd l'acces au plus tard 15 min apres desactivation. A documenter comme comportement attendu dans le test, pas un bug.
- Absence actuelle de `@EnableMethodSecurity` dans le projet : premiere utilisation de `@PreAuthorize` - un oubli de l'annotation sur `SecurityConfig` rendrait le controle de role silencieusement inoperant (tout utilisateur authentifie passerait). Point de vigilance pour le codeur et le reviewer.
- Pas d'endpoint de liste (`GET /api/v1/agents`) dans les criteres d'acceptation : necessaire pour le frontend mais non liste explicitement - a trancher par le spec-writer.
- Contrainte UNIQUE sur `matricule` (V2__create_agent.sql) : la creation doit gerer le conflit avec un 409 explicite plutot qu'une 500 issue de l'exception JPA/PostgreSQL brute.
- `poste_id` obligatoire et non nul (FK NOT NULL) : la creation d'un agent necessite un `posteId` valide, resolu via `PosteRepository` - un id inconnu doit renvoyer 400/404 explicite, pas une exception JPA.
- Frontend offline-first (section 11.1) : cette page d'administration n'est pas l'application agent terrain concernee par le offline-first (IndexedDB/PWA) - elle vise un poste de travail administratif suppose toujours connecte ; ne pas construire de logique de synchronisation hors-ligne pour cette page.

## Hors perimetre

- Scoping RBAC fin par poste/region (ticket #8).
- Canal reel d'envoi du mot de passe temporaire (SMS/email).
- Revocation immediate d'un access token deja emis (blacklist JWT) - non demande par les criteres d'acceptation.
- Modification du mot de passe ou du role via `PATCH /api/v1/agents/{id}`.
- Authentification/connexion dans l'interface frontend admin (pas de page de login construite ici).
- Design system, pagination, recherche/filtre sur la liste d'agents - formulaire et tableau bruts suffisent.
- Toute modification de `AuthService`, `JwtService`, ou de la logique de verrouillage de compte (ticket #7), deja correcte pour ce besoin.
