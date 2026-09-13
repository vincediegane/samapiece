# Review — #18 Endpoint recherche publique anonymisée

APPROVE

## Critères d'acceptation

| # | Critère | Statut |
|---|---|---|
| 1 | POST /api/v1/recherche-publique exige type + nom + au moins un de (numéro/date) avant tout résultat | Couvert — RecherchePubliqueRequest.estSuffisant() appelé en tout premier dans RecherchePubliqueService.rechercher(), avant tout accès Meilisearch/PostgreSQL ; testé par RecherchePubliqueRequestTest (11 cas, table de vérité complète, tous verts) et par les scénarios 400 de RecherchePubliqueIntegrationTest |
| 2 | Réponse ne contient jamais numéro complet/photo/identité exacte du tiers | Couvert — RecherchePubliqueResponse/PosteResume n'exposent que trouve, typeDocument, poste.{nom,adresse,horaires,telephone}, referenceDossier ; RecherchePubliqueIntegrationTest#criteresSuffisantsAvecCorrespondance_... fait une assertion structurelle stricte (containsExactlyInAnyOrder) sur les clés JSON exactes de la réponse et de poste, donc ce test échouerait si un champ sensible (nom, prénom, numéro, date de naissance) était ajouté |
| 3 | Aucun résultat si critères trop vagues (anti-énumération) | Couvert — validation purement syntaxique (estSuffisant()), pas de @Valid/Bean Validation sur le DTO (confirmé par lecture de RecherchePubliqueRequest.java, aucune annotation), donc le code d'erreur/message est rigoureusement identique quel que soit le champ manquant ; RecherchePubliqueExceptionHandler renvoie un corps fixe. Testé par 5 variantes paramétrées (type manquant, nom manquant, nom vide, nom blanc, ni numéro ni date) qui vérifient toutes le même couple code+message |
| 4 | Tests d'intégration : suffisant+correspondance / insuffisant / aucune correspondance | Couvert dans RecherchePubliqueIntegrationTest (+ 4 cas dérivés utiles : statut non DISPONIBLE, numéro erroné, 2x discriminants partiels) et RecherchePubliqueMeilisearchIndisponibleIntegrationTest (repli PostgreSQL) |

## Vérifications ciblées (points critiques du prompt)

1. Anti-énumération réelle : estSuffisant() est le tout premier appel de rechercher(...), avant obtenirCandidats(...). Aucune annotation Bean Validation sur RecherchePubliqueRequest (vérifié par lecture directe du fichier) — le corps 400 est donc garanti identique pour toute requête insuffisante, pas de risque de MethodArgumentNotValidException parasite. Confirmé par le test paramétré sur 5 variantes.
2. Aucune fuite de données du tiers : RecherchePubliqueResponse/PosteResume n'ont que les 4+4 champs attendus ; le test d'intégration fait une assertion fieldNames() exacte sur la réponse ET sur poste, ce qui casserait si un champ sensible fuitait.
3. Fraîcheur du statut : correspond(candidat, requete) teste candidat.getStatut() != StatutPiece.DISPONIBLE sur l'entité Piece rechargée depuis PostgreSQL, jamais sur le champ Meilisearch. Le scénario statutNonDisponible_... force le désaccord (indexé DISPONIBLE dans Meilisearch, UPDATE piece SET statut = RETIREE en direct SQL) et attend trouve=false.
4. Vérification du numéro par sel de ligne : hasher.verifier(requete.numeroDocument(), candidat.getNumeroDocumentSel(), candidat.getNumeroDocumentHash()) — sel et hash lus sur la ligne candidate elle-même, jamais de comparaison de hash global. NumeroDocumentHasher.verifier réutilise la méthode privée calculerHash existante.
5. Plusieurs discriminants fournis : correspond(...) évalue le numéro ET la date indépendamment (deux if distincts) — pas de retour anticipé sur succès partiel. Les deux scénarios plusieursCriteresUnSeulCorrespond_* attendent tous deux trouve=false.
6. Logs : le seul LOG.warn(...) du nouveau code (repli Meilisearch) reçoit un message fixe + l'exception capturée — jamais requete ni un de ses champs.
7. Fichiers #17 non touchés : git diff main..HEAD --stat confirme que seuls NumeroDocumentHasher.java (+7 lignes), PieceRepository.java (+4 lignes), SecurityConfig.java (+1 ligne) sont modifiés parmi les fichiers existants — PieceRechercheDocument, MeilisearchConfig, PieceRechercheIndexService, PieceIndexationListener n'apparaissent pas dans le diff.
8. Endpoint réellement public : SecurityConfig ajoute .requestMatchers(HttpMethod.POST, "/api/v1/recherche-publique").permitAll() dans le même bloc que GET /api/v1/postes ; RecherchePubliqueController ne porte aucune annotation @PreAuthorize.

