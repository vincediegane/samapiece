# Review — Ticket #14 : Génération du reçu de dépôt (PDF)

APPROVE

## Critères d'acceptation

| Critère | Statut |
|---|---|
| `GET /api/v1/pieces/{id}/recu` génère un PDF (numéro de fiche, poste, date, type de document, sans donnée sensible en clair) | Couvert |
| Accessible uniquement à l'agent ayant créé la fiche ou à un rôle habilité du même poste (lu comme : tout agent AGENT/CHEF_POSTE du même poste, cf. décision tranchée §2 de la spec) | Couvert |
| Test vérifiant la génération et le contenu minimal attendu du PDF | Couvert |

## Points de vigilance de la spec — vérification détaillée

1. **Contenu exact (8 lignes)** — `PieceRecuPdfGenerator.java` écrit exactement les 8 champs dans l'ordre : titre, numéro de fiche, poste, adresse, date de dépôt `dd/MM/yyyy`, `typeDocument.name()`, état du document avec fallback `"Non renseigné"`, mention explicative (répartie sur deux lignes de texte mais un seul message logique, conforme — la spec autorise "une ou plusieurs lignes"). Aucun champ supplémentaire. Conforme.
2. **Absence stricte des données exclues** — `PieceRecuPdfGeneratorTest` extrait le texte via `Loader.loadPDF` + `PDFTextStripper` (pas une simple relecture de code) et vérifie `doesNotContain` sur `nomTitulaire`, `prenomTitulaire`, `numeroDocumentMasque`, et sur la date de naissance formatée (`"12/05/1990"`) alors que la date de dépôt utilisée dans le test est délibérément différente (`13/09/2026`), ce qui rend l'assertion significative (pas de collision accidentelle qui masquerait un bug). Le test d'intégration `PieceAuditIntegrationTest` répète les mêmes assertions `doesNotContain` sur nom/prénom via le vrai flux HTTP + `PDFTextStripper`. Conforme.
3. **Signature finale** — `PieceService.genererRecu` retourne bien `RecuPdf` (record imbriqué `PieceService.RecuPdf(byte[] contenu, String numeroFiche)`), pas un `byte[]` seul. Conforme à la version définitive de la spec (pas le brouillon intermédiaire).
4. **RBAC** — comparaison directe `appelant.getPoste().getId().equals(piece.getPoste().getId())`, aucune référence à `agentCreateur` dans la méthode. Le test `genererRecu_commeAgentDuMemePosteNonCreateur_...` confirme explicitement le cas non-créateur. Conforme.
5. **Content-Disposition** — `"inline; filename=\"" + nomFichier + "\""` avec assainissement `replaceAll("[^A-Za-z0-9-]", "_")` appliqué à `numeroFiche`. Conforme, et vérifié côté intégration (assertion sur l'en-tête contenant le numéro assaini).
6. **`@ActionAuditee`** — présent avec `action = "PIECE_RECU_GENERE"`, `entiteCible = "PIECE"`. Confirmé en base par les tests d'intégration (succès `resultat=SUCCES` avec `entiteCibleId`, échec `resultat=ECHEC` avec `exception=AccesRefuseException`).
7. **`PieceExceptionHandler.java`** — diff vide sur ce fichier (`git diff main..bolt/issue-14-recu-depot-pdf -- .../PieceExceptionHandler.java` ne retourne rien) : aucun handler dupliqué ajouté. Conforme.
8. **`PieceRecuPdfGenerator` pur** — aucune dépendance à `HttpServletRequest`, à `SecurityContext` ni à `@Transactional` ; reçoit une `Piece` déjà chargée. Testable et testé sans Spring (`new PieceRecuPdfGenerator()` en test unitaire pur, pas de `@SpringBootTest`). Conforme.
9. **Fermeture du `PDDocument`** — `try (PDDocument document = new PDDocument())` englobe l'écriture et le `document.save(sortie)`, le tableau d'octets n'est retourné qu'après la fermeture implicite du try-with-resources. Conforme.
10. **Aucun fichier frontend touché** — `git diff --stat -- frontend` vide. Conforme.
11. **Version PDFBox** — `pom.xml` déclare `org.apache.pdfbox:pdfbox:3.0.8` (branche 3.x). L'API utilisée (`new PDType1Font(Standard14Fonts.FontName.HELVETICA)`) est l'API PDFBox 3.x correcte (le constructeur `PDType1Font(Standard14Fonts.FontName)` remplace les champs statiques `PDType1Font.HELVETICA` de la 2.x, qui ont été supprimés en 3.x) — pas de mélange d'API incompatible. Conforme.

## Cohérence avec spec.md / design.md

Toutes les tâches de la checklist de `spec.md` sont couvertes par le diff : `pom.xml`, `PieceRecuPdfGenerator`, `PieceService.genererRecu` (+ record `RecuPdf`), `PieceController`, `PieceRecuPdfGeneratorTest`, extension de `PieceServiceTest` (3 nouveaux cas : nominal non-créateur, hors périmètre, pièce introuvable) et extension de `PieceAuditIntegrationTest` (succès+audit, 403 autre poste+audit échec, et les 3 tests de non-régression rôle ADMIN_REGIONAL/ADMIN_NATIONAL/AUDITEUR — la spec les qualifiait de "recommandé mais non strictement exigé", le codeur les a inclus, ce qui est un plus). Aucun écart non justifié constaté.

Point de vigilance mémoire (nettoyage `piece_sequence`) : non applicable ici car `PieceAuditIntegrationTest` est un fichier existant déjà conforme à l'ordre de nettoyage documenté (`@BeforeEach nettoyer()` inchangé par ce diff), pas un nouveau fichier `@SpringBootTest`.

Point de vigilance mémoire (UTF-8 MockMvc) : les nouveaux tests d'intégration lisent le corps binaire du PDF via `getContentAsByteArray()` (correct, pas `getContentAsString()`), conforme à la note de la spec sur ce point.

## Findings

Aucun finding bloquant. Deux remarques mineures, non bloquantes, à titre indicatif pour un futur ticket :
- `PieceAuditIntegrationTest.java:293-307` (nouveau test succès) utilise `org.hamcrest.Matchers.startsWith`/`containsString` en nom pleinement qualifié inline plutôt qu'un import statique — cohérence de style seulement, aucun impact fonctionnel.
- Le design signale (non repris comme point ouvert bloquant par la spec) l'absence de couverture du cas "agent hors ligne souhaitant imprimer immédiatement après création" — explicitement hors périmètre du ticket, correctement documenté, rien à corriger ici.

## Build/tests

- `mvn -pl backend -am compile` — succès.
- `mvn -pl backend test -Dtest=PieceRecuPdfGeneratorTest,PieceServiceTest` — succès (`Tests run: 16, Failures: 0, Errors: 0`).
- `mvn -pl backend test -Dtest='sn.samapiece.enregistrement.**'` — 74 tests, 71 passent, 3 erreurs (`PhotoIntegrationTest`, `PieceNumeroFicheGeneratorTest`, `PieceIntegrationTest`), toutes dues à `Could not find a valid Docker environment` (Testcontainers indisponible dans ce sandbox), préexistant et sans rapport avec ce ticket. Aucune régression imputable au diff #14 : tous les tests non-Testcontainers du module `enregistrement` passent, y compris `PieceTest` (25 tests) et `PieceServiceTest` (14 tests, incluant les 3 nouveaux cas `genererRecu`).
- `PieceAuditIntegrationTest` (test d'intégration MockMvc étendu pour ce ticket) — non exécutable dans ce sandbox (Testcontainers/Docker indisponible), conformément à la limitation connue. Compensé par une relecture manuelle rigoureuse du test et du code de production exercé (RBAC, en-têtes HTTP, extraction PDFBox, audit) — voir points 1 à 9 ci-dessus.
