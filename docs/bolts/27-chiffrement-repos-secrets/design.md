# Design — Ticket #27 : Chiffrement au repos et gestion des secrets

## Approche

Ce ticket est de nature conformite/documentation, pas une fonctionnalite applicative : il n'existe dans ce repo aucune infra staging/prod reelle (pas de Terraform/K8s/cloud), seulement un `docker-compose.yml` de developpement local. La strategie retenue distingue explicitement trois niveaux de livrable au lieu d'en faire un seul bloc homogene : (1) un audit factuel de l'existant (deja fait ci-dessous, resultat : globalement conforme, rien a corriger dans le code de config) ; (2) un outillage CI verifiable dans ce repo (scan de secrets gitleaks sur l'historique complet) — la seule contribution de code legitime de ce ticket ; (3) une documentation de procedure (`docs/securite/`) pour le chiffrement au repos DB/MinIO et pour TLS, explicitement scopee "a executer lors du provisioning reel de staging/prod" plutot qu'une fausse configuration Spring Boot qui donnerait l'illusion que c'est deja gere par le code. Prix de ce choix : aucune case de la conformite paragraphe 10.4 n'est "activee" a la fin de ce ticket (chiffrement disque, TLS reel) — seulement documentee et rendue verifiable au bon moment, ce qui est honnete etant donne l'absence d'infra, mais deplait a un lecteur qui s'attendrait a une implementation.

## Resultat de l'audit (fait pendant la conception, a formaliser dans les documents)

Secrets deja externalises — conforme, verifie fichier par fichier :
- `backend/src/main/resources/application.yml` (racine, chargee dans tous les profils) : JWT_SECRET, PHOTO_CLE_CHIFFREMENT, ALERTE_CLE_CHIFFREMENT, SMS_API_KEY, RABBITMQ_PASSWORD sont tous en variable d'environnement sans defaut — aucune valeur sensible en dur.
- `application-prod.yml` / `application-staging.yml` : DB_USER, DB_PASSWORD, MINIO_ACCESS_KEY, MINIO_SECRET_KEY sans defaut non plus.
- `application-dev.yml` : defauts presents (samapiece_minio / samapiece_minio_password / samapiece_dev_master_key_change_me) mais ce sont des mots de passe de developpement triviaux, distincts d'un secret de prod en dur — acceptable et deja le pattern du repo.
- `backend/src/test/resources/application.yml` : valeurs factices explicitement nommees (test-secret-uniquement-pour-les-tests-automatises-ne-jamais-reutiliser, cles AES bidon en A/B repetes) — pas des secrets, juste des fixtures de test.
- `docker-compose.yml` : tous les secrets sont relayes via variable d'environnement depuis l'hote, aucune valeur en dur.
- `.env.example` : uniquement des placeholders explicitement marques ("changez_moi_avec_...", commentaires "Ne jamais utiliser cette valeur d'exemple en staging/prod") ou des identifiants dev triviaux — jamais une vraie cle/mot de passe.

Conclusion : critere d'acceptation 2 deja satisfait par le code existant, sans modification necessaire des fichiers de config.

Recherche de fuites dans l'historique git — methode et resultat :
Commande utilisee : `git log --all -p` sur les fichiers `.env`, `application*.yml` et `docker-compose.yml` (historique complet, pas seulement HEAD), filtree par regex sur les motifs password/secret/api-key, puis exclusion manuelle des faux positifs (placeholders, references a des variables d'environnement, commentaires). Complement : `git log --all --diff-filter=D --name-only` pour verifier qu'aucun fichier .env/.pem/.key/credential n'a jamais ete commite puis supprime (ce qui laisserait le secret recuperable dans l'historique malgre sa suppression apparente).
Resultat : aucun secret reel trouve dans l'historique. Les seules occurrences des motifs recherches correspondent au fichier de test `backend/src/test/resources/application.yml` (valeurs factices identiques a celles d'aujourd'hui, introduites directement comme telles). Aucun fichier .env n'a jamais ete versionne. Cette methode et ce resultat doivent etre transcrits tels quels (reproductibles par le codeur) dans le document de revue attendu par le critere 4.

TLS : aucune configuration TLS/reverse proxy n'existe dans le repo (`frontend/nginx.conf` sert du HTTP nu sur le port 80, usage dev uniquement dans docker-compose.yml). Rien a auditer de plus : il n'y a simplement rien a ce niveau aujourd'hui.

## Fichiers/modules impactes

