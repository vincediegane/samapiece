# Design — #10 Modèle Piece (numéro hashé + masqué) + migration Flyway

## Approche

On ajoute au package existant `sn.samapiece.enregistrement` (déjà créé, vide depuis #1 — c'est le bon
emplacement, confirmé par son javadoc de `package-info.java`) l'entité JPA `Piece`, l'enum `StatutPiece`
(+ converter), l'enum `TypeDocument` (+ converter) et un composant `NumeroDocumentHasher` qui
encapsule toute la logique de hachage/salage/masquage. Le numéro en clair ne transite **jamais** par
un champ ou un setter de l'entité `Piece` : seul le triplet déjà dérivé (hash, sel, masqué) est
accepté par son constructeur, exactement comme `Agent` n'accepte qu'un `hashMotDePasse` déjà calculé
par `PasswordEncoder` en amont. Le hachage retenu est **SHA-256 + sel aléatoire par enregistrement
stocké en colonne dédiée** (pas BCrypt) — voir justification dans Décisions clés. Prix payé : on
n'a pas la robustesse anti-bruteforce volontairement lente de BCrypt, mais ce n'est pas l'usage visé
(comparaison exacte, pas protection d'un secret à faible entropie consulté en 1-vers-1 comme un mot
de passe).

## Fichiers/modules impactés

Tous nouveaux (aucun fichier existant modifié) :

