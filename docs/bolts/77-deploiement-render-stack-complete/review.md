# Review — Ticket #77 : Déploiement de la stack complète sur Render (démo/suivi client)

CHANGES_REQUESTED

## Contexte de la revue


Vérification effectuée sur la branche `bolt/issue-77-deploiement-render-stack-complete` (3 commits :
`08e1acb`, `61e791b`, `0054643`) contre `main`, avec lecture intégrale de `spec.md`/`design.md`,
`git diff`/`git log -p`, et exécution locale réelle (Docker Desktop disponible sur cette machine) :
- `python -c "import yaml..."` sur `render.yaml` → YAML valide.
- `docker compose up -d --build` (stack complète, `.env` généré depuis `.env.example` + clés AES/JWT
  base64 valides générées via `openssl rand -base64 32`) → tous les services `healthy`, puis
  `curl -i http://localhost:8081/api/v1/postes` → `200`, `Content-Type: application/json`, corps `[]`.
  Inspection du fichier nginx généré dans le conteneur frontend
  (`/etc/nginx/conf.d/default.conf`) : `proxy_pass http://backend:8080/api/;` — confirme que
  `NGINX_ENVSUBST_FILTER='^(SERVER_PORT|BACKEND_HOST)$'` substitue bien les deux variables (aucun
  littéral `${BACKEND_HOST}` résiduel). Non-régression confirmée.
- Démarrage direct du conteneur `samapiece-backend:dev` (profil `staging`, réseau `samapiece_default`,
  hostnames internes `postgres`/`redis`/`meilisearch`/`minio`/`rabbitmq`) avec exactement le jeu de
  variables d'environnement déclaré par `render.yaml` pour le service `samapiece-backend` (pas les
  valeurs de confort de `.env`) : démarre avec succès, `GET /actuator/health` retourne le statut UP.
  Migrations Flyway (12) appliquées automatiquement au démarrage, confirmant le paragraphe "Flyway
  automatique" du README.