A creer (nouveau repertoire `docs/securite/`, premier ticket a y toucher) :
- `docs/securite/gestion-secrets-et-revue-historique.md` — formalise l'audit ci-dessus (methode + resultat) pour repondre au critere 4 ; sert aussi de reference pour la regle "aucun secret en dur" appliquee aux tickets futurs.
- `docs/securite/chiffrement-au-repos.md` — repartition des responsabilites (plateforme d'hebergement vs application), options concretes selon le choix d'hebergement (PostgreSQL/MinIO manages -> chiffrement de disque du fournisseur ; auto-heberge sur volume Docker -> chiffrement au niveau du systeme de fichiers hote type LUKS), checklist a cocher avant toute mise en environnement partage. Explicitement marque "non applicable aujourd'hui, aucun staging/prod provisionne".
- `docs/securite/tls-flux-externes.md` — checklist TLS 1.2+ obligatoire (portail public, app agent, API, webhooks SMS) a executer/verifier au moment du deploiement reel ; pas de test automatise possible en l'absence d'infra.
- `.gitleaks.toml` (racine du repo) — configuration/allowlist pour le scan (voir Decisions cles) afin d'eviter les faux positifs sur les fixtures de test connues.
- `.github/workflows/secrets-scan.yml` — nouveau workflow CI independant executant gitleaks sur l'historique complet.

A ne PAS modifier (deja conformes, verifie ci-dessus) : `backend/src/main/resources/application*.yml`, `docker-compose.yml`, `.env.example`, `.gitignore`.

## Decisions cles

1. Repartition code vs documentation par critere d'acceptation :
   - Critere 1 (chiffrement repos DB/MinIO) -> documentation seule (chiffrement-au-repos.md), responsabilite infra/plateforme, hors du code applicatif.
   - Critere 2 (secrets jamais commites) -> deja conforme (audit ci-dessus), aucun changement de code requis ; renforce pour l'avenir par le scan CI (le critere 4 outille aussi ce point en continu).
   - Critere 3 (TLS obligatoire) -> checklist documentee (tls-flux-externes.md), pas de test automatise possible sans reverse proxy/infra reelle.
   - Critere 4 (revue documentee de l'historique git) -> document factuel (gestion-secrets-et-revue-historique.md) transcrivant la methode et le resultat de l'audit ci-dessus, complete par le scan gitleaks en CI comme garde-fou continu (pas seulement une revue ponctuelle).

2. `docs/securite/` distinct de `docs/bolts/27-.../` : `docs/bolts/27-.../` reste le suivi de travail de ce ticket (design/spec/review, convention du repo) ; `docs/securite/` est la reference vivante que les tickets futurs pourront relire (cf. paragraphes 10.4/11.5 du PROJET), plus adaptee a un contenu qui doit rester a jour au-dela de ce seul ticket.

3. gitleaks en deux jobs complementaires (revise pendant la revue, correction d'une erreur de conception initiale) : le job `gitleaks-action@v2` sur push/PR ne scanne en realite que les commits introduits par l'evenement (base..head), pas l'historique complet, quel que soit `fetch-depth` du checkout (`fetch-depth: 0` ne conditionne que le checkout, pas la plage analysee par l'action). Design initial errone sur ce point, corrige par un second job planifie hebdomadairement (`schedule:`) qui rescanne tout l'historique via l'image Docker officielle gitleaks (`--log-opts="--all"`), pour obtenir a la fois la reactivite (diff a chaque push/PR) et la couverture complete en continu (rescan hebdomadaire), au lieu d'un seul job qui pretendait a tort couvrir les deux. Workflow separe de backend.yml/frontend.yml pour ne pas coupler un faux positif de scan de secrets a la CI applicative existante.

4. Allowlist .gitleaks.toml minimale et justifiee : n'exclure que les chaines de test deja identifiees (le secret JWT de test et les cles AES factices de backend/src/test/resources/application.yml) et rien d'autre, pour ne pas risquer de masquer une vraie fuite future par une regle trop large.

## Risques / points d'attention

- Ambition realiste : chiffrement-au-repos.md et tls-flux-externes.md doivent etre ecrits comme des procedures a executer plus tard (avec proprietaire et checklist), pas comme des fonctionnalites "faites" — risque de fausse assurance de conformite si le ton n'est pas explicite sur ce point pour le spec-writer/reviewer suivants.
- Ecart Vault/KMS non traite : le PROJET (paragraphes 10.4/11.4) recommande Vault/KMS pour la gestion des secrets et cles de chiffrement ; aujourd'hui tout passe par variables d'environnement/.env. C'est un ecart reel mais son introduction est un chantier d'infra a part entiere, disproportionne pour ce ticket — a signaler comme point ouvert/ticket futur, pas a forcer ici.
- PHOTO_CLE_CHIFFREMENT/ALERTE_CLE_CHIFFREMENT (deja implementes en #13/#22) sont du chiffrement applicatif au niveau champ, distinct du chiffrement au repos infra vise par le critere 1 — a ne pas confondre dans la doc, sous peine de laisser croire que le critere 1 est deja rempli par du code existant.
- Cout CI du scan historique complet : deja traite par la decision 3 revisee (scan diff-only a chaque push/PR + scan complet planifie hebdomadaire) — plus un point ouvert, c'est desormais l'architecture retenue, verifiee empiriquement en revue (voir review.md).
- Faux positifs gitleaks : les cles factices de test ou les identifiants dev triviaux (samapiece_dev_master_key_change_me, etc.) pourraient declencher des regles par defaut de gitleaks selon leur entropie — a verifier au premier run avant de rendre le workflow bloquant (cf. decision 3).
- Perimetre TLS non verifiable par un test : aucune infra reelle n'existe dans ce repo pour executer/valider TLS ; le critere "verifiable" ne peut se traduire ici que par une checklist humaine au moment du deploiement reel — le spec-writer doit garder des criteres d'acceptation coherents avec cette limite (pas de test automatise a inventer).

## Hors perimetre

- Provisionner une vraie infra staging/prod (Terraform, Kubernetes, cloud) — n'existe pas dans ce repo et ce ticket ne la cree pas.
- Introduire HashiCorp Vault ou un KMS — ecart reel signale, mais ticket futur distinct.
- Toute configuration reelle de chiffrement de disque PostgreSQL/MinIO ou de terminaison TLS (reverse proxy, certificats) — releve de la plateforme d'hebergement au moment du deploiement reel, pas du code de ce repo.
- Reecriture de l'historique git (BFG/filter-repo) — l'audit n'a trouve aucun secret reel a purger.
- Modification de PHOTO_CLE_CHIFFREMENT/ALERTE_CLE_CHIFFREMENT (chiffrement applicatif deja livre par #13/#22).
- Pentest, EIPD/PIA (paragraphe 10.1 du PROJET) — activites de gouvernance separees, hors code/doc de ce ticket.
- OWASP Dependency-Check / SCA (mentionne au paragraphe 11.9 du PROJET mais distinct de la gestion des secrets) — pas traite ici.
