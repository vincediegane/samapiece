# Gestion des secrets et revue de l'historique git

## 1. Objectif et périmètre

Ce document répond aux critères d'acceptation 2 (« aucun secret n'est jamais commité en clair ») et 4 (« une revue documentée de l'historique git a été effectuée ») du ticket #27. Il décrit :

- la règle applicable aux secrets du projet SamaPiece ;
- l'état de conformité actuel des fichiers de configuration versionnés ;
- une méthode reproductible de revue de l'historique git ;
- le résultat de la revue effectuée par le codeur du ticket #27 ;
- le garde-fou continu mis en place (scan gitleaks en CI) ;
- une procédure de revue future.

## 2. Règle applicable

Aucun secret réel (mot de passe, clé API, clé de chiffrement, jeton) ne doit jamais être écrit en dur dans un fichier versionné dans ce dépôt. Tous les secrets du projet sont chargés depuis des variables d'environnement, sans valeur par défaut en profil `prod`/`staging`. Les secrets concernés à ce jour :

- `JWT_SECRET`
- `PHOTO_CLE_CHIFFREMENT`
- `ALERTE_CLE_CHIFFREMENT`
- `SMS_API_KEY`
- `RABBITMQ_PASSWORD`
- `DB_USER` / `DB_PASSWORD`
- `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY`
- la clé API Meilisearch (`MEILISEARCH_API_KEY` / `MEILI_MASTER_KEY`)

Seul le profil `dev` (`application-dev.yml`, `.env.example`) tolère des valeurs par défaut, explicitement triviales et documentées comme non réutilisables en dehors du poste de développement local.

## 3. État de conformité actuel

| Fichier | Statut | Commentaire |
|---|---|---|
| `backend/src/main/resources/application.yml` | Conforme | `JWT_SECRET`, `PHOTO_CLE_CHIFFREMENT`, `ALERTE_CLE_CHIFFREMENT`, `SMS_API_KEY`, `RABBITMQ_PASSWORD` référencés via `${VAR}` sans défaut. |
| `backend/src/main/resources/application-prod.yml` | Conforme | `DB_PASSWORD`, `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY` sans défaut. |
| `backend/src/main/resources/application-staging.yml` | Conforme | Idem `application-prod.yml`. |
| `backend/src/main/resources/application-dev.yml` | Conforme (dev uniquement) | Défauts présents (`samapiece_minio`, `samapiece_minio_password`, `samapiece_dev_master_key_change_me`) : mots de passe de développement triviaux et explicitement nommés comme tels, pas des secrets de production. |
| `backend/src/test/resources/application.yml` | Conforme | Valeurs factices explicitement nommées (`test-secret-uniquement-pour-les-tests-automatises-ne-jamais-reutiliser`, clés AES `A...=`/`B...=` répétées) — fixtures de test, pas des secrets réels. |
| `docker-compose.yml` | Conforme | Tous les secrets relayés via `${VAR}` depuis l'environnement hôte, aucune valeur en dur. |
| `.env.example` | Conforme | Uniquement des placeholders explicites (`changez_moi_avec_...`) ou des identifiants dev triviaux, jamais une vraie clé/mot de passe ; `.env` réel est ignoré par `.gitignore` et n'a jamais été commité (voir section 5). |

## 4. Méthode de revue de l'historique git (reproductible)

Deux commandes suffisent à couvrir l'essentiel du risque (secret en dur dans un diff, ou fichier sensible supprimé mais toujours présent dans l'historique) :

```bash
git log --all -p -- '*.env' 'backend/src/main/resources/application*.yml' 'backend/src/test/resources/application*.yml' docker-compose.yml \
  | grep -iE "password|secret|api[_-]?key"
```

```bash
git log --all --diff-filter=D --name-only \
  | grep -iE '\.env$|\.pem$|\.key$|credential'
```

