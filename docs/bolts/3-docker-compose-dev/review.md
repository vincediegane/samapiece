# Review — Ticket #3 : Dockeriser backend/frontend + docker-compose de dev

## Verdict

**APPROVE**

Le diff (`bolt/issue-2-ci-github-actions...HEAD`) est conforme au contrat technique de `spec.md` au caractère près pour tous les fichiers, à l'exception du tag MinIO — écart justifié, vérifié indépendamment (voir ci-dessous), et documenté dans le message de commit `41f82ee`. J'ai reconstruit et relancé la stack complète moi-même (pas seulement lu le rapport du codeur) : les 7 services démarrent, les 6 qui ont un healthcheck passent `healthy`, tous les endpoints exposés répondent, aucune erreur JDBC dans les logs backend, et le nettoyage (`docker compose down -v`) s'est terminé proprement.

## Critères d'acceptation

| # | Critère | Statut | Base de la vérification |
|---|---|---|---|
| 1 | `Dockerfile` multi-stage backend (Maven → jar layered) | **Couvert** | `backend/Dockerfile` identique au contrat de spec. Build réel : `docker compose build backend` réussit. `docker history samapiece-backend:dev` confirme que le stage final ne contient que `eclipse-temurin:...-jre-alpine` + les 4 couches `COPY --from=build` (dependencies/spring-boot-loader/snapshot-dependencies/application dans cet ordre) — pas de Maven ni JDK complet dans l'image finale. Conteneur démarré réellement, `healthy` en ~25s, `/actuator/health` → `{"status":"UP"}`. |
| 2 | `Dockerfile` frontend (Vite → nginx statique) | **Couvert** | `frontend/Dockerfile` et `frontend/nginx.conf` identiques au contrat. `docker run --rm samapiece-frontend:dev ls /usr/share/nginx/html` liste uniquement `index.html`, `assets/`, `vite.svg`, `50x.html` — pas de `node_modules`. `curl -I http://localhost:8081/` → `200`. Fallback SPA testé sur une route arbitraire (`/some/deep/route/...`) → `200` + contenu de `index.html`, conforme au `try_files $uri /index.html;`. |
| 3 | `docker-compose.yml` démarre les 7 services (backend, frontend, postgres, redis, meilisearch, minio, rabbitmq) | **Couvert** | `docker compose up -d --build` réel : les 7 conteneurs sont créés et démarrés. `docker compose ps` : backend/postgres/redis/meilisearch/minio/rabbitmq → `healthy` ; frontend → `Up` (pas de healthcheck, conforme au contrat). |
| 4 | `docker-compose up` fonctionne sans configuration manuelle supplémentaire sur machine propre | **Couvert** | Seule étape manuelle effectuée : `cp .env.example .env` (confirmé identique au contrat, aucune édition). `docker compose up -d --build` sans autre intervention. Tous les endpoints testés répondent (détail dans Build/tests). `docker compose logs backend` ne contient aucune occurrence de `error`/`exception`/`jdbc`/`refused` — démarrage propre en ~7s applicatif. |
| 5 | `.env.example` documente toutes les variables | **Couvert** | Comparaison automatisée : les 20 variables `${...}` référencées dans `docker-compose.yml` sont exactement les 20 clés définies dans `.env.example` (ensemble identique, `comm -23` vide dans les deux sens). Aucune valeur vide, `MINIO_ROOT_PASSWORD` ≥ 8 caractères. |

## Findings

Aucun finding bloquant. Deux observations mineures, non bloquantes :

