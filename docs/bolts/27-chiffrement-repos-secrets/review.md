# Review — Ticket #27 : Chiffrement au repos et gestion des secrets

APPROVE

## Contexte de cette revue (deuxieme passage)

Premier passage : CHANGES_REQUESTED, un seul finding bloquant (Finding 1) - la documentation affirmait a tort que secrets-scan.yml scannait l'historique complet du depot a chaque push/pull_request grace a fetch-depth: 0, alors que gitleaks-action@v2 ne scanne en realite que les commits introduits par l'evenement (verifie empiriquement par moi lors du premier passage : run 34835141977, 4 commits scanned sur 177). Le second finding (process) etait deja resolu pendant le premier passage puisque c'est moi (reviewer) qui avais pousse la branche et verifie le run manquant.

Correctif du bolt-coder (commit 5b3f601) : refonte de secrets-scan.yml en deux jobs - gitleaks-diff (push/PR, comportement inchange mais honnetement documente comme scan du diff introduit) et gitleaks-full-history (schedule hebdomadaire lundi 3h + workflow_dispatch, image Docker officielle zricethezav/gitleaks:latest avec --log-opts=--all, vrai rescan de tout l'historique). Documents mis a jour en coherence : design.md, spec.md, docs/securite/gestion-secrets-et-revue-historique.md (section 6).

Verifications effectuees pour ce deuxieme passage :

- Lecture ligne a ligne du YAML final de `secrets-scan.yml` (deux jobs, conditions `if: github.event_name == ...` mutuellement exclusives, commentaires honnetes sur la portee reelle de chaque job).
- `git diff 67457cd..5b3f601 --stat` : seuls `secrets-scan.yml`, `design.md`, `spec.md`, `gestion-secrets-et-revue-historique.md` modifies par le correctif (le `review.md` du premier passage, ajoute par un commit intermediaire, n'a pas ete retouche par le correctif - verifie par `git diff ecf088c..5b3f601 -- review.md`, vide).
- `git diff main..bolt/issue-27-chiffrement-repos-secrets --name-status` sur l'ensemble de la branche : toujours uniquement les 8 fichiers deja valides au premier passage (`.gitleaks.toml`, `secrets-scan.yml`, les 3 docs `docs/bolts/27-.../`, les 3 docs `docs/securite/*.md`). Confirme explicitement : aucun fichier `backend/`, `frontend/`, `backend.yml` ou `frontend.yml` touche par cette correction ni par le reste de la branche.
- Relecture complete de `docs/securite/gestion-secrets-et-revue-historique.md` section 6 (le document de conformite qu'un futur auditeur consultera) : decrit fidelement les deux jobs, leur portee reelle respective, reference explicitement le run GitHub Actions 34835141977 et le comptage 4 vs 177 commits qui a motive la correction, et assume explicitement le delai de garantie (citation : "Ce que cela signifie concretement pour un lecteur de ce document : entre deux executions planifiees de gitleaks-full-history (jusqu'a une semaine), un secret reintroduit... pourrait ne pas etre detecte immediatement"). Aucune affirmation residuelle ne pretend qu'un scan complet a lieu a chaque push/PR.
- Recherche de motifs residuels ("historique complet", "fetch-depth", "chaque push") dans tout `docs/` : `chiffrement-au-repos.md` et `tls-flux-externes.md` ne mentionnent pas gitleaks/secrets-scan (aucune regression possible sur ces deux documents, coherent avec le premier passage). Seul residu trouve : `design.md` ligne 32, une puce de la liste "Fichiers/modules impactes" encore formulee "nouveau workflow CI independant executant gitleaks sur l'historique complet" - imprecise (elle decrit le workflow dans son ensemble, dont un seul des deux jobs scanne reellement tout l'historique, l'autre seulement le diff) mais non bloquante : c'est un document de suivi de travail interne au ticket, pas le document de conformite destine a un lecteur futur (celui-ci, section 6, est corrige sans ambiguite), et la decision cle 3 du meme fichier (ligne 46) et le risque correspondant (ligne 55) sont corrects et explicites juste en dessous. Signale pour memoire, sans effet sur le verdict.
- Verification empirique independante des deux runs produits par l'orchestrateur (au lieu de me refier au seul recit) :
  - Run `34838809747` (declenche par le push du commit `5b3f601`) : `gh run view --json jobs` confirme `gitleaks-diff` = success, `gitleaks-full-history` = skipped. Comportement attendu (ni schedule ni workflow_dispatch).
  - Run `34838891733` (declenche manuellement) : `gitleaks-full-history` = success, `gitleaks-diff` = skipped. Log confirme ligne par ligne : `179 commits scanned.` / `no leaks found`. Comportement attendu, et confirme que le job planifie/manuel scanne bien reellement tout l'historique (179 commits a ce jour, coherent avec la croissance du depot depuis les 177 du premier passage).
- Le YAML est donc coherent avec le comportement reellement observe sur les deux runs, pas seulement plausible sur le papier.
- Re-verification rapide (pas de re-scan complet, rien n'a change sur ces points) : `.gitleaks.toml` intact, aucune entree d'allowlist ajoutee injustement ; le raisonnement sur la licence `gitleaks-action@v2` (depot personnel/public, pas de `GITLEAKS_LICENSE` requis) n'est pas affecte par le correctif ; ton honnete des trois documents `docs/securite/` maintenu.

## Criteres d'acceptation

| # | Critere | Statut |
|---|---|---|
| 1 | Chiffrement au repos active sur PostgreSQL/MinIO en staging/prod | Non applicable aujourd'hui (aucune infra staging/prod dans ce repo), honnetement documente comme procedure a executer au provisioning reel dans `docs/securite/chiffrement-au-repos.md`. Inchange depuis le premier passage. |
| 2 | Secrets jamais commis, charges depuis env/gestionnaire de secrets | Couvert : audit verifie independamment (aucun defaut en dur en profil prod/staging) et garde-fou CI maintenant honnetement documente et verifie empiriquement en conditions reelles sur les deux modes de declenchement (diff immediat a chaque push/PR + rescan complet hebdomadaire/a la demande). Finding 1 du premier passage resolu. |
| 3 | TLS obligatoire sur tous les flux externes, documente/verifiable | Non applicable aujourd'hui, checklist actionnable dans `docs/securite/tls-flux-externes.md`. Inchange depuis le premier passage. |
| 4 | Revue documentee confirmant qu'aucun secret n'apparait dans l'historique git | Couvert et verifie independamment (hash HEAD, commandes reproductibles, resultat 0 secret) - inchange, plus desormais complete par un garde-fou continu dont la portee reelle est honnetement assumee. |

## Findings

Aucun finding bloquant. Point mineur pour memoire (non bloquant, ne conditionne pas le verdict) :

- `docs/bolts/27-chiffrement-repos-secrets/design.md:32` conserve une formulation imprecise ("nouveau workflow CI independant executant gitleaks sur l'historique complet") heritee d'avant la correction, dans la liste sommaire des fichiers impactes. Sans consequence pratique : c'est un document de suivi de conception interne, la decision cle 3 (ligne 46) du meme document et le document de conformite reel (`gestion-secrets-et-revue-historique.md` section 6) sont corrects et sans ambiguite. A nettoyer par coherence si le fichier est retouche pour une raison future, pas une raison de rouvrir ce ticket.

## Build/tests

- Pas de build/test Java ou frontend pertinent pour ce ticket (nature documentaire/outillage CI, aucun fichier `backend/`/`frontend/` touche - confirme par `git diff main..bolt/issue-27-chiffrement-repos-secrets --name-status`).
- `gh run view 34838809747 --json jobs` -> `gitleaks-diff`: success, `gitleaks-full-history`: skipped (declenchement push, comportement attendu).
- `gh run view 34838891733 --json jobs` -> `gitleaks-full-history`: success, `gitleaks-diff`: skipped (declenchement workflow_dispatch, comportement attendu).
- `gh run view 34838891733 --log` -> confirme `179 commits scanned.` / `no leaks found` pour le job de rescan complet.
- Recherche de motifs residuels ("historique complet", "fetch-depth", "chaque push") dans `docs/` -> aucune affirmation trompeuse restante dans les documents de conformite (`gestion-secrets-et-revue-historique.md`), seul un residu mineur non bloquant dans `design.md` (voir Findings).

## Verdict

APPROVE - le Finding 1 (bloquant) du premier passage est corrige au niveau du code (workflow a deux jobs honnetement scopes) et de la documentation (`design.md`, `spec.md`, `gestion-secrets-et-revue-historique.md` section 6), et verifie empiriquement a deux reprises : par moi lors du premier passage sur l'ancien comportement fautif (run 34835141977, 4/177 commits), et par l'orchestrateur sur le nouveau comportement corrige (runs 34838809747 et 34838891733, ce dernier confirmant 179 commits scannes sans finding). Le delai de garantie (jusqu'a une semaine entre deux scans complets automatiques) est explicitement assume dans le document de conformite, pas dissimule. Le Finding 2 (process) du premier passage est sans objet, la verification empirique ayant maintenant ete effectuee deux fois par l'orchestrateur. Le reste du livrable (audit, revue d'historique, documents de procedure, absence de fichier applicatif modifie) reste solide, inchange depuis le premier passage.