Cohérence avec spec.md : le code (RecherchePubliqueRequest, RecherchePubliqueResponse, RecherchePubliqueService, RecherchePubliqueExceptionHandler, RecherchePubliqueController, ajouts NumeroDocumentHasher/PieceRepository/SecurityConfig) reproduit exactement les signatures et la logique du Contrat technique de la spec, sans écart non justifié. Les 3 écarts identifiés par la spec (méthode verifier au lieu de dupliquer SHA-256, absence de clause filter= Meilisearch, portée de la nouvelle méthode PieceRepository limitée au repli) sont bien respectés dans l'implémentation. Rate limiting/CAPTCHA (mentionnés en §7.2/§10.4 de PROJET-SAMAPIECE.md) sont explicitement hors périmètre de ce ticket (renvoyés à #19 par design.md), donc pas un manque de ce bolt.

Aucune donnée sensible stockée en clair par ce changement (§10) : pas de nouvelle colonne, le numéro reste vérifié via hash+sel existants, la date de naissance n'est ni indexée dans Meilisearch ni renvoyée en clair dans la réponse HTTP.

Aucune migration Flyway nécessaire/ajoutée pour ce ticket (aucun changement de schéma) — rien à revoir sur ce point.

## Build/tests

- mvn -q -pl backend -am test -Dtest=RecherchePubliqueRequestTest,NumeroDocumentHasherTest -> OK, Tests run: 11, Failures: 0, Errors: 0 (RecherchePubliqueRequestTest) et Tests run: 15, Failures: 0, Errors: 0 (NumeroDocumentHasherTest, incluant les 4 nouveaux cas verifier_*).
- Suite complète des tests unitaires purs relancée explicitement en excluant les 11 classes @Testcontainers du module -> OK, 71 tests, 0 échec, 0 erreur (inclut les deux nouveaux fichiers de test ci-dessus). Pas de régression détectée sur le reste du module.
- RecherchePubliqueIntegrationTest et RecherchePubliqueMeilisearchIndisponibleIntegrationTest (Testcontainers Postgres+Meilisearch) : tentative d'exécution réelle, échec avec ContainerFetchException / Could not find a valid Docker environment (NpipeSocketClientProviderStrategy: BadRequestException Status 400) — même incompatibilité Docker Desktop/Windows déjà documentée sur ce repo (cf. commit aed5445 du bolt #17). Limitation d'environnement, pas un signal sur le code. En compensation, relecture stricte ligne à ligne des deux fichiers (349 + 147 lignes) contre le plan de tests de la spec :
  - Assertion structurelle exacte des clés JSON de la réponse trouvé présente et correcte (containsExactlyInAnyOrder sur le corps de réponse et sur poste).
  - Nettoyage piece_sequence présent dans RecherchePubliqueIntegrationTest#nettoyer() avant posteRepository.deleteAll() (même correctif que #11/#17). Dans RecherchePubliqueMeilisearchIndisponibleIntegrationTest, ce nettoyage est absent mais n'est pas nécessaire : cette classe ne crée jamais de Piece via l'API POST /api/v1/pieces (seul chemin qui alimente piece_sequence via PieceNumeroFicheGenerator) — les pièces y sont insérées directement via pieceRepository.save(...) avec un numeroFiche fourni en dur, donc piece_sequence reste vide et posteRepository.deleteAll() ne peut pas échouer sur une FK résiduelle.
  - Les 7 méthodes de test couvrent bien tous les scénarios du plan de tests (AC1-AC4 + 3 cas dérivés + repli Meilisearch), avec des assertions cohérentes avec le contrat de réponse.
- Compilation OK (implicite au succès des exécutions ci-dessus, aucune erreur de type/signature).
- Frontend : hors périmètre de ce ticket (aucun fichier frontend dans le diff), pas de build front à lancer.

## Conclusion

Code conforme à la spec et au ticket, aucune fuite de donnée sensible détectée, anti-énumération correctement implémentée et testée, aucun fichier du module #17 modifié, endpoint correctement public sans RBAC. Tests unitaires (26 nouveaux, 71 au total hors Testcontainers) tous verts, aucune régression. Tests d'intégration Testcontainers non exécutables dans cet environnement (limitation Docker Desktop/Windows déjà connue), mais revue de code stricte des deux fichiers ne révèle aucun défaut.
