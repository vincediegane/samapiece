# Spec — Ticket #14 : Génération du reçu de dépôt (PDF)

## Résumé

Ajouter l'endpoint `GET /api/v1/pieces/{id}/recu` qui génère à la volée, sans stockage, un PDF de reçu de dépôt minimisé (aucune donnée personnelle du titulaire) accessible à tout agent du même poste que la pièce.

## Décisions tranchées (points ouverts du design fermés)

### 1. Contenu du reçu — liste EXACTE et définitive

Le reçu affiche, dans cet ordre, uniquement les champs suivants (aucun autre champ, aucune variante) :

1. Titre : `Reçu de dépôt`
2. `Numéro de fiche : <piece.getNumeroFiche()>`
3. `Poste : <piece.getPoste().getNom()>`
4. `Adresse : <piece.getPoste().getAdresse()>`
5. `Date de dépôt : <piece.getDateDepot()>` formatée en `dd/MM/yyyy`
6. `Type de document : <piece.getTypeDocument().name()>` (valeur brute de l'enum, ex. `CNI`, `PASSEPORT` — pas de table de libellés français à introduire pour ce ticket ; c'est un choix délibéré de simplicité, à ne pas contester)
7. `État du document : <piece.getEtatDocument()>` (si `null`, afficher `Non renseigné`)
8. Mention explicative, sur une ou plusieurs lignes : `Ce reçu ne constitue pas une preuve de propriété du document. Pour tout renseignement, présentez le numéro de fiche ci-dessus au poste indiqué.`

Champs explicitement EXCLUS et qui ne doivent apparaître nulle part dans le texte du PDF : `nomTitulaire`, `prenomTitulaire`, `dateNaissanceTitulaire`, `numeroDocumentMasque` (et a fortiori `numeroDocumentHash`/`numeroDocumentSel`). Aucun champ « déposant » n'existe dans le modèle (`Piece.java`) et ce ticket n'en introduit pas — ne pas ajouter de champ, de paramètre de requête, ni de valeur par défaut simulant une identité de déposant.

Cette décision confirme et fige la lecture de l'architecte (§ Décisions clés 1 du design) : le déposant n'est pas authentifié et n'est pas nécessairement le titulaire, donc aucune donnée personnelle du titulaire ne doit figurer sur un support remis en main propre à un tiers non identifié.

### 2. RBAC — lecture définitive

Tout agent avec le rôle `AGENT` ou `CHEF_POSTE` dont le poste (`appelant.getPoste().getId()`) est identique au poste de la pièce (`piece.getPoste().getId()`) peut générer le reçu — sans aucune distinction créateur/non-créateur. C'est exactement le pattern déjà utilisé par `PieceService.consulter`/`retirer`/`signaler`. Ne pas coder de vérification `piece.getAgentCreateur().getId().equals(appelant.getId())` : cette branche n'existe dans aucun autre endpoint de `PieceService` et ne doit pas être introduite ici. Cette lecture ferme définitivement l'ambiguïté du libellé du critère d'acceptation ("l'agent ayant créé la fiche OU un rôle habilité du même poste" = OR, donc tout agent du poste suffit).

### 3. Content-Disposition — tranché : `inline`

Décision : `Content-Disposition: inline; filename="<numeroFiche>.pdf"`. Justification : le § 7.1 du document de référence décrit un usage terrain où le reçu sert de preuve remise/imprimable immédiatement au poste ; `inline` permet à l'agent de prévisualiser le PDF dans un nouvel onglet du navigateur et de l'imprimer directement (Ctrl+P navigateur) sans étape de téléchargement intermédiaire, ce qui correspond mieux à ce flux qu'un téléchargement forcé. Le nom de fichier est dérivé du numéro de fiche dans les deux cas (`inline` comme `attachment` acceptent le paramètre `filename`) : remplacer tout caractère non alphanumérique/tiret du `numeroFiche` par `_` pour éviter tout problème d'échappement d'en-tête HTTP (le format exact de `numeroFiche` généré par `PieceNumeroFicheGenerator` n'a pas besoin d'être vérifié ici — appliquer la substitution par sécurité).

### 4. Lien frontend — hors périmètre confirmé