1. **Déviation du tag MinIO vis-à-vis du contrat de `spec.md` — justifiée et vérifiée indépendamment.** Le contrat de spec fixait `quay.io/minio/minio:RELEASE.2025-10-15T17-29-55Z`. Le codeur a remplacé ce tag par `RELEASE.2025-09-07T16-13-09Z`. J'ai interrogé moi-même l'API quay.io :
   - `RELEASE.2025-10-15T17-29-55Z` → `{"tags": []}` (n'existe pas sur le registre).
   - `RELEASE.2025-09-07T16-13-09Z` → tag présent, manifest list à 3 enfants, publié le 2025-09-07.
   Le remplacement est donc factuellement correct et nécessaire : garder le tag de la spec aurait fait échouer `docker compose up --build` au pull sur toute machine sans l'image déjà en cache, cassant directement le critère d'acceptation 4. La déviation est documentée explicitement dans le message du commit `41f82ee` (écart tracé, raison donnée, vérification décrite). Le conteneur MinIO avec ce tag démarre et passe `healthy` (healthcheck `mc ready local`), et `curl -f http://localhost:9000/minio/health/live` répond `200` depuis le host, comme attendu.
   
2. **Avertissement cosmétique Compose** : `docker compose` (v2.40.3) émet `the attribute version is obsolete` à chaque commande à cause de `version: "3.9"` en tête de `docker-compose.yml`. C'est une décision explicite du contrat de spec (compatibilité avec le binaire `docker-compose` V1), pas un bug du codeur — mentionné ici pour mémoire, aucune action requise.

Points explicitement vérifiés comme n'étant PAS des bugs (conformes au design/spec, à ne pas re-signaler) :
- Absence de `depends_on` backend→postgres/redis/etc. : voulu, documenté en commentaire inline dans `docker-compose.yml`, dans le README, et dans `spec.md` (Écart identifié #1). `/actuator/health` répond `UP` indépendamment de l'état de postgres, confirmé par les logs backend (aucune tentative de connexion JDBC, cohérent avec `spring.autoconfigure.exclude` dans `application.yml`).
- Healthchecks `wget` (backend) et `mc ready local` (minio) au lieu de `curl` : justifié par l'absence de `curl`/`wget` dans les images minimales retenues, documenté dans `spec.md` (Écarts #3 et #4), et vérifié fonctionnel en pratique (les deux services passent `healthy`).
- Volumes nommés présents pour `postgres_data`, `minio_data`, `meili_data` ; absence de volume pour redis/rabbitmq/meilisearch-des-données-non-critiques : conforme au contrat, tous les volumes attendus existent et sont bien supprimés par `down -v`.
- Zéro valeur en dur dans `docker-compose.yml` (toutes les valeurs sensibles passent par `${VAR}`, vérifié par grep) ; `.env` n'a jamais été committé (`git log --all --full-history -- .env` vide, `.env` bien listé dans `.gitignore`).
- Aucun fichier superflu créé : le diff ne touche que les 8 fichiers attendus par le contrat (+ `design.md`/`spec.md` déjà produits aux étapes précédentes du pipeline).

## Build/tests

Toutes les commandes ci-dessous ont été exécutées réellement par moi (reviewer), pas héritées du rapport du codeur.

```
$ cp .env.example .env      # .env n'existait pas encore avant cette étape dans mon shell ; contenu diff-vérifié identique aux valeurs de dev de .env.example
$ docker compose up -d --build
  → build backend + frontend OK, 7 conteneurs créés et démarrés

$ docker compose ps
  backend       Up (healthy)
  frontend      Up
  meilisearch   Up (healthy)
  minio         Up (healthy)
  postgres      Up (healthy)
  rabbitmq      Up (healthy)
  redis         Up (healthy)

$ curl -sf http://localhost:8080/actuator/health        → {"status":"UP"}
$ curl -sI http://localhost:8081/                        → HTTP/1.1 200 OK
$ curl -sf http://localhost:7700/health                  → {"status":"available"}
$ curl -sf -o /dev/null -w "%{http_code}" http://localhost:9000/minio/health/live → 200
$ curl -sI http://localhost:9001/                         → HTTP/1.1 200 OK
$ curl -sI http://localhost:15672/                        → HTTP/1.1 200 OK
$ docker compose exec redis redis-cli ping                → PONG
$ docker compose exec postgres pg_isready -U samapiece    → accepting connections

$ docker compose logs backend | grep -iE "error|exception|jdbc|refused"
  → aucune occurrence

$ docker run --rm samapiece-frontend:dev ls /usr/share/nginx/html
  → index.html, assets/, vite.svg, 50x.html (pas de node_modules)

$ docker history samapiece-backend:dev
  → confirme stage final JRE-only + 4 couches layertools dans l'ordre attendu

$ grep -oE '\$\{[A-Z_]+' docker-compose.yml | sort -u   (20 variables)
$ grep -oE '^[A-Z_]+=' .env.example | sort -u           (20 variables, ensembles identiques)

$ curl -s https://quay.io/api/v1/repository/minio/minio/tag/?specificTag=RELEASE.2025-10-15T17-29-55Z
  → {"tags": []}   (confirme : ce tag n'existe pas, la correction du codeur était nécessaire)
$ curl -s https://quay.io/api/v1/repository/minio/minio/tag/?specificTag=RELEASE.2025-09-07T16-13-09Z
  → tag présent (confirme : le tag retenu par le codeur existe bien)

$ git log --all --full-history -- .env
  → vide (jamais committé)

$ docker compose down -v
  → tous les conteneurs stoppés/supprimés, network et 3 volumes nommés supprimés
  → code de sortie : 0
$ docker volume ls | grep samapiece
  → aucun résultat (nettoyage confirmé, rien ne tourne plus)
```

Nettoyage final confirmé : aucun conteneur ni volume du projet ne reste actif après cette review.
