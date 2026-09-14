# Design — Ticket #14 : Génération du reçu de dépôt (PDF)

## Approche

Le numéro de fiche unique est déjà livré (PieceNumeroFicheGenerator, table piece_sequence) : le périmètre réel de ce ticket se limite à générer, à la volée, un PDF de reçu à partir d'une Piece existante. On ajoute un endpoint GET /api/v1/pieces/{id}/recu sur le PieceController existant, qui délègue à PieceService la résolution RBAC (même pattern que consulter) puis à un nouveau générateur PDF dédié la mise en forme. Bibliothèque retenue : Apache PDFBox (org.apache.pdfbox:pdfbox, licence Apache-2.0) — aucune dépendance de génération PDF n'existe encore dans backend/pom.xml ; PDFBox est écrit en Java pur, activement maintenu, largement utilisé avec Spring Boot, et évite tout risque de licence (contrairement à iText 7/AGPL ou commercial). Le reçu est généré en mémoire (ByteArrayOutputStream) et jamais persisté ni stocké (pas de nouvelle table/migration), conformément à la consigne.

Décision de minimisation centrale : le reçu est remis au citoyen déposant, qui n'est ni authentifié ni nécessairement le titulaire de la pièce retrouvée. Le modèle de données actuel (Piece.java) ne capture aucune identité de déposant (voir Risques) — seules les données du titulaire (nomTitulaire, prenomTitulaire, numeroDocumentMasque, dateNaissanceTitulaire) constituent une identité de tiers. Le reçu n'affiche donc aucune de ces données : uniquement des métadonnées de traçage (numéro de fiche, poste, date, type de document, état). C'est une lecture volontairement stricte de "sans donnée sensible en clair" — voir Décisions clés et points ouverts.

## Fichiers/modules impactés

Backend (backend/src/main/java/sn/samapiece/enregistrement/) :
- Nouveau PieceRecuPdfGenerator.java — composant Spring (@Component), méthode byte[] genererPdf(Piece piece), utilise PDFBox (PDDocument, PDPage, PDPageContentStream, police de base Helvetica/Helvetica-Bold, encodage WinAnsiEncoding par défaut de PDFBox pour les caractères accentués français). Ne dépend d'aucun état HTTP — testable unitairement sans MockMvc.
- PieceService.java — nouvelle méthode byte[] genererRecu(UUID pieceId) : résout appelantCourant(), charge la Piece (PieceIntrouvableException sinon, déjà gérée globalement), vérifie le périmètre poste (même pattern que consulter/retirer/signaler : comparaison directe des identifiants de poste, sinon AccesRefuseException), délègue à PieceRecuPdfGenerator.
- web/PieceController.java — nouvel endpoint GET /{id}/recu, protégé par @PreAuthorize (mêmes rôles que consulter : AGENT, CHEF_POSTE) et annoté @ActionAuditee(action = "PIECE_RECU_GENERE", entiteCible = "PIECE"), retournant un ResponseEntity<byte[]> avec Content-Type application/pdf et un Content-Disposition portant un nom de fichier dérivé du numéro de fiche.
- web/PieceExceptionHandler.java — aucun changement : PieceIntrouvableException (404) et AccesRefuseException (403) sont déjà gérées globalement par les @RestControllerAdvice existants (PhotoExceptionHandler, AgentAdminExceptionHandler — non scopés à un contrôleur particulier, cf. dette relevée dans le design #24). Ne pas redéclarer de handler pour ces deux types.
- backend/pom.xml — ajout de la dépendance org.apache.pdfbox:pdfbox (dernière version stable 3.x compatible Java 21).

Tests (structure à suivre par le codeur) :
- Nouveau backend/src/test/java/sn/samapiece/enregistrement/PieceRecuPdfGeneratorTest.java — test unitaire pur : génère un PDF pour une Piece de test, relit son texte avec PDFTextStripper (fourni par PDFBox lui-même, pas de nouvelle dépendance de test), vérifie la présence du numéro de fiche, du nom de poste, de la date de dépôt, du type de document, et l'absence de nom/prénom du titulaire et du numéro de document masqué.
- backend/src/test/java/sn/samapiece/enregistrement/PieceServiceTest.java — étendre : cas nominal (agent du même poste, y compris un agent différent du créateur), cas hors périmètre poste (AccesRefuseException), pièce introuvable.
- backend/src/test/java/sn/samapiece/audit/PieceAuditIntegrationTest.java (ou nouveau test d'intégration dédié) — GET /{id}/recu via MockMvc : statut 200, Content-Type application/pdf, corps non vide, événement d'audit PIECE_RECU_GENERE enregistré ; cas 403 pour un agent d'un autre poste (vérifier l'événement ECHEC comme pour les autres actions).

Frontend : aucun fichier requis par les critères d'acceptation (qui ne portent que sur l'API) — voir Hors périmètre et points ouverts pour le lien naturel avec EnregistrementPiecePage.tsx.

## Décisions clés