Aucun fichier frontend (`frontend/src/features/pieces/**`) n'est à modifier. Les critères d'acceptation du ticket ne portent que sur l'API REST. Ne pas ajouter de bouton, de lien, ni d'appel `fetch`/`axios` vers `/recu` dans `EnregistrementPiecePage.tsx` ou ailleurs.

## Tâches

- [ ] `backend/pom.xml` — ajouter la dépendance `org.apache.pdfbox:pdfbox` (dernière version stable de la branche 3.x compatible Java 21 ; à vérifier par le codeur au moment de l'implémentation, aucune version 1.x/2.x ne doit être utilisée). Aucun `<scope>` particulier (dépendance de production, pas seulement test).
- [ ] `backend/src/main/java/sn/samapiece/enregistrement/PieceRecuPdfGenerator.java` (nouveau) — composant `@Component`, méthode `public byte[] genererPdf(Piece piece)` (voir Contrat technique).
- [ ] `backend/src/main/java/sn/samapiece/enregistrement/PieceService.java` — nouvelle méthode `@Transactional(readOnly = true) public byte[] genererRecu(UUID pieceId)` (voir Contrat technique), injectant `PieceRecuPdfGenerator` comme dépendance supplémentaire du constructeur.
- [ ] `backend/src/main/java/sn/samapiece/enregistrement/web/PieceController.java` — nouvel endpoint `GET /{id}/recu` (voir Contrat technique).
- [ ] Ne pas modifier `backend/src/main/java/sn/samapiece/enregistrement/web/PieceExceptionHandler.java` : `PieceIntrouvableException` (géré par `PhotoExceptionHandler`, 404) et `AccesRefuseException` (géré globalement ailleurs, 403) sont déjà couverts — toute redéclaration provoquerait une erreur de démarrage Spring (bean ambigu). Vérifié dans le code actuel : `PieceExceptionHandler` ne gère aujourd'hui que `TransitionStatutInterditeException`, ne pas y toucher pour ce ticket.
- [ ] `backend/src/test/java/sn/samapiece/enregistrement/PieceRecuPdfGeneratorTest.java` (nouveau) — test unitaire pur du générateur PDF (voir Plan de tests).
- [ ] `backend/src/test/java/sn/samapiece/enregistrement/PieceServiceTest.java` — étendre avec les cas nominal / hors périmètre poste / pièce introuvable pour `genererRecu` (voir Plan de tests). Si ce fichier n'existe pas encore sous cette forme, vérifier son nom exact avant de créer un doublon.
- [ ] `backend/src/test/java/sn/samapiece/audit/PieceAuditIntegrationTest.java` — étendre avec les tests d'intégration MockMvc pour `GET /{id}/recu` (succès + audit `PIECE_RECU_GENERE`, 403 agent d'un autre poste + audit `ECHEC`) — voir Plan de tests et point de vigilance nettoyage ci-dessous.

## Contrat technique

### `PieceRecuPdfGenerator`

```java
package sn.samapiece.enregistrement;

@Component
public class PieceRecuPdfGenerator {
    public byte[] genererPdf(Piece piece) { ... }
}
```

- Aucune dépendance à l'état HTTP, à la sécurité, ni à un `@Transactional` — reçoit une `Piece` déjà chargée et vérifiée en amont par `PieceService`.
- Génère un document PDFBox une page A4 (`PDRectangle.A4`), police `Helvetica`/`Helvetica-Bold` (encodage WinAnsiEncoding par défaut de PDFBox), une ligne de texte par champ listé au point 1 ci-dessus, dans l'ordre indiqué. Le titre est en `Helvetica-Bold`, taille de police plus grande (ex. 16) ; les champs et la mention explicative en `Helvetica` (ex. 11-12).
- Retourne le contenu du `ByteArrayOutputStream` en `byte[]` ; le document PDFBox (`PDDocument`) doit être fermé (try-with-resources) avant de retourner le tableau de octets — ne jamais retourner un flux ouvert sur un document fermé.
- Ne persiste rien, n'écrit aucun fichier sur disque.

### `PieceService.genererRecu`