- `mvn -pl backend test` (exécuté deux fois pour confirmer) : 223 tests, 0 failure, 24 erreurs.
  Chaque classe en erreur (SamaPieceApplicationTests, AlerteIntegrationTest,
  AuditEndpointIntegrationTest, PieceIntegrationTest, AgentIntegrationTest,
  RecherchePubliqueRateLimitingIntegrationTest, etc. — 24 au total) échoue avec la même cause racine :
  IllegalStateException "Previous attempts to find a Docker environment failed" et
  "Can't get Docker image: RemoteDockerImage(imageName=postgres:16-alpine...)" levée par Testcontainers
  au bootstrap du contexte Spring, avant toute exécution de test métier. Vérifications croisées :
  aucune de ces 24 classes n'est nouvelle dans ce diff (git diff main --stat ne montre aucun fichier
  sous src/test modifié) ; grep sur "ActiveProfiles(staging)" dans src/test/java retourne 0 résultat
  (aucun test n'active le profil modifié par ce ticket). C'est bien un problème d'intégration
  Testcontainers/Docker Desktop sur cette machine Windows, alors que le CLI docker fonctionne
  parfaitement en dehors de Testcontainers (comme démontré par tous les docker run/docker compose de
  cette revue), indépendant du diff. L'affirmation du codeur est confirmée, pas seulement crue sur
  parole.
- Aucune trace, dans les 3 commits ni dans les outils utilisés, d'un accès réseau vers un compte ou
  une API Render réelle : périmètre "pas de déploiement réel" respecté.

## Critères d'acceptation

| Critère (ticket #77) | Statut | Commentaire |
|---|---|---|
| Health check backend 200 en ligne | Non applicable à ce bolt (documenté) | Hors de portée sans compte Render, correctement acté comme étape manuelle dans spec.md/design.md/README. Health check 200 en local avec le jeu de variables de render.yaml : vérifié par moi-même, OK. |
| Parcours fonctionnel bout-en-bout | Non applicable à ce bolt (documenté) | README ne prétend jamais qu'un parcours réel a été vérifié en ligne. |
| Tous les services réellement utilisés (pas de fallback silencieux) | Partiel | render.yaml déclare et injecte bien Postgres/Redis/Meilisearch/MinIO/RabbitMQ au backend, cohérent avec docker-compose.yml. L'écart Redis fail-open (#19) est correctement documenté comme risque assumé hors périmètre. Mais SMS_SENDER_ID est absent de render.yaml — voir Finding 1. |
| Premier compte admin utilisable dès le déploiement initial | Couvert (dans la limite du vérifiable) | AdminBootstrapRunner non modifié, BOOTSTRAP_ADMIN_ENABLED/_MATRICULE/_NOM/_POSTE_ID bien injectés en sync:false. Procédure d'insertion manuelle Région/Poste correctement documentée et référencée. Vérification en ligne réelle explicitement hors périmètre, documentée comme telle. |
| Aucun secret en clair dans render.yaml | Couvert | Relecture exhaustive et grep ciblé : toutes les valeurs sensibles utilisent sync:false. Les seules valeurs en clair (MINIO_BUCKET_PHOTOS, MEILISEARCH_INDEX_PIECES, noms d'hôtes internes, MEILI_NO_ANALYTICS) sont non sensibles, cohérent avec le design. |
| Procédure documentée | Couvert | README distingue clairement prêt techniquement et reste à déployer manuellement, ne revendique jamais un déploiement réel. Hypothèses de schéma Render marquées comme telles dans render.yaml (commentaires) et reprises dans le README, jamais présentées comme certaines. |
| Non-régression locale (docker-compose, application-staging.yml) | Couvert | Vérifié moi-même de bout en bout (voir Contexte) : proxy nginx, démarrage staging, health check, migrations Flyway. |

## Findings

1. Priority: Should-fix avant merge — render.yaml omet SMS_SENDER_ID, une variable requise sans
   défaut (render.yaml, bloc envVars du service samapiece-backend, lignes ~73-147).
   backend/src/main/resources/application.yml ligne 59 définit `sender-id: ${SMS_SENDER_ID}` (aucun
   défaut), lu par SmsProperties
   (backend/src/main/java/sn/samapiece/notifications/SmsProperties.java, champ `senderId` annoté
   @NotBlank, classe annotée @Component donc instanciée systématiquement au démarrage). render.yaml
   fournit bien SMS_API_ENDPOINT et SMS_API_KEY mais pas SMS_SENDER_ID, alors que docker-compose.yml
   l'injecte (ligne "SMS_SENDER_ID: ${SMS_SENDER_ID}") et que .env.example le documente
   (SMS_SENDER_ID=SamaPiece, valeur non sensible).

   Scénario déclenché, vérifié empiriquement : j'ai démarré le conteneur samapiece-backend:dev (profil
   staging) avec exactement le jeu de variables déclaré par render.yaml, SMS_SENDER_ID non défini.
   Contrairement à l'hypothèse initiale d'un échec de démarrage, Spring Boot résout le placeholder non
   résolvable en le laissant tel quel : le champ senderId prend la valeur littérale du texte
   "dollar-accolade-SMS_SENDER_ID-accolade" (non vide, donc passe la validation @NotBlank), et
   l'application démarre normalement avec /actuator/health répondant 200. Mais toute tentative d'envoi
   SMS via PasserelleSmsHttpClient enverrait un sender-id littéralement égal à ce texte de placeholder
   plutôt qu'un identifiant valide.

   Impact réel limité pour ce ticket précis puisque SMS_API_ENDPOINT est déjà pointé vers un endpoint
   factice (http://localhost:1, décision assumée du design pour la démo, aucune passerelle SMS réelle)
   — mais c'est une incomplétude réelle et vérifiée du livrable principal de ce ticket (render.yaml est
   censé énumérer toutes les variables requises pour un déploiement fonctionnel), à corriger avant de
   considérer le Blueprint complet : ajouter une entrée "key: SMS_SENDER_ID" avec une valeur non
   sensible en clair (par exemple value: SamaPiece, cohérent avec .env.example et avec le traitement
   déjà réservé à MINIO_BUCKET_PHOTOS/MEILISEARCH_INDEX_PIECES dans ce même fichier — inutile de la
   mettre en sync:false, ce n'est pas un secret). Fix estimé : une ligne, sans impact sur l'architecture
   ni sur les autres décisions du design.

Aucun autre défaut de logique, de sécurité (RBAC, secrets, chiffrement paragraphe 10 de
PROJET-SAMAPIECE.md) ou de cohérence spec/design n'a été identifié : le périmètre "pas de déploiement
réel" est strictement respecté, les incertitudes de schéma Render sont honnêtement documentées comme
des hypothèses (jamais présentées comme des faits), et le README ne revendique à aucun moment une
vérification en ligne qui n'a pas eu lieu.

## Build/tests

- `python -c "import yaml, sys; yaml.safe_load(open('render.yaml'))"` : OK, YAML valide.
- `docker compose up -d --build` (stack complète, `.env` local avec clés AES/JWT régénérées en base64
  valide via openssl) : tous les services healthy ; `curl -i http://localhost:8081/api/v1/postes` :
  200, application/json ; nginx généré confirmé sans placeholder résiduel. `docker compose down -v`
  pour nettoyage, `.env` supprimé après coup (fichier gitignored, jamais committé).
- Démarrage direct du conteneur backend (profil staging, variables strictement identiques à
  render.yaml) sur le réseau docker-compose : Started SamaPieceApplication, `GET /actuator/health`
  retourne le statut UP (confirme le Finding 1 : démarre malgré l'absence de SMS_SENDER_ID, mais avec
  une valeur de sender-id incorrecte en interne).
- `mvn -pl backend test` : 223 tests, 0 failure, 24 erreurs, toutes de la forme "Previous attempts to
  find a Docker environment failed" / "Can't get Docker image ... postgres:16-alpine" (Testcontainers
  ne détecte pas l'environnement Docker Desktop de cette machine Windows, alors que le CLI docker et
  docker compose fonctionnent normalement en dehors de Testcontainers, comme démontré tout au long de
  cette revue). Confirmé : aucune des 24 classes en erreur n'est nouvelle dans ce diff, aucune n'active
  le profil staging. Affirmation du codeur vérifiée et confirmée exacte — non-bloquant, problème
  d'environnement local préexistant et indépendant de ce diff.

## Conclusion

Le livrable respecte strictement la contrainte "pas de déploiement réel" et la qualité d'ensemble (YAML,
documentation, non-régression locale, hedging des hypothèses) est bonne. Un seul défaut concret et
vérifié empiriquement bloque l'approbation : render.yaml omet SMS_SENDER_ID, une variable requise par le
code existant, ce qui laisserait un Blueprint objectivement incomplet pour quiconque le déploierait tel
quel. Correction attendue avant merge : ajouter cette variable (valeur non sensible en clair) au bloc
envVars du service samapiece-backend dans render.yaml.
