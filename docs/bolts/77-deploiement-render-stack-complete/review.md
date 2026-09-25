# Review — Ticket #77 : Déploiement de la stack complète sur Render (démo/suivi client)

APPROVE

## Contexte de la revue (deuxième et dernier passage)

Ceci est le **deuxième passage** de review sur ce bolt. Le premier passage (commit `c564809`,
verdict `CHANGES_REQUESTED`) avait relevé un seul finding bloquant, vérifié empiriquement à l'époque :
`render.yaml` omettait `SMS_SENDER_ID` dans le bloc `envVars` du service `samapiece-backend`, alors que
`backend/src/main/resources/application.yml` (`sender-id: ${SMS_SENDER_ID}`, aucun défaut) alimente
`SmsProperties.senderId` (`@NotBlank`). Le premier passage avait constaté que Spring Boot ne fait pas
échouer le démarrage dans ce cas précis (le placeholder non résolu passe la validation `@NotBlank`
littéralement), mais que le sender-id envoyé à la passerelle SMS serait alors la chaîne littérale
`${SMS_SENDER_ID}` au lieu d'un identifiant valide — un Blueprint objectivement incomplet.

Le codeur a corrigé ce point dans le commit `29190ae` (`fix(#77): ajoute SMS_SENDER_ID manquant dans
render.yaml`).

### Vérification du correctif

- `git diff c564809..29190ae` et `git diff 0054643..29190ae --name-only` : le diff touche exactement
  deux fichiers : `render.yaml` (2 lignes ajoutées) et
  `docs/bolts/77-deploiement-render-stack-complete/review.md` (ajout du rapport du premier passage,
  déjà pris en compte). Aucun autre fichier n'a changé depuis `0054643` — en particulier
  `application-staging.yml`, `nginx.conf.template`, `Dockerfile`, `docker-compose.yml`,
  `.env.example` et `README.md` sont strictement identiques à ce qu'ils étaient au premier passage :
  pas de régression possible sur ces points déjà validés.
- Contenu exact du diff `render.yaml` :
  ```
  @@ -125,6 +125,8 @@ services:
           value: "http://localhost:1" # pas de passerelle SMS reelle pour la demo, cf. .env.example dev
         - key: SMS_API_KEY
           sync: false
  +      - key: SMS_SENDER_ID
  +        value: SamaPiece
         - key: MINIO_ACCESS_KEY
           sync: false # meme valeur que MINIO_ROOT_USER (duplication assumee, cf. en-tete)
  ```
  Placement cohérent (juste après `SMS_API_ENDPOINT`/`SMS_API_KEY`, dans le bloc `envVars` du service
  `samapiece-backend`), aucune ligne parasite ajoutée ailleurs dans le fichier.
- Valeur retenue : `SamaPiece`, en clair (pas de `sync: false`). C'est cohérent et correct : ce n'est
  pas un secret (ni numéro de document, ni contact citoyen au sens du §10 de `PROJET-SAMAPIECE.md`),
  simplement l'identifiant d'expéditeur affiché sur les SMS sortants — la même valeur non sensible que
  celle déjà documentée dans `.env.example` (`SMS_SENDER_ID=SamaPiece`, ligne 69) et injectée telle
  quelle par `docker-compose.yml` (`SMS_SENDER_ID: ${SMS_SENDER_ID}`, ligne 36). Traitement identique à
  celui déjà réservé à `MINIO_BUCKET_PHOTOS`/`MEILISEARCH_INDEX_PIECES` dans ce même fichier.
- Confirmation que `backend/src/main/resources/application-staging.yml` ne redéfinit pas
  `notifications.sms.sender-id` (grep ciblé, aucun résultat) : la valeur injectée par `render.yaml`
  est bien celle utilisée en profil `staging`, sans court-circuit.
- Revalidation YAML indépendante : `python -c "import yaml; d=yaml.safe_load(open('render.yaml'));
  print('OK', type(d))"` → `OK <class 'dict'>`, aucune erreur de parsing.
