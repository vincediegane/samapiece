# Spec — Ticket #27 : Chiffrement au repos et gestion des secrets

## Résumé

Livrer un scan de secrets gitleaks en CI (`.github/workflows/secrets-scan.yml` + `.gitleaks.toml`, bloquant dès le premier commit après vérification locale sans faux positif) et trois documents de procédure dans `docs/securite/` (chiffrement au repos, TLS, revue des secrets/historique git), sans modifier aucun fichier de code applicatif.

## Tâches

- [ ] **Vérification préalable locale de gitleaks (avant tout commit du workflow/config)** : installer gitleaks localement (binaire depuis les releases GitHub `gitleaks/gitleaks`, ou via Docker `zricethezav/gitleaks` si Docker est disponible dans l'environnement du codeur) et exécuter un scan sur l'historique complet du repo actuel, sans encore de `.gitleaks.toml` custom, pour lister les findings bruts (faux positifs attendus : fixtures de `backend/src/test/resources/application.yml`). Conserver la sortie brute (elle sert de justification pour le contenu exact de l'allowlist ci-dessous).
- [ ] **Créer `.gitleaks.toml`** à la racine du repo avec le contenu donné en Contrat technique, en ajustant la liste `regexes` de l'allowlist pour qu'elle corresponde exactement aux findings réels observés à l'étape précédente (ne pas ajouter d'entrée non justifiée par un vrai finding).
- [ ] **Re-scanner localement avec `.gitleaks.toml` appliqué** et confirmer 0 finding sur l'historique complet (toutes branches, `--log-opts="--all"`). C'est la condition qui autorise le mode bloquant du workflow (voir Contrat technique, décision sur `continue-on-error`).
- [ ] **Vérifier le type de compte GitHub du dépôt** (`gh repo view --json owner --jq .owner.type` ou équivalent) pour trancher si `gitleaks/gitleaks-action@v2` peut être utilisé sans `GITLEAKS_LICENSE` (gratuit pour compte individuel/repo public, licence payante requise seulement pour un repo appartenant à une organisation GitHub). Documenter le résultat dans le message de commit ou la description de PR. Si une organisation sans licence est détectée, utiliser à la place l'alternative Docker officielle décrite en Contrat technique (pas d'action marketplace).
- [ ] **Créer `.github/workflows/secrets-scan.yml`** avec le contenu exact donné en Contrat technique (déclenchement `push`/`pull_request` sans filtre `paths`, `fetch-depth: 0`, job gitleaks bloquant).
- [ ] **Créer `docs/securite/gestion-secrets-et-revue-historique.md`** en suivant le plan de sections du Contrat technique — réexécuter soi-même les commandes de revue de l'historique git (ne pas se contenter de recopier la conclusion de `design.md`) et consigner la date de la revue, le hash du commit HEAD au moment de la revue, et le résultat obtenu.
- [ ] **Créer `docs/securite/chiffrement-au-repos.md`** en suivant le plan de sections du Contrat technique, avec mention explicite en tête de document que le critère n'est pas applicable aujourd'hui (aucune infra staging/prod provisionnée) et une checklist actionnable pour le jour du provisioning.
- [ ] **Créer `docs/securite/tls-flux-externes.md`** en suivant le plan de sections du Contrat technique, avec la même mention explicite de non-applicabilité actuelle et une checklist de vérification par flux externe.
- [ ] **Ne modifier aucun fichier sous `backend/` ou `frontend/`** (config déjà conforme, confirmé par l'audit du design — aucune correction à apporter à `application*.yml`, `docker-compose.yml`, `.env.example`, `.gitignore`).
- [ ] **Committer et pousser**, puis ouvrir/mettre à jour la PR et laisser tourner `secrets-scan.yml` au moins une fois pour confirmer qu'il passe en vert sur cette branche (voir Vérifications attendues).

## Contrat technique

### `.gitleaks.toml` (racine du repo)

Étend la configuration par défaut de gitleaks (garde toutes les règles standard actives) et ajoute une allowlist minimale, basée sur des chaînes exactes (pas de chemin de fichier exclu en bloc, pour ne pas masquer un futur vrai secret dans ce même fichier de test) :

```toml
title = "SamaPiece — configuration gitleaks"

[extend]
useDefault = true

[allowlist]
description = "Fixtures de test factices connues (backend/src/test/resources/application.yml) — voir docs/securite/gestion-secrets-et-revue-historique.md"
regexes = [
  '''test-secret-uniquement-pour-les-tests-automatises-ne-jamais-reutiliser''',
  '''AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=''',
  '''BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=''',
]
```

Ces trois entrées correspondent aux fixtures déjà identifiées par l'audit (`jwt.secret`, `photo.cle-chiffrement`, `alerte.cle-chiffrement` de `backend/src/test/resources/application.yml`). Si le scan local (tâche 1) révèle d'autres findings réels sur ce même fichier (par exemple `test-access-key`, `test-secret-key`, `test-master-key`, `test-api-key` s'ils déclenchent une règle d'entropie), ajouter une regex par valeur exacte trouvée — jamais une regex générique du type `test-.*` qui masquerait une vraie fuite future nommée `test-...`.

### `.github/workflows/secrets-scan.yml`

Workflow séparé de `backend.yml`/`frontend.yml` (pas de filtre `paths`, puisqu'un secret peut apparaître dans n'importe quel fichier) :

```yaml
name: secrets-scan

on:
  push:
    branches:
      - '**'
  pull_request:

jobs:
  gitleaks:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout (historique complet)
        uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: Scan des secrets (gitleaks)
        uses: gitleaks/gitleaks-action@v2
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          GITLEAKS_CONFIG: .gitleaks.toml
```

- `fetch-depth: 0` : obligatoire pour que gitleaks scanne tout l'historique et pas seulement le commit du push/de la PR.
- `gitleaks/gitleaks-action@v2` : action officielle du projet gitleaks. Gratuite pour un dépôt personnel/public ; nécessite un secret `GITLEAKS_LICENSE` si le dépôt appartient à une organisation GitHub (cf. tâche de vérification ci-dessus).
- **Mode bloquant dès la livraison** : ne pas mettre `continue-on-error: true`. Le codeur ayant accès à `Bash`/Docker pour exécuter gitleaks localement sur l'historique complet avant de committer `.gitleaks.toml`, il doit valider l'absence de faux positif (0 finding après allowlist) avant de pousser — dans ce cas, rien ne justifie de livrer le workflow en mode dégradé. Le workflow est donc livré directement bloquant.
- **Alternative sans action marketplace (si licence requise et non disponible)** : remplacer le step `gitleaks/gitleaks-action@v2` par un run Docker direct de l'image officielle, qui ne nécessite aucune licence :
  ```yaml
      - name: Scan des secrets (gitleaks, image Docker officielle)
        run: |
          docker run --rm -v "$GITHUB_WORKSPACE:/repo" zricethezav/gitleaks:latest \
            detect --source /repo --log-opts="--all" --config /repo/.gitleaks.toml --redact --exit-code 1
  ```
  N'utiliser cette variante que si la vérification du type de compte (tâche dédiée ci-dessus) montre qu'une licence serait nécessaire.

### `docs/securite/gestion-secrets-et-revue-historique.md` — plan de sections

1. **Objectif et périmètre** — répond aux critères d'acceptation 2 et 4 du ticket #27.
2. **Règle applicable** — aucun secret en dur dans le code versionné ; liste des secrets concernés du projet (`JWT_SECRET`, `PHOTO_CLE_CHIFFREMENT`, `ALERTE_CLE_CHIFFREMENT`, `SMS_API_KEY`, `RABBITMQ_PASSWORD`, `DB_USER`/`DB_PASSWORD`, `MINIO_ACCESS_KEY`/`MINIO_SECRET_KEY`, clé API Meilisearch), toujours chargés depuis des variables d'environnement.
3. **État de conformité actuel** — tableau fichier / statut / commentaire, transcrivant l'audit du design (`application.yml` racine, `application-prod.yml`/`application-staging.yml`, `application-dev.yml`, `backend/src/test/resources/application.yml`, `docker-compose.yml`, `.env.example`).
4. **Méthode de revue de l'historique git (reproductible)** — commandes exactes à copier-coller, au minimum :
   - `git log --all -p -- '*.env' 'backend/src/main/resources/application*.yml' 'backend/src/test/resources/application*.yml' docker-compose.yml` filtré visuellement/par regex sur `password|secret|api[_-]?key`.
   - `git log --all --diff-filter=D --name-only` pour vérifier qu'aucun fichier `.env`/`.pem`/`.key`/credential n'a jamais été commité puis supprimé.
   - Préciser explicitement la méthode d'exclusion des faux positifs (placeholders, références `${VAR}`, commentaires, fixtures de test).
5. **Résultat de la revue** — date de la revue effectuée par le codeur (pas la date de conception), hash du commit HEAD au moment de la revue, conclusion (aucun secret réel trouvé, ou liste des actions correctives si un secret est trouvé).
6. **Garde-fou continu : scan gitleaks en CI** — référence à `.github/workflows/secrets-scan.yml` et `.gitleaks.toml`, mode bloquant, procédure à suivre en cas de détection positive (rotation immédiate du secret concerné + évaluation d'une purge d'historique via `git filter-repo`/BFG si nécessaire).
7. **Procédure de revue future** — checklist reproductible + fréquence recommandée (ex. avant chaque release majeure, ou trimestriellement).
8. **Hors périmètre** — Vault/KMS non traité ici (écart connu, ticket futur distinct).

### `docs/securite/chiffrement-au-repos.md` — plan de sections

1. **Statut actuel (en tête, en gras)** — "Non applicable aujourd'hui : aucun environnement staging/prod n'est provisionné dans ce repo (seul `docker-compose.yml` existe, pour le développement local)." Répond au critère d'acceptation 1.
2. **Répartition des responsabilités** — plateforme d'hébergement (chiffrement de disque) vs application (ne gère pas le chiffrement au repos infra).
3. **Options selon le choix d'hébergement futur** :
   - PostgreSQL/MinIO managés (offre cloud) → chiffrement de disque activé côté fournisseur (ex. chiffrement de volume géré, à activer explicitement à la création de l'instance).
   - Auto-hébergé (VM/Docker sur serveur dédié) → chiffrement au niveau du système de fichiers hôte (LUKS/dm-crypt) sur les volumes Docker utilisés par PostgreSQL et MinIO.
4. **Ce qui ne compte pas comme chiffrement au repos** — mise en garde explicite : `PHOTO_CLE_CHIFFREMENT`/`ALERTE_CLE_CHIFFREMENT` (tickets #13/#22) sont du chiffrement applicatif au niveau champ, distinct de ce critère ; ne pas les citer comme preuve de conformité au critère 1.
5. **Checklist à cocher avant toute mise en environnement partagé** — liste actionnable avec propriétaire (qui doit exécuter chaque étape) avant tout provisioning staging/prod réel.
6. **Procédure de vérification une fois l'infra provisionnée** — comment prouver que le chiffrement est actif (ex. requête `describe`/`show` côté fournisseur cloud, `cryptsetup status` côté hôte auto-hébergé).
7. **Hors périmètre** — introduction d'un KMS/Vault, provisioning d'infra réelle (tickets futurs distincts).

### `docs/securite/tls-flux-externes.md` — plan de sections

1. **Statut actuel (en tête, en gras)** — "Non applicable aujourd'hui : aucun reverse proxy/terminaison TLS n'est configuré dans ce repo (`frontend/nginx.conf` sert du HTTP nu, usage développement uniquement)." Répond au critère d'acceptation 3.
2. **Flux externes concernés** — portail public web, application agent vers l'API, l'API elle-même exposée publiquement, appel sortant vers l'API SMS (`samapiece.sms.api-endpoint`), tout futur webhook entrant du fournisseur SMS.
3. **Exigence** — TLS 1.2 minimum (1.3 recommandé), certificats valides (Let's Encrypt ou fournisseur cloud), redirection HTTP→HTTPS obligatoire, en-tête HSTS recommandé.
4. **Checklist à exécuter/vérifier au moment du déploiement réel**, par flux, avec propriétaire et méthode de vérification concrète (ex. `curl -Iv https://<host>`, `openssl s_client -connect <host>:443 -tls1_2`, scan SSL Labs).
5. **Cas particulier de l'API SMS** — rappeler que `samapiece.sms.api-endpoint` vaut `http://127.0.0.1:1` uniquement en profil test (normal, cible un port fermé volontairement) ; vérifier explicitement que la valeur configurée en staging/prod est en `https://` avant toute mise en service.
6. **Hors périmètre** — pas de test automatisé possible sans infra réelle ; aucune configuration de reverse proxy n'existe dans ce repo à ce jour.

## Vérifications attendues

Pas de suite de tests automatisés classique pertinente ici (nature documentaire/outillage). Le codeur doit valider lui-même son travail avant de conclure :

1. **Gitleaks — 0 faux positif avant mode bloquant** : exécuter localement (ou en environnement CI-like) `gitleaks detect --source . --log-opts="--all" --config .gitleaks.toml -v` (ou équivalent Docker) sur l'historique complet, après création de `.gitleaks.toml`, et confirmer 0 finding. Conserver la sortie de ce run comme preuve (dans la description de la PR par exemple).
2. **Workflow CI réellement vert** : après push de la branche et ouverture/mise à jour de la PR, `gh run list --workflow=secrets-scan.yml --branch <nom-de-branche>` doit montrer un run en succès ; `gh run view <run-id> --log` pour confirmer que le scan a bien porté sur l'historique complet (pas seulement le diff) et s'est terminé sans finding non-allowlisté.
3. **Cohérence des trois documents `docs/securite/*.md`** : relecture croisée pour vérifier qu'ils utilisent un ton homogène de "procédure à exécuter plus tard" (pas de fausse assurance de conformité), qu'ils se référencent mutuellement là où pertinent (ex. `chiffrement-au-repos.md` et `tls-flux-externes.md` renvoient vers `gestion-secrets-et-revue-historique.md` pour le garde-fou CI), et qu'aucun des trois ne prétend qu'un critère est déjà satisfait alors qu'il ne l'est pas.
4. **Reproductibilité de la revue d'historique** : rejouer soi-même, en tant que codeur, les commandes documentées dans `gestion-secrets-et-revue-historique.md` (section 4) et vérifier que le résultat obtenu correspond à ce qui est écrit dans la section 5 du même document (même conclusion : aucun secret réel trouvé).
5. **Aucune régression sur les workflows existants** : `backend.yml` et `frontend.yml` ne sont pas modifiés ; `secrets-scan.yml` est un workflow indépendant qui ne doit pas interférer avec leur déclenchement.
6. **Aucun fichier applicatif touché** : `git diff --stat main...<branche>` (ou équivalent) doit montrer uniquement des fichiers sous `docs/securite/`, `docs/bolts/27-.../`, `.gitleaks.toml` et `.github/workflows/secrets-scan.yml` — aucun fichier sous `backend/` ou `frontend/`.

## Écarts identifiés

- **Licence `gitleaks-action` selon le type de compte GitHub, non tranchée par le design.** Le design ne précise pas si le dépôt est personnel ou appartient à une organisation GitHub, information qui détermine si `gitleaks/gitleaks-action@v2` peut être utilisé gratuitement ou nécessite un secret `GITLEAKS_LICENSE` payant. Cette spec transforme ce point en tâche explicite de vérification (`gh repo view --json owner`) avec un plan de repli documenté (image Docker officielle `zricethezav/gitleaks`, sans dépendance à une licence) — à trancher par le codeur avant de committer le workflow définitif, pas à deviner.
- **Mode `continue-on-error` du workflow, laissé ouvert par le design.** Le design présentait le choix bloquant/non-bloquant comme un point ouvert "à trancher par le spec-writer/codeur selon le résultat des premiers runs". Cette spec tranche explicitement en faveur du mode bloquant dès la livraison (`continue-on-error` absent/`false`), conditionné à une vérification locale préalable obligatoire (tâche dédiée) — pas de round CI intermédiaire en mode dégradé, puisque le codeur peut valider l'absence de faux positif avant de committer.
- Aucun autre écart entre `design.md` et les critères d'acceptation du ticket #27 : la répartition documentation/outillage/audit proposée par l'architecte couvre les quatre critères, avec les limitations déjà explicitement assumées (pas de test automatisé possible pour les critères 1 et 3 en l'absence d'infra réelle — traité par des checklists de procédure et non par un test à inventer artificiellement).