- `backend/src/main/resources/db/migration/V4__create_piece.sql` — migration (suite logique de V3).
- `backend/src/main/java/sn/samapiece/enregistrement/Piece.java` — entité JPA.
- `backend/src/main/java/sn/samapiece/enregistrement/StatutPiece.java` — enum de statut (7 valeurs de l'AC).
- `backend/src/main/java/sn/samapiece/enregistrement/StatutPieceConverter.java` — `AttributeConverter`, même
  patron que `RoleConverter`/`TypePosteConverter` (minuscules en base + `CHECK`).
- `backend/src/main/java/sn/samapiece/enregistrement/TypeDocument.java` — enum de la liste fermée du §5
  (`CNI, PASSEPORT, PERMIS_CONDUIRE, CARTE_ELECTEUR, EXTRAIT_NAISSANCE, CARTE_GRISE, CARTE_CONSULAIRE, AUTRE`).
- `backend/src/main/java/sn/samapiece/enregistrement/TypeDocumentConverter.java` — même patron converter/CHECK.
- `backend/src/main/java/sn/samapiece/enregistrement/NumeroDocumentHasher.java` — `@Component` :
  `NumeroDocumentHache hacher(String numeroClair)` (génère sel + hash + masqué) et
  `NumeroDocumentMasque` = record `(String hash, String sel, String masque)`.
- `backend/src/test/java/sn/samapiece/enregistrement/PieceTest.java` — test unitaire pur (pas de
  Spring/DB) garantissant qu'aucun champ de l'entité ne porte le numéro en clair.
- `backend/src/test/java/sn/samapiece/enregistrement/NumeroDocumentHasherTest.java` — tests du sel
  unique par appel, du format du masqué, et de non-réversibilité triviale.

Référencés en lecture seule (FK, style) : `sn.samapiece.referentiel.Poste`, `sn.samapiece.iam.Agent`,
`V1__create_region_poste.sql`, `V2__create_agent.sql`, `V3__ajoute_verrouillage_agent.sql`.

## Décisions clés

1. **SHA-256 + colonne `numero_document_sel` (SecureRandom, 16 octets, hex) plutôt que BCrypt.**
   BCrypt (déjà utilisé pour les mots de passe via `SecurityConfig.passwordEncoder()`) intègre son
   sel dans le hash et est délibérément lent (~coût 10) — adapté à une vérification 1-contre-1 au
   login. Le §7.6.2 documente un futur besoin de **détection de doublons par numéro de document
   identique** (ticket #15). Or un sel unique par enregistrement (imposé par le critère d'acceptation
   et par le §10.4 de PROJET-SAMAPIECE.md) interdit de toute façon une recherche par égalité indexée
   sur le hash : même numéro + sels différents ⇒ hash différents, que ce soit avec BCrypt ou
   SHA-256+sel. La détection de doublon devra donc recalculer le hash candidat avec le sel de
   chaque fiche existante du périmètre (poste/région/type) et comparer — un balayage O(n). SHA-256
   est de l'ordre de la microseconde par calcul, ce qui rend ce balayage tenable à l'échelle du
   volume annoncé (dizaines de milliers de fiches/an) ; BCrypt (~100 ms/calcul) le rendrait
   impraticable dès quelques milliers de comparaisons. Le sel protège contre la pré-fabrication
   d'une table arc-en-ciel globale sur des numéros de document structurés (entropie limitée), ce qui
   est la menace pertinente pour une fuite de la base au repos.
2. **Format du masqué : conserve les 2 premiers et 2 derniers caractères en clair (4 max, conforme au
   critère et à l'exemple `12●●●●89` du §9.2), masque le reste avec `●` en préservant la longueur
   réelle.** Si le numéro fait 4 caractères ou moins, aucun caractère n'est révélé (masquage complet)
   pour éviter qu'un numéro court ne soit quasiment entièrement lisible. Pas de format spécifique par
   type de document (CNI vs passeport) : PROJET-SAMAPIECE.md ne documente aucune règle différenciée,
   et l'ajouter serait de la sur-ingénierie non demandée par le ticket.
3. **Garantie architecturale (pas seulement conventionnelle) qu'aucun numéro en clair n'est
   persistable** : `Piece` n'a ni champ, ni setter, ni constructeur acceptant une chaîne "numéro
   brut" — son seul constructeur public prend `numeroDocumentHash`, `numeroDocumentSel`,
   `numeroDocumentMasque` déjà calculés. Le calcul du triplet est isolé dans
   `NumeroDocumentHasher`, composant Spring sans dépendance JPA, qui ne connaît pas `Piece`. Le futur
   contrôleur (#11) devra donc obligatoirement passer par ce composant avant de construire une
   `Piece` — il n'existe aucun chemin de code qui permette de contourner ce calcul et de stocker le
   numéro tel quel. Le test unitaire `PieceTest` vérifie cette garantie par réflexion : il construit
   une `Piece` à partir d'un numéro connu, puis itère sur tous les champs déclarés de la classe et
   affirme qu'aucun ne contient la valeur brute ni son nom en toutes lettres (`numeroDocument` sans
   suffixe `Hash`/`Sel`/`Masque`) — ce qui détecterait aussi une régression future (ex. un champ
   ajouté par erreur).
4. **`TypeDocument` en enum + converter** (au lieu d'un simple `VARCHAR` libre), pour rester cohérent
   avec le patron déjà établi (`TypePoste`, `Role`) et parce que le §5 de PROJET-SAMAPIECE.md définit
   explicitement une liste fermée. Ce n'est pas demandé littéralement par les critères d'acceptation
   du ticket (qui ne listent que `StatutPiece` comme enum) — à confirmer/trancher par le spec-writer
   si le périmètre doit être réduit à un simple `VARCHAR` + `CHECK` sans enum Java.
5. **`etat_document` reste un `VARCHAR` libre nullable**, sans enum : aucune liste fermée n'est
   documentée dans PROJET-SAMAPIECE.md pour l'état physique du document (seuls des exemples textuels
   comme "partiellement illisible" apparaissent en §8.2).
6. **Schéma de la table `piece` (V4)** :
   ```sql
   CREATE TABLE piece (
       id                      UUID PRIMARY KEY,
       poste_id                UUID NOT NULL REFERENCES poste(id),
       agent_createur_id       UUID NOT NULL REFERENCES agent(id),
       type_document           VARCHAR(30) NOT NULL CHECK (type_document IN
           ('cni', 'passeport', 'permis_conduire', 'carte_electeur',
            'extrait_naissance', 'carte_grise', 'carte_consulaire', 'autre')),
       nom_titulaire           VARCHAR(255) NOT NULL,
       prenom_titulaire        VARCHAR(255) NOT NULL,
       numero_document_hash    VARCHAR(64) NOT NULL,
       numero_document_sel     VARCHAR(64) NOT NULL,
       numero_document_masque  VARCHAR(64) NOT NULL,
       date_naissance_titulaire DATE,
       date_depot              DATE NOT NULL,
       etat_document           VARCHAR(255),
       statut                  VARCHAR(20) NOT NULL DEFAULT 'disponible' CHECK (statut IN
           ('disponible', 'reclamee', 'retiree', 'litige', 'archivee', 'detruite', 'signalee')),
       remarques               TEXT,
       cree_le                 TIMESTAMPTZ NOT NULL DEFAULT now(),
       maj_le                  TIMESTAMPTZ NOT NULL DEFAULT now()
   );

   CREATE INDEX idx_piece_poste_id ON piece(poste_id);
   CREATE INDEX idx_piece_agent_createur_id ON piece(agent_createur_id);
   CREATE INDEX idx_piece_statut ON piece(statut);
   ```
   Pas d'index/contrainte `UNIQUE` sur `numero_document_hash` : inutile tant que le sel est unique
   par ligne (deux fiches du même numéro auront des hash différents), et prématuré vis-à-vis du
   mécanisme que choisira #15. Divergence assumée par rapport au diagramme ER illustratif du §9.1 (qui
   ne montre pas de colonne "sel") : ce diagramme est une vue de synthèse, pas un schéma final —
   `numero_document_sel` est un ajout nécessaire à l'implémentation du principe "sel unique par
   enregistrement" du §9.2/§10.4.
7. **Statut initial `disponible` par défaut.** L'état transitoire "déposée" du cycle de vie décrit au
   §7.6.4 n'a pas de valeur dédiée dans l'enum imposé par ce ticket (7 valeurs, sans `DEPOSEE`) : le
   dépôt et la mise à disposition sont donc traités comme un seul et même instant à la création de la
   fiche.
8. `date_naissance_titulaire` et `etat_document` sont nullables (tous les types de documents
   n'affichent pas une date de naissance, ex. carte grise) ; `nom_titulaire`, `prenom_titulaire`,
   `date_depot`, `type_document`, `statut` sont `NOT NULL`.

## Risques / points d'attention

- **Incompatibilité potentielle avec #15** si ce futur ticket suppose à tort une recherche de doublon
  par simple `WHERE numero_document_hash = ?` : ce n'est pas possible avec un sel par enregistrement.
  Le point doit être explicitement communiqué au spec-writer de #15 (recalcul + comparaison par
  balayage borné par poste/région/type/fenêtre de dates, ou introduction ultérieure d'un index
  déterministique séparé avec pepper applicatif global — hors périmètre de #10).
- **Le sel seul (sans pepper applicatif) n'apporte pas de résistance forte si la base entière fuit** :
  hash et sel sont stockés côte à côte, donc un attaquant avec un dump complet peut retester chaque
  numéro candidat par ligne (mais ne peut pas précalculer une table arc-en-ciel unique valable pour
  toutes les lignes). C'est un compromis documenté au §10.4 ("algorithme adapté"), pas une faille du
  ticket.
- **Aucune contrainte `UNIQUE`/index n'empêche la création de doublons exacts à ce stade** — c'est
  attendu (#15 traite la détection), mais il faut s'assurer que le spec-writer/codeur de #10 ne
  l'ajoute pas prématurément avec une sémantique fausse (une contrainte `UNIQUE(numero_document_hash)`
  serait incorrecte et sans effet réel vu le sel par ligne).
- **Longueur des colonnes hash/sel** : dimensionnées pour SHA-256 hex (64 caractères) et un sel de 16
  octets en hex (32 caractères, marge à 64) — si l'algorithme change plus tard, une migration
  `ALTER COLUMN` sera nécessaire.
- **`TypeDocument` en enum** est une extension au-delà du texte strict des critères d'acceptation
  (qui ne mentionnent que l'enum de statut) ; si le spec-writer préfère un simple `VARCHAR` sans enum
  Java pour limiter le scope, il faudra ajuster ce point du design.
- **Offline-first** : ce ticket ne touche pas le frontend agent ; aucune structure IndexedDB/synchro
  n'est concernée ici. Le futur endpoint de création (#11) devra être conçu compatible file d'attente
  de synchronisation, mais cela ne contraint rien dans le modèle de données actuel.

## Hors périmètre

- Aucun endpoint REST (création, lecture, listing de fiches) — c'est #11.
- Aucune logique de détection de doublons ni de fusion de fiches — c'est #15.
- Aucune gestion des photos (`PHOTO`), des retraits (`RETRAIT`) ou des événements d'audit
  (`EVENEMENT_AUDIT`) du diagramme §9.1 : ces entités ne sont pas dans le périmètre de ce ticket.
- Aucun RBAC/contrôle d'accès sur `Piece` (pas de repository Spring Data exposé via un contrôleur ici) —
  le scoping par poste/région pour les pièces suivra le même patron que `PerimetreRegional` (#8) mais
  sera introduit avec l'endpoint de #11, pas ici.
- Pas de trigger SQL de mise à jour automatique de `maj_le` sur `UPDATE` : le projet n'en a pas
  aujourd'hui pour `agent`/`poste` non plus ; rester cohérent avec l'existant plutôt que de corriger
  une lacune générale dans ce ticket.