- La première commande liste toutes les lignes ajoutées/supprimées, sur toute l'histoire (`--all`, tous les refs), touchant un fichier de config ou `.env`, contenant un des motifs `password`, `secret`, `api-key`/`api_key`/`apikey`.
- La seconde vérifie qu'aucun fichier `.env`, `.pem`, `.key` ou nommé `credential*` n'a jamais été commis puis supprimé (un tel fichier resterait récupérable dans l'historique malgré sa suppression apparente en HEAD).
- **Méthode d'exclusion des faux positifs** : chaque ligne renvoyée par la première commande est relue manuellement pour écarter : les références `${VAR}`/`${VAR:defaut}` (pas une valeur en dur), les commentaires (lignes commençant par `#`), et les fixtures de test explicitement nommées comme telles (`test-...`, clés `AAAA...=`/`BBBB...=`). Une ligne n'est retenue comme suspecte que si elle contient une valeur littérale qui ressemble à un secret réel (pas un placeholder, pas une variable, pas un nom de clé).

## 5. Résultat de la revue

- **Date de la revue** : 2026-09-14
- **Commit HEAD au moment de la revue** : `50736f2cd76199a62d84f200e411093f45d423cb` (branche `bolt/issue-27-chiffrement-repos-secrets`)
- **Commandes rejouées** : celles de la section 4, exécutées telles quelles par le codeur du ticket #27 (pas une recopie de la conclusion de conception).
- **Résultat obtenu** :
  - La première commande ne remonte que des références `${DB_PASSWORD}`, `${JWT_SECRET}`, `${MEILISEARCH_API_KEY}`, `${MINIO_SECRET_KEY}`, `${SMS_API_KEY}`, `${RABBITMQ_PASSWORD}` (variables d'environnement, pas des valeurs en dur), ainsi que les fixtures de test déjà identifiées (`test-secret-uniquement-pour-les-tests-automatises-ne-jamais-reutiliser`, `test-api-key`, `test-master-key`, `test-secret-key`) et un défaut de développement trivial (`samapiece_dev_master_key_change_me`, `samapiece_minio_password`). Aucune valeur littérale ressemblant à un secret réel de production.
  - La seconde commande ne retourne aucun résultat : aucun fichier `.env`/`.pem`/`.key`/`credential*` n'a jamais été commité puis supprimé sur l'ensemble de l'historique (toutes branches).
  - **Complément outillé** : un scan gitleaks (image `zricethezav/gitleaks:latest`, v8.30.1) a également été exécuté sur l'historique complet (`--log-opts="--all"`, 175 commits non-fusion sur 200 commits toutes branches confondues), sans aucune configuration custom au départ. Résultat : **0 finding**, y compris sur les fixtures de `backend/src/test/resources/application.yml` — contrairement à l'hypothèse initiale du design qui anticipait des faux positifs sur ces fixtures (la règle par défaut `generic-api-key` de gitleaks ne matche pas ces valeurs précises, vérifié en isolant le fichier de test dans un scan dédié). Ce point a été documenté dans le message du commit ayant introduit `.gitleaks.toml` : aucune entrée d'allowlist n'a donc été ajoutée, faute de finding réel à exclure.
- **Conclusion** : aucun secret réel trouvé dans l'historique git de ce dépôt. Aucune action corrective (rotation, purge d'historique) n'est nécessaire à ce jour.

## 6. Garde-fou continu : scan gitleaks en CI

Le scan ponctuel ci-dessus ne protège pas contre une régression future (un développeur qui commettrait accidentellement un secret réel dans un commit ultérieur). Le garde-fou continu est le workflow `.github/workflows/secrets-scan.yml`, qui exécute `gitleaks/gitleaks-action@v2` avec la configuration `.gitleaks.toml` sur chaque `push`/`pull_request`, sur l'historique complet (`fetch-depth: 0`), **en mode bloquant** (pas de `continue-on-error`).

**Procédure en cas de détection positive par ce workflow :**

1. Ne pas fusionner/merger la branche concernée tant que le finding n'est pas traité.
2. **Rotation immédiate** du secret concerné (générer une nouvelle valeur, la déployer partout où l'ancienne était utilisée, révoquer l'ancienne côté fournisseur si applicable) — considérer le secret exposé comme compromis dès l'instant où il apparaît dans un commit, même sur une branche non fusionnée.
3. Évaluer si le secret n'apparaît que dans un commit récent non poussé/non partagé (auquel cas un simple correctif suffit) ou s'il est déjà présent dans l'historique partagé (auquel cas une purge d'historique via `git filter-repo` (préféré) ou BFG Repo-Cleaner est nécessaire, suivie d'une coordination avec toute l'équipe pour re-cloner le dépôt).
4. Si le finding est un faux positif confirmé (donnée non sensible), ajouter une entrée d'allowlist dans `.gitleaks.toml` avec une regex correspondant à la valeur exacte trouvée (jamais une regex générique), en documentant la justification dans le commit et dans ce document.

## 7. Procédure de revue future

Checklist reproductible, à exécuter :

- [ ] Avant chaque release majeure.
- [ ] Trimestriellement, indépendamment des releases.
- [ ] Immédiatement après tout finding positif du scan CI (voir section 6).

Étapes :

1. Rejouer les deux commandes de la section 4 sur l'historique complet.
2. Relire manuellement les résultats selon la méthode d'exclusion des faux positifs décrite en section 4.
3. Mettre à jour la section 5 de ce document (date, hash HEAD, résultat) pour garder une trace datée de chaque revue.
4. Vérifier que le tableau de la section 3 reflète toujours l'état réel des fichiers de config (ajouter une ligne si un nouveau fichier de config sensible a été introduit depuis la dernière revue).

## 8. Hors périmètre

La gestion centralisée des secrets via un coffre-fort (HashiCorp Vault, KMS cloud) n'est pas traitée par ce document. C'est un écart connu par rapport aux recommandations du PROJET (§10.4/§11.4) : à ce jour, tous les secrets transitent par des variables d'environnement/`.env`, ce qui est acceptable pour l'état actuel du projet mais devra faire l'objet d'un ticket dédié si le besoin de rotation automatisée ou d'audit d'accès aux secrets devient réel.
