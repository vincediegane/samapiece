# Design — Ticket #77 : Déploiement de la stack complète sur Render (démo/suivi client)

**Cadrage impératif de ce bolt** : ce pipeline ne dispose d'aucun compte Render, ne facture rien et
n'exécute aucun déploiement réel. Le livrable est uniquement du code/config versionné (Blueprint
`render.yaml`, ajustements de config, documentation). Le "Deploy Blueprint" effectif sur Render,
l'entrée manuelle des secrets dans le dashboard, et la vérification des critères d'acceptation qui
supposent une URL en ligne (health check 200, parcours fonctionnel bout-en-bout, comptes réellement
joignables) restent une étape humaine documentée comme prochaine action, jamais présentée comme faite.

## Approche

On adapte l'existant (`docker-compose.yml`, profil `staging`, proxy nginx du ticket #57, bootstrap
admin du ticket #58) plutôt que de recréer une configuration Render ad hoc, pour rester cohérent avec
les noms de variables déjà consommés par le backend. Le Blueprint `render.yaml` décrit 7 services :
`backend` et `frontend` en Web Service Docker, `postgres` et `redis` en services managés Render, et
`meilisearch`/`minio`/`rabbitmq` en "Private Services" Docker avec disque persistant — miroir exact du
`docker-compose.yml`, seuls les noms d'hôtes changent (`*.internal`/nom de service Render au lieu des
noms `docker-compose`). Le frontend reste servi par nginx qui proxifie `/api/*` vers le backend en
interne (même mécanisme que #57), ce qui évite d'introduire `VITE_API_BASE_URL` et du CORS pour ce
ticket — le prix est que `nginx.conf.template`/`Dockerfile` doivent être rendus paramétrables sur le
nom d'hôte du backend (aujourd'hui codé en dur `backend`), ce qui n'était pas nécessaire tant que
docker-compose fixait ce nom. Constat important fait en explorant le code (voir "Décisions clés") :
`application-staging.yml` n'a en réalité jamais été complété au fil des tickets #17/#19/#22 qui ont
ajouté Meilisearch/Redis/rate-limiting — le profil `staging` est aujourd'hui **non fonctionnel en
l'état** (échec de démarrage certain), ce qui doit être corrigé dans ce ticket pour que Render puisse
seulement démarrer l'application.

## Fichiers/modules impactés

- `render.yaml` (nouveau, racine) — Blueprint Render : services `samapiece-backend` (web, docker,
  `backend/Dockerfile`), `samapiece-frontend` (web, docker, `frontend/Dockerfile`), `samapiece-db`
  (postgres managé), `samapiece-redis` (key-value managé), `samapiece-meilisearch`,
  `samapiece-minio`, `samapiece-rabbitmq` (private services docker, image officielle/pinnée + disque),
  plus un `envVarGroup` pour les secrets (`sync: false`, jamais de valeur en clair dans le fichier).
- `backend/src/main/resources/application-staging.yml` (modifié) — ajout des blocs manquants
  (`spring.data.redis.host/port`, `samapiece.meilisearch.host/api-key/index-pieces`), aujourd'hui
  absents alors que `MeilisearchProperties` (`@NotBlank` sur `host`/`apiKey`/`indexPieces`,
  `backend/src/main/java/sn/samapiece/recherche/MeilisearchProperties.java`) les exige au démarrage —
  sans cet ajout, tout déploiement en profil `staging` échoue à l'initialisation du contexte Spring
  (validation `@ConfigurationProperties`), avant même d'atteindre Render. `application-prod.yml`
  souffre du même manque mais n'est pas dans le périmètre de ce ticket (pas de prod Render visée) —
  signalé en risque, non corrigé ici pour ne pas élargir le diff sans besoin.
- `frontend/nginx.conf.template` (modifié) — `proxy_pass http://backend:${SERVER_PORT}/api/;` devient
  `proxy_pass http://${BACKEND_HOST}:${SERVER_PORT}/api/;` (le nom `backend` est spécifique à
  docker-compose ; sur Render le nom d'hôte interne du service backend est différent et doit être
  injectable).
