# Spec — Ticket #1 : Initialiser le monorepo (backend Spring Boot + frontend React/Vite)

## Résumé

Créer le squelette du monorepo (aggregator Maven + module `backend` Spring Boot 3/Java 21 avec packages par domaine, `frontend` React/Vite/TypeScript scaffoldé, et READMEs) de sorte que `mvn -pl backend spring-boot:run` expose `/actuator/health` (200) et `npm run dev` affiche une page d'accueil minimale, sans aucun secret en dur.

## Tâches

- [ ] `pom.xml` (racine) : créer l'aggregator Maven (`groupId=sn.samapiece`, `artifactId=samapiece-parent`, `version=0.1.0-SNAPSHOT`, `packaging=pom`, `<modules><module>backend</module></modules>`). Sans ce fichier, `mvn -pl backend spring-boot:run` échoue depuis la racine.
- [ ] `backend/pom.xml` : module Maven `backend` (packaging `jar`), parent `org.springframework.boot:spring-boot-starter-parent`, `<java.version>21</java.version>`, dépendances `spring-boot-starter-web`, `spring-boot-starter-security`, `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`, `spring-boot-starter-actuator` (nécessaire à `/actuator/health`, non listée explicitement dans le ticket mais requise par le critère d'acceptation — voir **Écarts identifiés**), `spring-boot-starter-test` (scope `test`) et `spring-security-test` (scope `test`) ; plugin `spring-boot-maven-plugin`.
- [ ] `backend/src/main/java/sn/samapiece/SamaPieceApplication.java` : classe `@SpringBootApplication` avec `public static void main(String[] args)`.
- [ ] `backend/src/main/java/sn/samapiece/enregistrement/package-info.java` : Javadoc « Cycle de vie de création/mise à jour des fiches pièces (déclaration des pièces retrouvées). » sur le package.
- [ ] `backend/src/main/java/sn/samapiece/recherche/package-info.java` : Javadoc « Indexation et requêtes de recherche publique, avec anonymisation des résultats. »
- [ ] `backend/src/main/java/sn/samapiece/notifications/package-info.java` : Javadoc « Gestion des alertes, files d'attente et intégration passerelle SMS/push/email. »
- [ ] `backend/src/main/java/sn/samapiece/retraitaudit/package-info.java` : Javadoc « Validation des retraits et journal d'audit immuable. Correspond au domaine `retrait_audit` de PROJET-SAMAPIECE.md §11.3 (voir Écarts identifiés pour la divergence de nommage). »
- [ ] `backend/src/main/java/sn/samapiece/iam/package-info.java` : Javadoc « Gestion des comptes, rôles, authentification et habilitations. »
- [ ] `backend/src/main/java/sn/samapiece/reporting/package-info.java` : Javadoc « Agrégations statistiques, exports et tableaux de bord. »
- [ ] `backend/src/main/resources/application.yml` : config commune (voir structure exacte dans **Contrat technique**), y compris l'exclusion temporaire de l'autoconfiguration datasource/JPA et le commentaire `TODO` associé.
- [ ] `backend/src/main/resources/application-dev.yml` : config profil `dev` (valeurs par défaut non sensibles autorisées, ex. `localhost`).
- [ ] `backend/src/main/resources/application-staging.yml` : config profil `staging` (aucune valeur par défaut pour host/port/name/credentials — tout via variable d'environnement).
- [ ] `backend/src/main/resources/application-prod.yml` : config profil `prod` (idem staging, aucun défaut).
- [ ] `backend/src/test/java/sn/samapiece/SamaPieceApplicationTests.java` : test `contextLoads()` + test `healthEndpoint_shouldReturn200()` via `MockMvc` sur `GET /actuator/health` (voir **Contrat technique** pour le comportement de sécurité attendu et le fallback si le test échoue en 401).
- [ ] `backend/README.md` : prérequis (JDK 21, Maven), commande de lancement (`mvn -pl backend spring-boot:run` depuis la racine, ou `mvn spring-boot:run` depuis `backend/`), commande de test (`mvn -pl backend test`), profils disponibles et variable `SPRING_PROFILES_ACTIVE`.
- [ ] Scaffold frontend : exécuter `npm create vite@latest frontend -- --template react-ts` depuis la racine, puis `npm install` dans `frontend/` (génère `frontend/package.json`, `frontend/vite.config.ts`, `frontend/tsconfig.json`, `frontend/tsconfig.node.json`, `frontend/index.html`, `frontend/src/main.tsx`, `frontend/src/App.tsx`, `frontend/src/vite-env.d.ts`, etc. — base du template, avant les ajustements des tâches suivantes).
- [ ] `frontend/package.json` : ajuster `name` (`samapiece-frontend`), vérifier/compléter les scripts `dev`, `build`, `preview`, `lint` (`eslint .`), `format` (`prettier --write .`), `format:check` (`prettier --check .`) ; ajouter les devDependencies ESLint/Prettier listées dans **Contrat technique**.
- [ ] `frontend/eslint.config.js` : remplacer la config générée par le template par une config plate ESLint 9 avec `typescript-eslint`, `eslint-plugin-react-hooks`, et `eslint-config-prettier` en dernier élément du tableau (désactive les règles de style en conflit avec Prettier — pas de plugin `eslint-plugin-prettier`).
- [ ] `frontend/.prettierrc` : config Prettier standard (voir **Contrat technique** pour les valeurs exactes).
- [ ] `frontend/index.html` : mettre à jour `<title>` en « SamaPièce ».
- [ ] `frontend/src/main.tsx` : point d'entrée React, monte `<App />` dans `#root` (issu du template, vérifier import de `App` depuis `./app/App` après déplacement — voir tâche suivante).
- [ ] Supprimer `frontend/src/App.tsx`, `frontend/src/App.css` et `frontend/src/assets/` générés par le template (non utilisés par la structure par feature retenue) et créer `frontend/src/app/App.tsx` : shell applicatif minimal qui rend `<HomePage />` (pas de routing dans ce ticket, un seul point d'entrée).
- [ ] `frontend/src/features/home/HomePage.tsx` : composant fonctionnel affichant un titre « SamaPièce » et une phrase d'accroche courte (ex. « Retrouvez une pièce d'identité perdue au Sénégal. ») — page d'accueil minimale statique, sans appel API.
- [ ] `frontend/README.md` : prérequis (Node 20 LTS, npm), commande de lancement (`npm install` puis `npm run dev` depuis `frontend/`), commande de lint/format (`npm run lint`, `npm run format:check`), commande de build (`npm run build`) ; mentionner l'absence de tests automatisés pour ce ticket de scaffolding et le renvoi à un ticket ultérieur pour Vitest/Testing Library.
- [ ] `README.md` (racine) : ajouter une section « Conventions » avec convention de nommage des commits (ex. Conventional Commits : `feat:`, `fix:`, `chore:`, `docs:`, `test:`, `refactor:` + référence optionnelle au numéro de ticket, ex. `feat(backend): ajoute le endpoint X (#12)`) et convention de nommage de fichiers/packages (packages Java `sn.samapiece.<domaine>` en minuscules sans séparateur, dossiers frontend `src/features/<feature-kebab-case>/`).
- [ ] Vérification manuelle finale (pas de fichier à modifier) : lancer `mvn -pl backend spring-boot:run` depuis la racine et confirmer `curl http://localhost:8080/actuator/health` → 200 ; lancer `npm run dev` dans `frontend/` et confirmer l'affichage de la page d'accueil sur `http://localhost:5173` ; relire tous les fichiers `application*.yml` et `frontend/.env*` (s'il en existe) pour confirmer l'absence de secret en dur.

## Contrat technique

**Versions cibles** (branche fixée par le design ; le codeur DOIT vérifier sur start.spring.io / Maven Central / npm registry la dernière version corrective disponible dans la même branche mineure au moment de l'implémentation et l'utiliser à la place si plus récente — ne pas descendre sous la branche indiquée) :
- Java : 21 (LTS)
- Spring Boot : `3.3.x`, valeur plancher `3.3.5`
- Node.js : `20.x` LTS, minimum `20.11.0`
- npm : celui livré avec Node 20 (pas de yarn/pnpm)
- Vite : `5.x` (via `npm create vite@latest -- --template react-ts`, laisser le template résoudre le patch exact)
- React : `18.x` (résolu par le template Vite `react-ts`)
- TypeScript : `5.x` (résolu par le template)
- ESLint : `9.x` (flat config), `typescript-eslint` `8.x`, `eslint-plugin-react-hooks` dernière version compatible React 18, `eslint-config-prettier` `9.x`
- Prettier : `3.x`

**`backend/src/main/resources/application.yml`** (clés exactes) :
```yaml
spring:
  application:
    name: samapiece-backend
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:dev}
  # TODO(#<ticket-suivant-JPA-Flyway>) : retirer cette exclusion dès qu'une entité JPA
  # et une migration Flyway réelles sont introduites. Sans elle, "mvn -pl backend
  # spring-boot:run" échoue au démarrage faute de datasource configurée, car aucune
  # base de données n'est requise pour ce ticket de scaffolding.
  # ATTENTION : tant que cette exclusion est active, /actuator/health répond UP même
  # si une base réelle configurée par ailleurs serait inaccessible (health check trompeur).
  autoconfigure:
    exclude: org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration

server:
  port: ${SERVER_PORT:8080}

management:
  endpoints:
    web:
      exposure:
        include: health
  endpoint:
    health:
      show-details: never
```

**`application-dev.yml`** (defaults non sensibles autorisés) :
```yaml
spring:
  # Bloc inactif tant que l'exclusion datasource ci-dessus est en place (scaffold
  # pour le ticket qui introduira JPA/Flyway).
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:samapiece_dev}
    username: ${DB_USER}
    password: ${DB_PASSWORD}

logging:
  level:
    sn.samapiece: DEBUG
```

**`application-staging.yml`** et **`application-prod.yml`** (aucun défaut, même structure, seul `logging.level.sn.samapiece` passe à `INFO`) :
```yaml
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}
    username: ${DB_USER}
    password: ${DB_PASSWORD}

logging:
  level:
    sn.samapiece: INFO
```

**Endpoint exposé** : `GET /actuator/health` uniquement (via `management.endpoints.web.exposure.include: health` ci-dessus). Aucun autre endpoint applicatif dans ce ticket.

**Sécurité de `/actuator/health`** : aucune classe `SecurityFilterChain` custom n'est créée dans ce ticket — on s'appuie sur le comportement par défaut de Spring Boot Actuator + Security (`ManagementWebSecurityAutoConfiguration`), qui autorise l'accès non authentifié à `/actuator/health` et exige une authentification pour tout autre endpoint de gestion. **Si le test `healthEndpoint_shouldReturn200()` échoue avec 401**, ajouter `backend/src/main/java/sn/samapiece/SecurityConfig.java` avec un bean `SecurityFilterChain` minimal :
```java
http.authorizeHttpRequests(auth -> auth
    .requestMatchers("/actuator/health").permitAll()
    .anyRequest().authenticated());
```
et documenter par un commentaire que cette classe sera complétée par le ticket IAM.

**Structure des dossiers frontend** :
```
frontend/
  index.html
  package.json
  vite.config.ts
  tsconfig.json
  tsconfig.node.json
  eslint.config.js
  .prettierrc
  README.md
  src/
    main.tsx
    app/
      App.tsx
    features/
      home/
        HomePage.tsx
```
Pas de dossier `shared/` ou `components/` vide créé dans ce ticket (sera ajouté par le premier ticket qui en a réellement besoin).

**`.prettierrc`** (valeurs exactes) :
```json
{
  "semi": true,
  "singleQuote": true,
  "trailingComma": "all",
  "printWidth": 100
}
```

## Plan de tests

| Critère d'acceptation (ticket #1) | Test |
|---|---|
| `mvn -pl backend spring-boot:run` démarre un backend exposant `/actuator/health` qui répond 200. | Automatisé : `backend/src/test/java/sn/samapiece/SamaPieceApplicationTests.java` — `contextLoads()` (le contexte Spring démarre sans erreur) et `healthEndpoint_shouldReturn200()` (`MockMvc.perform(get("/actuator/health")).andExpect(status().isOk())`). Complété par une vérification manuelle : lancer `mvn -pl backend spring-boot:run` depuis la racine et `curl -i http://localhost:8080/actuator/health` → doit répondre `200`. |
| `npm run dev` dans `frontend/` démarre un serveur de dev Vite affichant une page d'accueil minimale. | Manuel : lancer `npm install && npm run dev` dans `frontend/`, ouvrir `http://localhost:5173`, vérifier l'affichage du contenu de `HomePage.tsx` sans erreur dans la console navigateur. Pas de test automatisé pertinent pour ce ticket (aucun framework de test frontend n'est ajouté au périmètre — voir design, Hors périmètre) ; `npm run build` et `npm run lint` doivent aussi passer sans erreur (vérification manuelle en local). |
| Un `README.md` de chaque module explique comment lancer/tester localement. | Manuel : relecture de `backend/README.md` et `frontend/README.md`, vérifier que chacun documente prérequis, commande de lancement et commande de test/lint. |
| Aucun secret en dur dans le code (config via variables d'environnement). | Manuel/revue : relire `backend/src/main/resources/application*.yml` et confirmer que `username`/`password`/toute valeur sensible utilisent `${VAR}` sans valeur par défaut (seuls `host`/`port`/`name` en profil `dev` ont un défaut non sensible) ; grep rapide (`grep -rniE "password|secret|token" backend/src/main/resources frontend/src`) pour repérer toute valeur suspecte codée en dur avant de committer. |

## Écarts identifiés

- **Nommage de package `retraitaudit` vs `retrait_audit`** (repris tel quel du design, non retranché ici) : le ticket #1 et PROJET-SAMAPIECE.md §11.3 utilisent `retrait_audit` avec underscore, mais le design retient `retraitaudit` (sans underscore, convention Java idiomatique) comme forme faisant foi pour ce ticket. Le `package-info.java` du package `retraitaudit` porte un commentaire de traçabilité vers `retrait_audit` (§11.3) pour éviter toute confusion future.
- **Dépendance `spring-boot-starter-actuator` non listée explicitement par le ticket** : le corps du ticket #1 énumère « Spring Web/Security/Data JPA/Validation » comme dépendances à ajouter, sans mentionner Actuator — pourtant le critère d'acceptation exige `/actuator/health`, qui nécessite cette dépendance. Le design la mentionne implicitement (section Hors périmètre : « … autres que ceux fournis automatiquement par spring-boot-starter-actuator »). Cette spec l'ajoute explicitement à `backend/pom.xml` pour lever toute ambiguïté ; ce n'est pas un écart de décision, seulement une clarification d'un point sous-spécifié par le ticket.
- **Comportement de sécurité par défaut de `/actuator/health`** : ce ticket n'ajoute aucune configuration Spring Security explicite (conforme au Hors périmètre du design). Le contrat technique ci-dessus documente l'hypothèse retenue (accès non authentifié à `/actuator/health` via le comportement par défaut de `ManagementWebSecurityAutoConfiguration`) et fournit un fallback de code si cette hypothèse s'avère fausse à l'implémentation, afin que le codeur n'ait pas à deviner en cas d'échec du test.
- **Versions exactes non figées** (risque déjà signalé par le design, repris sans le retrancher) : les versions listées dans le Contrat technique ci-dessus sont des valeurs planchers de la branche décidée par le design (Spring Boot 3.3.x, Node 20 LTS, Vite 5.x) ; le codeur doit vérifier la dernière version corrective réellement disponible au moment de l'implémentation plutôt que de recopier ces numéros tels quels.
