# Spec - Ticket #4 : Configuration multi-environnements (dev/staging/prod)

## Résumé

Ajouter au `README.md` racine un court paragraphe documentant les profils Spring `dev`/`staging`/`prod` et renvoyant vers `backend/README.md`, seul changement nécessaire puisque les deux premiers critères d'acceptation sont déjà satisfaits par le scaffolding du ticket #1.

## Tâches

- [ ] **`README.md`** (racine) : ajouter un paragraphe "Profils Spring (dev/staging/prod)" juste après la section "## Lancer la stack complète avec Docker Compose" (donc avant "## Conventions", après la ligne 46 `docker-compose down -v` + le bloc de code qui la contient). Contenu exact attendu (adapter la forme, garder le fond) :

  ```markdown
  ## Profils Spring (dev/staging/prod)

  Le backend supporte trois profils Spring : `dev` (défaut), `staging` et `prod`
  (`backend/src/main/resources/application-{dev,staging,prod}.yml`). Le profil actif est
  piloté par la variable d'environnement `SPRING_PROFILES_ACTIVE` (définie dans `.env`,
  propagée au conteneur `backend` par `docker-compose.yml`) — aucune valeur n'est codée en
  dur dans les fichiers versionnés. Le mot de passe et l'utilisateur de la base
  (`DB_USER`, `DB_PASSWORD`) ne sont jamais définis en clair : ils sont lus depuis
  l'environnement dans les trois profils, sans valeur par défaut en `staging`/`prod`, et
  sans valeur par défaut sensible en `dev` non plus (seuls `DB_HOST`/`DB_PORT`/`DB_NAME` ont
  un défaut non sensible en `dev`). Détail des profils et commande pour en changer :
  voir la section [« Profils disponibles »](./backend/README.md#profils-disponibles) de
  `backend/README.md`.
  ```

  Points non négociables dans la formulation finale :
  - Nommer explicitement les trois profils `dev`, `staging`, `prod`.
  - Mentionner `SPRING_PROFILES_ACTIVE` comme variable pilotant le profil actif, et le fait qu'elle vient de l'environnement (`.env` / `docker-compose.yml`), pas d'une valeur en dur.
  - Mentionner qu'aucun mot de passe / clé n'est en clair dans les fichiers versionnés (DB_USER/DB_PASSWORD lus depuis l'environnement).
  - Faire un lien relatif vers `./backend/README.md` (idéalement l'ancre `#profils-disponibles`, à vérifier après rendu GitHub — si l'ancre ne matche pas, lien simple vers le fichier).
  - Ne pas affirmer que changer de profil change le comportement réel de connexion DB aujourd'hui (le datasource reste exclu via `spring.autoconfigure.exclude`, ticket #1) — rester sur le fait documentaire/config, pas fonctionnel.

- [ ] Vérifier que le nouveau paragraphe respecte le nommage déjà en usage dans `.env.example` et `docker-compose.yml` (`SPRING_PROFILES_ACTIVE`, `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`) — aucun nom de variable inventé.

- [ ] Aucune autre modification de fichier n'est nécessaire ni attendue pour ce ticket : ne pas toucher à `backend/src/main/resources/application.yml`, `application-dev.yml`, `application-staging.yml`, `application-prod.yml`, `backend/README.md`, `.env.example`, `docker-compose.yml`.

## Contrat technique

Aucun contrat de code (pas d'endpoint, pas d'entité, pas de schéma). Seul artefact : un bloc Markdown dans `README.md` racine, positionné entre la section Docker Compose et la section Conventions.

Référence de nommage à respecter (déjà en vigueur, ne pas dévier) :
- `SPRING_PROFILES_ACTIVE` (défaut `dev` dans `.env.example` ligne 9 et dans `application.yml` ligne 5 : `active: ${SPRING_PROFILES_ACTIVE:dev}`).
- `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` (utilisés dans `application-dev.yml` lignes 5-7, `application-staging.yml`/`application-prod.yml` lignes 3-5, propagés dans `docker-compose.yml` lignes 17-21).

## Plan de tests

Ce ticket est documentaire ; la « vérification » consiste à relire le contenu existant et le nouveau paragraphe, pas à exécuter des tests automatisés. Correspondance critère → preuve :

1. **Critère « Profils Spring dev, staging, prod avec application-{profil}.yml »**
   - Preuve (déjà satisfaite, à citer telle quelle dans la review, ne rien recréer) :
     - Fichiers existants : `backend/src/main/resources/application-dev.yml`, `application-staging.yml`, `application-prod.yml`.
     - `backend/src/main/resources/application.yml` ligne 5 : `active: ${SPRING_PROFILES_ACTIVE:dev}` — le profil actif est bien piloté par variable d'environnement avec défaut `dev`.
   - Vérification manuelle : `ls backend/src/main/resources/application-*.yml` doit lister les trois fichiers.

2. **Critère « Aucune valeur sensible en clair — lues depuis l'environnement »**
   - Preuve (déjà satisfaite) :
     - `application-dev.yml` lignes 6-7 : `username: ${DB_USER}` / `password: ${DB_PASSWORD}` — aucune valeur par défaut fournie (contrairement à `DB_HOST`/`DB_PORT`/`DB_NAME` ligne 5 qui ont des défauts non sensibles `localhost`/`5432`/`samapiece_dev`).
     - `application-staging.yml` lignes 3-5 et `application-prod.yml` lignes 3-5 : `${DB_HOST}`, `${DB_PORT}`, `${DB_NAME}`, `${DB_USER}`, `${DB_PASSWORD}` — aucun défaut du tout, y compris pour host/port/nom.
     - Aucune clé JWT ni secret d'authentification n'existe dans le repo (module `iam` réduit à un `package-info.java`) — rien à corriger.
   - Vérification manuelle : `grep -n "DB_USER\|DB_PASSWORD" backend/src/main/resources/application-*.yml` doit montrer uniquement des références `${...}` sans valeur par défaut après les deux-points (pas de `${DB_PASSWORD:...}`).

3. **Critère « Documentation courte dans README.md sur comment basculer de profil »**
   - Preuve à produire par ce ticket : le nouveau paragraphe "Profils Spring (dev/staging/prod)" dans `README.md` racine (voir tâche ci-dessus), qui renvoie à la section "Profils disponibles" de `backend/README.md` (déjà présente lignes 39-51, incluant la commande `SPRING_PROFILES_ACTIVE=staging mvn -pl backend spring-boot:run`).
   - Vérification manuelle : ouvrir `README.md` après modification, confirmer visuellement la présence du paragraphe et que le lien vers `backend/README.md` fonctionne (rendu GitHub ou clic local).

Aucun test automatisé (JUnit, Testcontainers, Vitest) n'est pertinent : il n'y a ni code Java ni composant React modifié.

## Écarts identifiés

- Le design signale que seul `README.md` racine manque de documentation multi-environnements ; confirmé par lecture directe : la section "Stack"/"Monorepo" du `README.md` racine (lignes 8-19) ne mentionne aucun profil Spring, contrairement à `backend/README.md` qui les documente déjà intégralement (lignes 39-51). Pas d'écart supplémentaire détecté entre design et ticket — le périmètre restreint proposé par l'architecte est cohérent avec les trois critères d'acceptation.
- Point d'attention pour la review : le paragraphe ajouté ne doit pas laisser entendre que le changement de profil modifie aujourd'hui le comportement réel de connexion à la base (le datasource reste désactivé via `spring.autoconfigure.exclude` dans `application.yml` lignes 12-13, cf. ticket #1) — rester strictement sur la configuration/documentation, pas sur un comportement fonctionnel non implémenté.