- `frontend/Dockerfile` (modifié) — la ligne `ENV NGINX_ENVSUBST_FILTER='^SERVER_PORT$'` doit couvrir
  aussi la nouvelle variable (sinon le littéral `${BACKEND_HOST}` resterait tel quel dans la conf nginx
  générée — régression silencieuse du même type que celle déjà documentée dans
  `docs/bolts/57-frontend-sans-proxy-api-backend/design.md`).
- `docker-compose.yml` (modifié a minima) — ajout de `BACKEND_HOST: backend` dans
  `environment:` du service `frontend`, pour que le comportement local docker-compose reste identique
  après la paramétrisation du template (sinon régression locale).
- `.env.example` (modifié) — ajout de `BACKEND_HOST=backend` (valeur par défaut docker-compose),
  documentée comme spécifique à Render dans `render.yaml`.
- `README.md` (racine) — nouvelle section "Déploiement sur Render (démo)" : lien vers `render.yaml`,
  procédure de déploiement initial (Blueprint puis renseigner l'envVarGroup de secrets manuellement dans
  le dashboard Render puis déployer), procédure de redéploiement (push sur `main` et clic "Manual Deploy",
  puisque la CI/CD automatique est hors périmètre), comment lancer les migrations Flyway (elles
  tournent automatiquement au démarrage du service `backend`, comme en local — pas de commande
  séparée : le seul geste manuel est de vérifier les logs Render du service après déploiement), et la
  procédure de bootstrap admin incluant l'étape manuelle de création d'une `Region`/un `Poste` (voir
  Risques) avant d'activer `BOOTSTRAP_ADMIN_ENABLED`.
- Aucun autre fichier backend (contrôleurs, services, migrations Flyway) n'est modifié — les
  9 fichiers `*Api.ts` du frontend qui font déjà des appels `fetch` relatifs vers `/api/v1/...` ne
  changent pas.

## Décisions clés

1. **Frontend = Web Service Docker (pas Static Site) qui garde le proxy nginx `/api`**, plutôt
   qu'introduire `VITE_API_BASE_URL` : préserve l'architecture posée par #57 (chemins relatifs, pas de
   CORS à gérer côté `SecurityConfig`), et limite le diff à 2 fichiers déjà identifiés
   (`nginx.conf.template`, `Dockerfile`) au lieu de toucher 9 fichiers `*Api.ts` plus `SecurityConfig`.
   Prix : frontend et backend doivent être déployés dans la même région Render (le nom d'hôte interne
   n'est résolvable qu'au sein du même réseau privé régional) — à vérifier explicitement lors du
   déploiement manuel.