```java
@Transactional(readOnly = true)
public byte[] genererRecu(UUID pieceId) {
    Agent appelant = appelantCourant();
    Piece piece = pieceRepository.findById(pieceId)
            .orElseThrow(() -> new PieceIntrouvableException(pieceId));

    if (!appelant.getPoste().getId().equals(piece.getPoste().getId())) {
        throw new AccesRefuseException("Poste hors perimetre pour cette piece.");
    }

    return pieceRecuPdfGenerator.genererPdf(piece);
}
```

Reproduit exactement le pattern de `consulter`/`retirer`/`signaler` (comparaison directe d'identifiants de poste, pas `PerimetrePoste.estDansPerimetre` qui est réservé à `debloquer`).

### `PieceController` — endpoint

```java
@GetMapping("/{id}/recu")
@PreAuthorize("hasAnyRole('AGENT','CHEF_POSTE')")
@ActionAuditee(action = "PIECE_RECU_GENERE", entiteCible = "PIECE")
public ResponseEntity<byte[]> genererRecu(@PathVariable UUID id) {
    byte[] pdf = pieceService.genererRecu(id);
    String nomFichier = /* numeroFiche assaini, voir décision 3 */ + ".pdf";
    return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + nomFichier + "\"")
            .body(pdf);
}
```

Notes :
- Rôles autorisés identiques à `consulter` : `AGENT`, `CHEF_POSTE` uniquement (pas `ADMIN_REGIONAL`/`ADMIN_NATIONAL`/`AUDITEUR`, cohérent avec les tests existants sur `consulter` qui attendent 403 pour ces rôles).
- `MediaType.APPLICATION_PDF` existe nativement dans Spring (`org.springframework.http.MediaType`), pas besoin de le définir manuellement.
- Le nom de fichier doit être dérivé de `piece.getNumeroFiche()` : le contrôleur n'a pas directement accès à la `Piece`, donc soit `PieceService.genererRecu` retourne un petit objet porteur (`byte[]` + nom de fichier), soit le contrôleur récupère le numéro de fiche via un appel séparé. **Choix imposé pour éviter toute ambiguïté** : `PieceService.genererRecu` retourne uniquement `byte[]` (signature ci-dessus, ne pas la changer) ; le contrôleur appelle `pieceService.consulter(id)` d'abord pour obtenir `PieceResponse.numeroFiche()`, ou plus simple : `PieceService` expose une seconde méthode minimale si besoin. **Décision finale retenue** : introduire dans `PieceService` un record de retour dédié `RecuPdf(byte[] contenu, String numeroFiche)` et faire retourner ce type par `genererRecu` (adapter la signature ci-dessus en conséquence) — le contrôleur lit `recu.numeroFiche()` pour construire l'en-tête sans appel réseau/DB supplémentaire ni double vérification RBAC. Le codeur doit créer ce record (`PieceService.RecuPdf` ou classe top-level dans le package `enregistrement`, au choix, mais un seul appel à `genererRecu` par requête).
- Aucun autre header n'est requis (pas de `Cache-Control` spécifique demandé par les critères d'acceptation).

## Plan de tests