1. Contenu du reçu (minimisation) : numéro de fiche, nom et adresse du poste, date de dépôt, type de document, état du document, et une mention explicative rappelant que ce reçu ne prouve pas la propriété du document et qu'il faut présenter le numéro de fiche au poste pour tout renseignement. Exclus : nom, prénom et date de naissance du titulaire, ainsi que le numéro de document masqué — ce sont des données personnelles du titulaire (un tiers vis-à-vis du déposant), et leur exposition sur un support papier remis à un déposant non authentifié va au-delà du besoin de preuve de dépôt. C'est une interprétation plus stricte que le simple masquage déjà appliqué au numéro de document (qui cible un usage agent, pas un support remis au public) — à confirmer explicitement par le spec-writer/produit (voir points ouverts).
2. RBAC — lecture retenue de "l'agent créateur ou un rôle habilité du même poste" : le libellé du critère est ambigu entre un OR (créateur OU rôle habilité du poste) et une restriction stricte. Décision : tout agent (AGENT/CHEF_POSTE) du même poste que la pièce peut générer le reçu, sans distinction créateur/non-créateur — c'est le pattern déjà en place pour consulter/retirer/signaler (comparaison directe de poste, pas de vérification d'égalité avec l'agent créateur), et il correspond à un besoin opérationnel réel (l'agent créateur peut être absent quand le reçu doit être réimprimé). Aucune règle nouvelle de type "créateur uniquement" n'est introduite.
3. PDF généré à la volée, jamais stocké : pas de nouvelle table/migration, conforme à la consigne — le reçu peut être régénéré à l'identique à tout moment tant que la Piece existe.
4. Audit dédié PIECE_RECU_GENERE : la génération d'un reçu produit un artefact destiné à sortir du système (remis en main propre), assimilable à un export au sens de la section 7.5 du document de référence ("historique... pour toute action sensible : ... export") — un événement dédié est donc préférable à la réutilisation de PIECE_CONSULTEE, pour distinguer dans le journal d'audit la consultation à l'écran et la génération d'une preuve papier/PDF remise à un tiers. Aucune modification de AuditAspect n'est nécessaire : l'extraction de l'identifiant de l'entité cible se fait déjà depuis le @PathVariable UUID id, indépendamment du type de retour (ResponseEntity<byte[]>).
5. Bibliothèque PDF — Apache PDFBox (Apache-2.0), plutôt qu'OpenPDF (LGPL/MPL, base iText 4 peu maintenue) ou iText 7 (AGPL/commercial, à proscrire). Usage minimal : une page A4, texte statique plus valeurs dynamiques, pas de mise en page complexe.

## Risques / points d'attention

- Absence de champ "déposant" dans le modèle actuel : Piece.java ne porte aucune colonne d'identité du déposant (nom, contact), alors que la section 7.1 du document de référence liste l'identité du déposant comme champ de formulaire, et la section 7.3 mentionne une notification/reconnaissance civique au déposant. Ce ticket ne peut donc pas afficher de nom de déposant sur le reçu (il n'existe nulle part dans le système), et ne doit pas en créer un implicitement pour ce faire — capture d'une identité déposant potentielle = dépendance non résolue pour un ticket futur, distincte de celui-ci.
- Caractères accentués dans le PDF : la police de base PDFBox (Helvetica, WinAnsiEncoding) couvre les accents français courants (é, è, à, ô, ç) mais pas tous les caractères Unicode exotiques — à vérifier en test avec des noms de poste/valeurs réalistes ; risque analogue à la note déjà connue sur l'encodage UTF-8 des tests MockMvc, mais ici côté génération PDF.
- Les @RestControllerAdvice existants ne sont pas scopés à un contrôleur : ne pas redéclarer de handler pour PieceIntrouvableException/AccesRefuseException dans PieceExceptionHandler — un doublon casserait le démarrage Spring (dette déjà signalée dans le design #24).
- Nouvelle dépendance PDFBox : vérifier l'absence de conflit de résolution avec les dépendances existantes au build ; impact mineur sur la taille du jar.
- Le contenu minimal exact du reçu (décision 1) est une lecture d'architecte, pas une certitude produit — à valider explicitement, car une exclusion trop stricte pourrait rendre le reçu peu utile en pratique (aucune identification, seulement une référence numérique).
- Offline-first : ce ticket ne touche pas le frontend agent PWA ; la génération du PDF nécessite une requête réseau (pas de génération côté client) — un agent hors ligne ne pourra pas imprimer le reçu immédiatement après une création de fiche mise en queue locale (cf. section 7.1, fonctionnement hors ligne). Ce cas limite n'est pas traité ici (voir Hors périmètre) mais mérite d'être noté pour le produit.

## Hors périmètre

- Réimplémentation du numéro de fiche unique et de la table piece_sequence — déjà livré.
- Stockage ou historisation des PDF générés (pas de bucket Minio dédié, pas de table de reçus).
- Capture d'une identité de déposant sur Piece (nom, contact) — non demandée par les critères d'acceptation de ce ticket, dépendance non résolue signalée en risques.
- Interface frontend (bouton de téléchargement ou d'impression du reçu dans EnregistrementPiecePage.tsx ou ailleurs dans frontend/src/features/pieces/) — les critères d'acceptation ne portent que sur l'API REST ; un point d'ancrage naturel existe déjà côté écran de création, mais son câblage est laissé à un ticket ultérieur ou à une extension explicitement demandée.
- Génération de PDF hors ligne côté PWA agent (le cas création hors ligne suivie d'une impression immédiate n'est pas couvert).
- Export de rapports CSV/PDF pour la hiérarchie (module Tableau de bord, section 7.4) — sans rapport avec ce ticket.
- Toute évolution du floutage ou du masquage des photos (section 7.1) — module photo distinct, sans rapport.

## Points ouverts pour le spec-writer

1. Confirmer ou amender la décision de minimisation (nom, prénom, date de naissance du titulaire et numéro de document masqué absents du reçu) — c'est le choix le plus impactant de ce design.
2. Confirmer la lecture RBAC "même poste, sans restriction créateur" plutôt qu'une restriction stricte au créateur de la fiche.
3. Trancher entre Content-Disposition inline (aperçu navigateur, impression via le navigateur) et attachment (téléchargement forcé) — impact sur l'expérience agent, aucun impact technique côté backend.
4. Décider si un lien frontend minimal (bouton de téléchargement sur EnregistrementPiecePage.tsx après création) doit être inclus dans ce ticket ou reporté.