2. **Meilisearch/MinIO/RabbitMQ en "Private Service" Docker, images identiques à `docker-compose.yml`**
   (`getmeili/meilisearch:v1.10`, `bitnamilegacy/minio` épinglé par digest avec `user: root` — cf.
   commentaire dédié dans `docker-compose.yml` sur l'indisponibilité des images MinIO officielles —,
   `rabbitmq:3-management-alpine`), chacun avec un disque Render persistant monté sur le même chemin
   que le volume docker-compose (`/meili_data`, `/data` ; pas de volume RabbitMQ, il n'y en a pas
   aujourd'hui). Décision produit déjà actée dans le ticket : pas de dégradation fonctionnelle, donc
   ces 3 services payants sont inclus malgré le coût (souligné explicitement en risque).
3. **Postgres et Redis en services managés Render** plutôt qu'en Private Service Docker : ce sont les
   deux seuls services pour lesquels Render propose une offre managée, cohérent avec la contrainte
   technique du corps du ticket. Leurs identifiants (host/port/mot de passe) sont exposés par Render
   lui-même à l'exécution via des références de service dans `render.yaml`, pas définis en dur.
4. **Secrets exclusivement via un envVarGroup non synchronisé** (mot de passe DB généré par Render,
   `JWT_SECRET`, `PHOTO_CLE_CHIFFREMENT`, `ALERTE_CLE_CHIFFREMENT`, `SMS_API_KEY`, `MEILI_MASTER_KEY`,
   identifiants MinIO/RabbitMQ) : aucune valeur, y compris de démo, n'est committée — reprend
   exactement la liste de `.env.example` et la posture déjà actée au ticket #27
   (`docs/securite/gestion-secrets-et-revue-historique.md`). Seules les valeurs non sensibles
   (`SPRING_PROFILES_ACTIVE=staging`, noms d'hôtes internes, `MINIO_BUCKET_PHOTOS`,
   `MEILISEARCH_INDEX_PIECES`) sont écrites en clair dans `render.yaml`.
5. **Bootstrap admin activé (`BOOTSTRAP_ADMIN_ENABLED=true`) via l'envVarGroup**, mais avec la
   contrainte déjà documentée au ticket #58 (`docs/bolts/58-bootstrap-premier-compte-admin/design.md`,
   section Risques) : `BOOTSTRAP_ADMIN_POSTE_ID` doit référencer un `Poste` existant
   (`AdminBootstrapRunner.resoudrePosteId`,
   `backend/src/main/java/sn/samapiece/iam/AdminBootstrapRunner.java`) et aucune migration Flyway ne
   crée de `Region`/`Poste` (`V1__create_region_poste.sql` ne fait que créer les tables, aucun insert).
   Décision : documenter dans le README une étape manuelle (connexion à la base Render via psql ou le
   Shell Render, un insert direct d'une `Region` plus un `Poste` de démo) à exécuter avant le premier
   démarrage avec le bootstrap activé — pas de seed Flyway ajouté ici, pour ne pas insérer
   silencieusement des données métier arbitraires dans un environnement qui pourrait un jour être
   promu (hors périmètre explicite de ce ticket).
6. **Reprise du profil `staging` existant, complété plutôt que remplacé** par un nouveau profil
   `render` : cohérent avec la demande explicite du corps du ticket ("utilisation du profil Spring
   `staging` déjà existant... comme base, ajusté si nécessaire").

## Risques / points d'attention

- **`application-staging.yml` est aujourd'hui non fonctionnel** (confirmé par lecture du code, pas
  supposé) : sans les ajouts de ce ticket, `MeilisearchProperties` fait échouer le démarrage du
  contexte Spring dès l'activation du profil `staging`, indépendamment de Render. À vérifier par un
  démarrage local avec `SPRING_PROFILES_ACTIVE=staging` avant tout déploiement.
- **TLS sur Redis managé Render** : l'offre "Key Value" de Render impose potentiellement TLS même en
  interne selon le plan — le code actuel (`RedisRateLimiterConfig`,
  `backend/src/main/java/sn/samapiece/recherche/securite/RedisRateLimiterConfig.java`, et
  l'auto-configuration `spring-boot-starter-data-redis`) ne configure aucune option TLS aujourd'hui. Ce
  point ne peut pas être tranché sans accès réel à Render (hors périmètre de ce bolt) — à valider
  explicitement lors du déploiement manuel ; si le TLS est requis, `application-staging.yml` devra être
  complété dans une itération ultérieure.
- **Redis "fail-open" par conception** (`management.health.redis.enabled=false`, commentaire dans
  `application.yml`) : le rate limiting/CAPTCHA de la recherche publique dégrade silencieusement en cas
  de panne Redis — choix produit déjà acté au ticket #19, pas une régression de ce ticket, mais à
  rappeler au client/reviewer car le critère d'acceptation "pas de fallback silencieux" du ticket #77
  vise avant tout Postgres/Meilisearch/MinIO/RabbitMQ (dont le backend dépend réellement pour
  fonctionner), pas ce mécanisme de dégradation volontaire.
- **Poste/Région manquants pour le bootstrap admin** (détaillé en Décisions clés) : sans l'étape
  manuelle documentée, `AdminBootstrapRunner` lève une exception au démarrage et le service `backend`
  ne devient jamais sain sur Render — risque concret de bloquer tout le déploiement si l'étape est
  oubliée.
- **Coût des 3 Private Services Docker** (Meilisearch, MinIO, RabbitMQ) : facturation continue à
  l'heure, pas de tier gratuit pérenne — signalé explicitement dans le corps du ticket, à faire valider
  par l'utilisateur avant de cliquer "Deploy Blueprint" (décision de facturation hors périmètre de ce
  pipeline).
- **Plan Postgres/Redis Render** : les tiers gratuits Render pour Postgres ont une durée de vie limitée
  (suppression automatique après un délai fixe) — incompatible avec l'objectif du ticket ("URL publique
  stable, à jour au fil des merges"). Le choix du plan payant est une décision humaine/budgétaire, à
  documenter comme prérequis dans le README plutôt qu'à trancher ici.
- **Environnement démo, pas production souveraine** : `PROJET-SAMAPIECE.md` §11.1/§11.5 fixe
  l'hébergement principal visé sur un datacenter ADIE/cloud souverain sénégalais pour la production.
  Render (PaaS non-souverain) est cohérent avec le cadrage du ticket #77 (démo/suivi client, pas
  remplacement de la cible §11.5), mais implique de ne jamais saisir de données personnelles citoyennes
  réelles sur cet environnement, seulement des données de démonstration synthétiques. À rappeler
  explicitement dans le README pour éviter toute ambiguïté côté client.