| Critère d'acceptation | Test | Fichier |
|---|---|---|
| `GET /{id}/recu` génère un PDF avec numéro de fiche, poste, date, type de document, sans donnée sensible en clair | Test unitaire : génère un PDF pour une `Piece` de test avec des valeurs distinctes et non ambiguës (nom/prénom titulaire différents des autres champs), relit le texte avec `PDFTextStripper`, vérifie la présence de `numeroFiche`, `poste.getNom()`, `poste.getAdresse()`, `dateDepot` formatée, `typeDocument.name()`, `etatDocument` et la mention explicative | `PieceRecuPdfGeneratorTest.java` (nouveau) |
| Absence de donnée sensible en clair | Même test : assertions explicites `assertThat(texteExtrait).doesNotContain(piece.getNomTitulaire())`, `.doesNotContain(piece.getPrenomTitulaire())`, `.doesNotContain(piece.getNumeroDocumentMasque())`, et si `dateNaissanceTitulaire` est formatée de façon reconnaissable, vérifier aussi son absence sous cette forme | `PieceRecuPdfGeneratorTest.java` |
| Accessible uniquement à l'agent du même poste (RBAC) — cas nominal, y compris agent non créateur | Test unitaire service : agent A crée la pièce, agent B du même poste appelle `genererRecu` → succès, PDF non vide | `PieceServiceTest.java` |
| Accessible uniquement à l'agent du même poste (RBAC) — cas hors périmètre | Test unitaire service : agent d'un autre poste appelle `genererRecu` → `AccesRefuseException` ; pièce inexistante → `PieceIntrouvableException` | `PieceServiceTest.java` |
| Accessible uniquement à l'agent du même poste (RBAC) — intégration bout en bout | Test MockMvc : `GET /api/v1/pieces/{id}/recu` avec un agent d'un autre poste → 403 ; vérifier l'événement d'audit `PIECE_RECU_GENERE` avec `resultat = ECHEC` (même pattern que `retirer_commeAgentDunAutrePoste_shouldRetourner403EtCreerEvenementAuditEchec`) | `PieceAuditIntegrationTest.java` |
| Test vérifiant la génération et le contenu minimal attendu du PDF (intégration) | Test MockMvc : `GET /api/v1/pieces/{id}/recu` avec un agent du même poste (non créateur) → 200, `Content-Type` commence par `application/pdf`, en-tête `Content-Disposition` contient `inline` et le numéro de fiche assaini, corps non vide (`getContentAsByteArray().length > 0`), relecture du corps avec `PDFTextStripper` (charger via `Loader.loadPDF(...)` de PDFBox, pas de conversion `String` du flux binaire — ne pas utiliser `getContentAsString` ici, réservé aux réponses JSON), vérification de la présence du numéro de fiche et de l'absence du nom/prénom titulaire ; vérifier aussi l'événement d'audit `PIECE_RECU_GENERE` avec `resultat = SUCCES` et `entiteCibleId` égal à l'id de la pièce | `PieceAuditIntegrationTest.java` |
| Non-régression rôles | Réutiliser le pattern des tests `consulter_commeAdminRegional_shouldRetourner403` / `commeAdminNational` / `commeAuditeur` appliqué à `GET /{id}/recu` (403 pour ces trois rôles) — recommandé mais non strictement exigé par les critères d'acceptation ; à inclure si le temps le permet, sinon documenter l'omission | `PieceAuditIntegrationTest.java` |

Points de vigilance transverses (mémoire projet) à respecter dans ces tests :

- Si un test étend `PieceAuditIntegrationTest.java` (qui crée déjà des `Piece` via `@SpringBootTest`), le `@BeforeEach nettoyer()` existant vide déjà `evenement_audit`, `retrait`, `piece` puis `piece_sequence` avant `agent`/`poste`/`region` — ne pas modifier cet ordre, il est correct. Si un nouveau fichier de test `@SpringBootTest` créant des `Piece` est ajouté ailleurs, respecter le même ordre : vider `piece_sequence` avant de supprimer les `Poste` référencés (FK), sinon échec uniquement en CI.
- Toute assertion sur un corps de réponse JSON contenant des caractères accentués français (ex. messages d'erreur 403/404, `etatDocument` = "bon état") doit utiliser `getResponse().getContentAsString(StandardCharsets.UTF_8)`, jamais la surcharge sans argument. Cela ne s'applique pas à la lecture du corps binaire du PDF (utiliser `getContentAsByteArray()` puis `Loader.loadPDF` de PDFBox), mais s'applique si un test vérifie séparément un message d'erreur JSON.
- L'ajout de PDFBox n'introduit aucun health indicator Actuator ni composant auto-détecté par Spring Boot Actuator (ce n'est ni un driver de datastore ni un client réseau) — le point de vigilance mémorisé sur `management.health.<nom>.enabled=false` ne s'applique pas à cette dépendance, aucune action requise sur `/actuator/health`.

## Écarts identifiés

Aucun écart bloquant entre le design et les critères d'acceptation du ticket : les 4 points ouverts sont tranchés ci-dessus et couvrent l'intégralité des 3 critères d'acceptation. Un seul point mérite d'être signalé au codeur sans bloquer l'implémentation : la signature de `PieceService.genererRecu` évolue par rapport à la proposition initiale du design (`byte[]` seul) vers un petit record `RecuPdf(byte[] contenu, String numeroFiche)`, nécessaire pour construire l'en-tête `Content-Disposition` sans dupliquer l'accès à la `Piece` ni la vérification RBAC dans le contrôleur — ce choix est imposé par cette spec et ne doit pas être source d'improvisation supplémentaire.
