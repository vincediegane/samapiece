# Design — #18 Endpoint recherche publique anonymisée

## Approche

Architecture a deux etages, sans toucher au schema Meilisearch livre par #17 : **Meilisearch** sert
uniquement de shortlist rapide et tolerante aux fautes de frappe sur (typeDocument, nomTitulaire,
prenomTitulaire) restreinte a statut = DISPONIBLE ; **PostgreSQL reste la seule source de verite**
pour la decision finale — c'est la qu'on recharge les Piece candidates par id et qu'on verifie de
facon stricte le statut frais (le champ statut indexe dans Meilisearch est fige a la creation,
donc potentiellement obsolete tant que #24 n'a pas branche desindexer(...)), le nom/prenom exacts
(normalises), et le critere discriminant fourni (numero via re-hash avec le sel propre a chaque ligne
candidate, et/ou date de naissance exacte). Le prix de ce choix : on ne modifie pas le document
Meilisearch (aucun champ sensible ajoute, zero regression sur #17), et le balayage couteux du hash
sale par ligne (cf. #10) est borne a la shortlist Meilisearch — quelques lignes en pratique — au lieu
d'un scan complet de piece. En contrepartie, une correspondance necessite que Meilisearch soit
disponible ou un repli explicite vers une requete PostgreSQL directe (memes filtres) en cas de panne
de Meilisearch, pour ne pas rendre l'endpoint public totalement indisponible.

Anti-enumeration : la validation des criteres minimaux (type + nom + au moins un de numero/date) est
une regle purement syntaxique, evaluee avant tout acces aux donnees (ni Meilisearch ni PostgreSQL) —
la reponse "criteres insuffisants" est donc rigoureusement identique quel que soit le contenu soumis,
et ne peut jamais confirmer/infirmer l'existence d'un tiers. Une fois les criteres juges suffisants,
la reponse "trouve"/"non trouve" a la meme forme JSON (seul trouve et les champs optionnels varient),
avec le meme code HTTP 200 et un temps de traitement qui ne doit pas dependre du chemin (trouve vs
non trouve) de facon detectable au-dela de la variance normale.

## Fichiers/modules impactes

Aucun fichier de #17 n'est modifie (PieceRechercheDocument, PieceRechercheIndexService,
MeilisearchConfig, MeilisearchProperties, PieceIndexationListener restent inchanges).

Nouveaux fichiers (package sn.samapiece.recherche, le sous-module "requete publique" n'existe pas
encore — seule la partie indexation existe a ce jour) :
- backend/src/main/java/sn/samapiece/recherche/RecherchePubliqueService.java — orchestration
  Meilisearch (shortlist) + PostgreSQL (verification stricte), repli PostgreSQL direct si Meilisearch
  echoue.
- backend/src/main/java/sn/samapiece/recherche/CriteresInsuffisantsException.java — levee quand la
  regle "type + nom + (numero OU date)" n'est pas satisfaite.
- backend/src/main/java/sn/samapiece/recherche/web/RecherchePubliqueController.java —
  POST /api/v1/recherche-publique, public (pas de @PreAuthorize, permitAll() cote SecurityConfig).
- backend/src/main/java/sn/samapiece/recherche/web/RecherchePubliqueRequest.java — DTO requete
  (typeDocument, nomTitulaire, prenomTitulaire optionnel, numeroDocument optionnel,
  dateNaissanceTitulaire optionnel) + methode estSuffisant().
- backend/src/main/java/sn/samapiece/recherche/web/RecherchePubliqueResponse.java — DTO reponse
  (trouve: boolean, typeDocument, poste avec nom/adresse/horaires/telephone, referenceDossier — tous
  nullable si trouve = false).
- backend/src/main/java/sn/samapiece/recherche/web/RecherchePubliqueExceptionHandler.java —
  @RestControllerAdvice mappant CriteresInsuffisantsException vers 400 avec un
  ErreurReponse(code="CRITERES_INSUFFISANTS", message=...), sur le modele de
  sn.samapiece.iam.web.AuthExceptionHandler.

Fichiers modifies :
- backend/src/main/java/sn/samapiece/enregistrement/PieceRepository.java — ajout d'une methode de
  requete bornee, ex. findByTypeDocumentAndStatutAndNomTitulaireIgnoreCase(TypeDocument, StatutPiece,
  String) (utilisee pour la verification finale et pour le repli si Meilisearch est indisponible).
- backend/src/main/java/sn/samapiece/config/SecurityConfig.java — ajout de
  .requestMatchers(HttpMethod.POST, "/api/v1/recherche-publique").permitAll(), meme patron que
  GET /api/v1/postes.

Tests (nouveau fichier) :
- backend/src/test/java/sn/samapiece/recherche/web/RecherchePubliqueIntegrationTest.java — les 3
  scenarios exiges par le ticket (criteres suffisants + correspondance, criteres insuffisants, aucune
  correspondance), plus un cas numero errone (mauvais sel/hash) et un cas statut non DISPONIBLE (ex.
  RETIREE) pour verifier qu'on ne s'appuie pas sur le champ statut fige de Meilisearch.

## Decisions cles

- Repartition des roles : Meilisearch = decouverte tolerante aux fautes sur nom/prenom/type (UX,
  performance) ; PostgreSQL = verite finale sur statut, numero (hash + sel par ligne) et date de
  naissance. Le document Meilisearch n'est pas etendu (ni numero ni date de naissance n'y sont
  ajoutes) — ecarte l'option (a) du ticket pour ne rien changer a un module deja merge et teste, et
  eviter de statuer sur la sensibilite de la date de naissance dans un index tiers.
- Criteres minimaux exacts : typeDocument requis, nomTitulaire requis (non vide), et au moins un de
  numeroDocument / dateNaissanceTitulaire requis. prenomTitulaire est optionnel (affine la recherche
  s'il est fourni, n'est jamais a lui seul suffisant). Si plusieurs criteres discriminants sont
  fournis (numero et date), les deux doivent correspondre — jamais de match sur "au moins un
  correspond" quand plusieurs sont donnes, pour ne jamais elargir un match sur la base d'un seul
  indice partiel.
- Verification du numero : recalcul de SHA-256(sel_ligne + numeroFourni) pour chaque candidate de la
  shortlist et comparaison a numero_document_hash — jamais de comparaison sur un hash global, coherent
  avec le sel par ligne introduit en #10. Le scan n'est jamais un SELECT * sur toute la table : il est
  borne par le filtre type + nom (+ statut) applique en amont (Meilisearch, ou PostgreSQL en repli).
- Fraicheur du statut : le statut retenu pour la decision est celui lu en base au moment de la
  requete (jamais celui, potentiellement obsolete, indexe dans Meilisearch a la creation). Seules les
  pieces DISPONIBLE peuvent produire un trouve = true (une piece RETIREE, SIGNALEE, LITIGE, etc.
  n'est jamais confirmee).
- Contrat de reponse : trouve, et si true : typeDocument, poste.nom/adresse/horaires/telephone,
  referenceDossier = Piece.getNumeroFiche() (format PC-poste-annee-sequence, jamais l'UUID interne).
  Jamais de nom/prenom du titulaire, jamais de numero (meme masque), jamais de photo, jamais de date
  de naissance en retour.
- Anti-enumeration : validation des criteres minimaux effectuee en amont de tout acces
  Meilisearch/PostgreSQL (purement syntaxique sur le corps de requete) — reponse 400 uniforme,
  independante des donnees. Au-dela de ce seuil minimal, aucune autre mesure anti-scraping (rate
  limiting, CAPTCHA) n'est ajoutee ici : explicitement le perimetre de #19.
- RBAC : endpoint public, permitAll() dans SecurityConfig, meme patron que GET /api/v1/postes. Pas de
  @PreAuthorize.
- Resilience Meilisearch : si l'appel Meilisearch echoue (exception), repli sur une requete
  PostgreSQL directe (findByTypeDocumentAndStatutAndNomTitulaireIgnoreCase) pour ne pas rendre
  l'endpoint public indisponible — degrade (perte de la tolerance aux fautes de frappe) mais
  fonctionnel.

## Risques / points d'attention

- Cout du balayage PostgreSQL : borne a la shortlist (type + nom, quelques lignes attendues en
  pilote), pas un scan complet de piece — mais si le filtre nomTitulaire est tres generique (nom tres
  courant) et Meilisearch renvoie beaucoup de resultats, le nombre de recalculs SHA-256 augmente
  lineairement ; acceptable pour le volume pilote mentionne dans le ticket, a surveiller si le volume
  grossit (cf. section 11.6 du PROJET-SAMAPIECE.md sur la scalabilite).
- Desynchronisation Meilisearch/PostgreSQL deja existante : #17 n'a jamais branche desindexer(...) en
  production (prevu pour #24) — une piece redevenue non DISPONIBLE reste dans l'index avec
  statut = "DISPONIBLE" fige. Ce ticket ne corrige pas ce gap (hors perimetre), mais s'en protege en
  ne faisant jamais confiance au statut Meilisearch pour la decision finale.
- Faux positifs sur nom approximatif : la tolerance aux fautes de Meilisearch pourrait faire remonter
  des candidats au nom proche mais different ; comme la reponse ne revele jamais le nom du tiers, le
  risque n'est pas une fuite de donnees mais un mauvais aiguillage (le citoyen se deplace au poste
  pour un dossier qui n'est pas le sien) — d'ou l'exigence d'une comparaison exacte (insensible a la
  casse) du nom/prenom en PostgreSQL avant de confirmer trouve = true, jamais une confiance aveugle
  dans le score de pertinence Meilisearch.
- Enumeration ciblee residuelle : un attaquant qui connait deja nom/prenom/type d'une personne
  precise peut tenter plusieurs dates de naissance pour la retrouver ; c'est un risque connu et
  assume par la section 7.2 du PROJET-SAMAPIECE.md, mitige par le rate limiting/CAPTCHA du futur #19
  — pas traite ici au-dela du seuil minimal de criteres.
- Minimisation : aucune donnee personnelle du tiers (nom, prenom, numero, date de naissance, photo)
  ne doit apparaitre dans la reponse HTTP ni dans les logs applicatifs de ce nouvel endpoint —
  attention en particulier a ne pas logger le corps de la requete entrante (contient potentiellement
  un numero de document en clair) dans le futur RecherchePubliqueController/Service.
- Offline-first / PWA agent : cet endpoint est un endpoint citoyen public, pas un endpoint de l'app
  agent offline-first — aucun impact sur la synchronisation offline de l'app agent (hors perimetre du
  frontend agent).

## Hors perimetre

- Rate limiting et CAPTCHA (ticket #19).
- Desindexation Meilisearch au changement de statut (desindexer(...) deja ecrit par #17, cablage
  effectif prevu par le futur ticket #24 "Workflow de retrait").
- Le module Alertes/notifications (section 7.3, ticket ulterieur) — pas d'inscription a une alerte en
  cas de non-correspondance ici.
- Toute modification du document PieceRechercheDocument ou du schema d'index Meilisearch (option (a)
  explicitement ecartee, voir Decisions cles).
- Canal USSD (section 11.7, phase 2/3, hors MVP).
- Frontend (formulaire de recherche citoyen) — ce design couvre uniquement le contrat backend ; le
  spec-writer/codeur suivant traite le contrat d'API, pas l'UI React/PWA.