- **Ordonnancement de démarrage** : `docker-compose.yml` utilise `depends_on`/`condition:
  service_healthy` pour séquencer `backend` après `minio`/`meilisearch`/`rabbitmq`, et `frontend` après
  `backend`. Le Blueprint Render n'offre pas la même garantie d'ordonnancement de démarrage entre
  services — un premier déploiement peut nécessiter un redémarrage manuel du service `backend` si les
  services annexes ne sont pas encore prêts à son premier boot. À documenter comme point de vigilance,
  pas comme bug à corriger dans le code.
- **Port d'écoute Docker** : l'hypothèse retenue est que Render détecte le port exposé via `EXPOSE 8080`
  (`backend/Dockerfile`) et `EXPOSE 80` (`frontend/Dockerfile`) pour les services Docker, sans variable
  `PORT` imposée comme sur les runtimes natifs — hypothèse non vérifiable sans accès réel à Render, à
  confirmer lors du déploiement manuel.

## Hors périmètre

- Exécuter le déploiement réel (connecter le repo à un compte Render, valider la facturation, cliquer
  "Deploy Blueprint", vérifier `GET /actuator/health` en 200 sur l'URL publique, dérouler les parcours
  fonctionnels bout-en-bout) : action humaine, hors capacité de ce pipeline.
- CI/CD automatique sur merge `main` (explicitement hors périmètre du ticket).
- Domaine personnalisé (on reste sur les URLs `*.onrender.com`).
- TLS/chiffrement au repos au-delà de ce que Render fournit par défaut (couvert par le ticket #27, à
  revalider dans ce contexte, pas à refaire).
- Ajout d'un mécanisme de seed Flyway pour `Region`/`Poste` (écart déjà identifié et volontairement non
  traité au ticket #58 ; ce ticket documente une étape manuelle, ne construit pas de seed versionné).
- Complétion de `application-prod.yml` (même lacune Meilisearch/Redis que `staging`, mais aucune prod
  Render n'est visée par ce ticket) — à traiter dans un ticket dédié si une vraie prod Render était
  envisagée un jour.
- Trancher la question du TLS sur le Redis managé Render : identifiée comme risque ouvert, à valider
  lors du déploiement manuel, pas par une hypothèse de code non vérifiable ici.
