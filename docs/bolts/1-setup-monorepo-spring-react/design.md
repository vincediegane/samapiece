# Design — Ticket #1 : Initialiser le monorepo (backend Spring Boot + frontend React/Vite)

État constaté du repo (vérifié via `git log`, `ls`, `Glob`) : seuls `PROJET-SAMAPIECE.md`, `README.md`, `.claude/` et `docs/bolts/.gitkeep` existent. Aucun code `backend/` ni `frontend/` — c'est un démarrage à blanc, rien à réutiliser ni à casser.

## Approche

Monorepo avec un `pom.xml` racine de type `pom` déclarant `backend` comme module Maven (packaging `jar` pour le module lui-même), ce qui permet exactement `mvn -pl backend spring-boot:run` depuis la racine (critère d'acceptation). Le frontend n'est pas un module Maven : c'est un projet npm indépendant sous `frontend/`. Un seul module Spring Boot pour l'instant (pas de sous-modules Maven par domaine) : le découpage par bounded context se fait par **packages Java** (`sn.samapiece.<domaine>`), conformément à la §11.3-11.4 qui ne demande la séparation physique en services qu'en cas de besoin démontré plus tard. Côté frontend, scaffold standard Vite + React + TypeScript avec structure de dossiers par feature, sans PWA/offline pour ce ticket (voir Hors périmètre). Ce choix minimise la complexité d'outillage initiale tout en posant des frontières de code claires que les tickets suivants rempliront.

## Fichiers / modules impactés

Tout est à créer (rien n'existe).

**Racine**
- `pom.xml` (aggregator, `packaging=pom`, `<modules><module>backend</module></modules>`)
- Mise à jour de `README.md` (racine) : section conventions de commit et de nommage

**Backend** (`backend/`)
- `backend/pom.xml` (parent `spring-boot-starter-parent`, Java 21)
- `backend/src/main/java/sn/samapiece/SamaPieceApplication.java` (classe `@SpringBootApplication`)
- `backend/src/main/java/sn/samapiece/enregistrement/package-info.java`
- `backend/src/main/java/sn/samapiece/recherche/package-info.java`
- `backend/src/main/java/sn/samapiece/notifications/package-info.java`
- `backend/src/main/java/sn/samapiece/retraitaudit/package-info.java`
- `backend/src/main/java/sn/samapiece/iam/package-info.java`
- `backend/src/main/java/sn/samapiece/reporting/package-info.java`
  (chaque `package-info.java` porte une Javadoc d'une ligne décrivant le bounded context — aucune classe métier, seulement un marqueur de package pour que le dossier existe dans Git et que l'intention du domaine soit documentée)
- `backend/src/main/resources/application.yml` (config commune, active le profil via `SPRING_PROFILES_ACTIVE`, défaut `dev`)
- `backend/src/main/resources/application-dev.yml`
- `backend/src/main/resources/application-staging.yml`
- `backend/src/main/resources/application-prod.yml`
- `backend/src/test/java/sn/samapiece/SamaPieceApplicationTests.java` (test de contexte + test `/actuator/health` via `MockMvc`/`WebTestClient`)
- `backend/README.md`

**Frontend** (`frontend/`)
- `frontend/package.json`, `frontend/vite.config.ts`, `frontend/tsconfig.json`, `frontend/tsconfig.node.json`
- `frontend/index.html`
- `frontend/src/main.tsx`
- `frontend/src/app/App.tsx` (shell applicatif minimal)
- `frontend/src/features/home/HomePage.tsx` (page d'accueil minimale affichée par défaut — premier exemple concret de la convention "un dossier par feature")
- `frontend/eslint.config.js` (config plate ESLint 9)
- `frontend/.prettierrc`
- `frontend/README.md`

Le `.gitignore` racine existant couvre déjà `target/`, `node_modules/`, `dist/`, `.env*` — pas de `.gitignore` supplémentaire nécessaire par module.

## Décisions clés

- **Version Spring Boot** : `3.3.x` (dernière version stable de la branche 3.3 au moment de l'implémentation — à vérifier sur start.spring.io/repo Maven Central par le codeur), compatible Java 21 LTS. Ne pas descendre en dessous de 3.2 (première branche supportant officiellement Java 21).
- **Démarrage sans base de données réelle pour ce ticket** : `spring-boot-starter-data-jpa` est ajouté (dépendance demandée par le ticket) mais **aucune entité/repository n'existe encore** et Flyway n'est pas dans le périmètre de ce ticket. Pour que `mvn -pl backend spring-boot:run` démarre et que `/actuator/health` réponde 200 sans exiger une instance PostgreSQL locale, on exclut explicitement l'autoconfiguration datasource/JPA dans `application.yml` (profil par défaut) :
  `spring.autoconfigure.exclude: org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration`.
  Cette exclusion est **temporaire** et devra être retirée par le premier ticket qui introduit une entité JPA + une migration Flyway réelle (à signaler clairement dans le code, ex. commentaire `// TODO(#<ticket-suivant>)`).
- **Packages de domaine** : `sn.samapiece.{enregistrement,recherche,notifications,retraitaudit,iam,reporting}`, orthographe reprise telle quelle du ticket (à noter : §11.3 du document produit utilise `retrait_audit` avec underscore — Java n'autorise pas l'underscore en début/segment de package de façon idiomatique ; `retraitaudit` du ticket est la forme retenue et fait foi ici).
- **Config par environnement** : trois fichiers `application-{dev,staging,prod}.yml`, aucun secret en dur — tout via variables d'environnement (`${DB_HOST}`, `${DB_PORT}`, `${DB_NAME}`, `${DB_USER}`, `${DB_PASSWORD}`, etc.) avec valeurs par défaut uniquement pour les paramètres non sensibles (ex. port applicatif).
- **Frontend** : scaffold `npm create vite@latest frontend -- --template react-ts`, Node 20 LTS, gestionnaire npm (cohérent avec `npm run dev` du ticket). ESLint 9 (flat config) + `typescript-eslint` + `eslint-plugin-react-hooks` + `eslint-config-prettier`, Prettier séparé (pas de plugin ESLint-Prettier qui ralentit le lint) — config standard, sans personnalisation exotique.
- **Structure frontend par feature** : `src/app/` (shell, routing futur), `src/features/<feature>/` (un dossier par fonctionnalité), pas de dossier `shared/`/`components/` vide créé maintenant — sera ajouté par le premier ticket qui en a réellement besoin (éviter les dossiers vides sans contenu, non trackés par Git).
- **Convention de commit/nommage** : documentée dans le `README.md` racine (le ticket dit "dans README.md" sans préciser lequel ; la racine est le point d'entrée le plus logique). Les README de `backend/` et `frontend/` restent focalisés sur "comment lancer/tester localement" (critère d'acceptation explicite par module).

## Risques / points d'attention

- **Health check trompeur** : avec l'exclusion datasource ci-dessus, `/actuator/health` répondra `UP` même quand la vraie base sera indisponible plus tard si l'exclusion n'est pas retirée à temps — risque d'angle mort si un ticket futur oublie de la lever en ajoutant Flyway/JPA. À documenter explicitement en commentaire dans `application.yml`.
- **Divergence de nommage de package** (`retraitaudit` vs `retrait_audit` dans le document produit) : décision assumée ci-dessus, mais à signaler au spec-writer/reviewer pour éviter une incohérence future si quelqu'un se base sur le document produit sans relire ce design.
- **Version exacte Spring Boot/Vite non figée** : ce document fixe une branche (`3.3.x` / Vite 5.x) mais pas un patch précis, car la connaissance de la dernière version publiée peut être dépassée ; le codeur doit vérifier la dernière version stable réelle au moment de l'implémentation plutôt que de recopier un numéro obsolète.
- **`mvn -pl backend spring-boot:run` depuis la racine** dépend strictement de la présence du `pom.xml` racine agrégateur — sans lui la commande échoue même si `backend/pom.xml` est correct.
- **Aucun secret en dur** : vérifier en revue qu'aucune valeur de `application-prod.yml` (ou autre) ne contient un mot de passe/URL de secret réel, même un exemple "réaliste".

## Hors périmètre

- Toute logique métier (entités JPA, repositories, services, contrôleurs applicatifs autres que ceux fournis automatiquement par `spring-boot-starter-actuator`).
- Migrations Flyway et schéma de base de données réel (ce ticket n'ajoute pas la dépendance Flyway).
- Authentification/autorisation effective (Spring Security est ajouté comme dépendance mais sans configuration de règles d'accès autres que celles par défaut du starter).
- PWA / offline-first / service worker / IndexedDB pour le frontend agent (prévu par la §11.4/11.7 du document produit, mais pour un ticket ultérieur — celui-ci ne livre qu'un scaffold Vite+React+TS classique avec une page d'accueil statique).
- Docker/`docker-compose`, CI/CD, Meilisearch, Redis, MinIO, SMS gateway — aucun de ces éléments n'est requis par les critères d'acceptation de ce ticket.
- Tout endpoint applicatif autre que `/actuator/health`.