- Aucun effet de bord identifié : l'ajout est une simple paire clé/valeur supplémentaire dans une
  liste YAML déjà valide, sans interaction avec les autres clés (`fromDatabase`, `fromService`,
  `fromGroup`, `sync: false`) ni avec le Dockerfile ou `docker-compose.yml`, tous inchangés. Aucun
  besoin de refaire le build Docker complet / `docker compose up` / `mvn test` déjà exécutés et
  documentés au premier passage : rien dans ce diff d'une ligne ne remet en cause ces vérifications.

Tous les autres critères d'acceptation et findings validés au premier passage restent inchangés et ne
sont pas rouverts ici (cf. historique du fichier, commit `c564809`, pour le détail complet de la
vérification initiale : health check local, non-régression `docker compose`, migrations Flyway,
`mvn -pl backend test`, absence de secret en clair, périmètre "pas de déploiement réel" respecté).

## Critères d'acceptation

| Critère (ticket #77) | Statut | Commentaire |
|---|---|---|
| Health check backend 200 en ligne | Non applicable à ce bolt (documenté) | Inchangé depuis le premier passage — hors de portée sans compte Render, correctement acté comme étape manuelle humaine. |
| Parcours fonctionnel bout-en-bout | Non applicable à ce bolt (documenté) | Inchangé — README ne prétend jamais qu'un parcours réel a été vérifié en ligne. |
| Tous les services réellement utilisés (pas de fallback silencieux) | Couvert | Le seul écart relevé au premier passage (SMS_SENDER_ID manquant) est corrigé et vérifié ci-dessus. Postgres/Redis/Meilisearch/MinIO/RabbitMQ/SMS tous correctement déclarés et injectés. |
| Premier compte admin utilisable dès le déploiement initial | Couvert (dans la limite du vérifiable) | Inchangé depuis le premier passage, fichier non touché par ce correctif. |
| Aucun secret en clair dans render.yaml | Couvert | `SMS_SENDER_ID=SamaPiece` n'est pas un secret (identifiant d'expéditeur SMS, pas une donnée du §10 PROJET-SAMAPIECE.md) ; cohérent avec le traitement déjà en clair de `MINIO_BUCKET_PHOTOS`/`MEILISEARCH_INDEX_PIECES`. Reste du fichier inchangé et déjà validé. |
| Procédure documentée | Couvert | README non modifié par ce correctif, déjà validé au premier passage. |
| Non-régression locale (docker-compose, application-staging.yml) | Couvert | Fichiers concernés strictement inchangés depuis `0054643` (confirmé par `git diff --name-only`) ; aucune régression possible. |

## Findings

Aucun finding bloquant. Le seul point relevé au premier passage (`SMS_SENDER_ID` manquant dans
`render.yaml`) est corrigé de façon chirurgicale, correcte et sans effet de bord.

## Build/tests

- `git diff c564809..29190ae` et `git diff 0054643..29190ae --name-only` : diff minimal confirmé (2
  fichiers, dont un seul fichier de code de configuration modifié — `render.yaml`, +2 lignes).
- `python -c "import yaml; d=yaml.safe_load(open('render.yaml')); print('OK', type(d))"` : `OK
  <class 'dict'>` — YAML valide, revalidé indépendamment du rapport du codeur.
- Grep de non-régression sur `application-staging.yml`/`.env.example`/`docker-compose.yml` pour
  `SMS_SENDER_ID`/`sender-id` : cohérence confirmée entre les trois fichiers et la nouvelle entrée de
  `render.yaml`.
- Pas de nouvelle exécution de `mvn -pl backend test` ni de `docker compose up` pour ce passage : le
  diff ne touche ni le code Java, ni les tests, ni `docker-compose.yml`, ni le Dockerfile — ces
  vérifications lourdes, déjà exécutées et documentées en détail au premier passage (223 tests, 0
  failure, 24 erreurs Testcontainers/Docker Desktop préexistantes et indépendantes du diff ; `docker
  compose up` healthy de bout en bout), restent valables et ne sont pas remises en cause par l'ajout
  d'une seule paire clé/valeur non sensible dans `render.yaml`.

## Conclusion

Le finding bloquant du premier passage est résolu par un correctif minimal, correct et sans
régression. Le Blueprint `render.yaml` déclare désormais l'ensemble des variables requises par le code
existant pour le service `samapiece-backend`, sans exposer de secret en clair. Approbation.
