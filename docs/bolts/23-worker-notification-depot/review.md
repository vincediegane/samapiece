# Review -- #23 Worker d'envoi de notifications sur depot correspondant

APPROVE

## Perimetre de revue

`git diff bolt/issue-22-alertes-recherche..bolt/issue-23-worker-notification-depot` (18 fichiers, ~1471 lignes ajoutees, aucune suppression). Commits du bolt : `6fbf91c` (spec), `d7fc9a1` (design), `4a8bec0`/`5ab1c61`/`3771017`/`275627a`/`16aa37d` (code + tests).

## Criteres d'acceptation

| # | Critere | Statut | Preuve |
|---|---|---|---|
| AC1 | Message publie sur file de notification a la creation d'une fiche DISPONIBLE | Couvert | `PieceService.creer(...)` publie `PieceDisponibleEvent` (AFTER_COMMIT via `AlerteCorrespondancePieceMatchingListener`) ; `AlerteCorrespondanceService.trouverEtNotifier` publie sur `QUEUE_CONSUME`. Teste par `AlerteCorrespondanceServiceTest.trouverEtNotifier_avecAlerteCorrespondante_devraitPublierUnMessage`, `PieceServiceTest.creer_devraitPublierPieceDisponibleEventAvecLesChampsAttendus`, et `AlerteCorrespondanceIntegrationTest.alerteActiveCorrespondante_devraitRecevoirUnSmsRapidement` (bout-en-bout). |
| AC2 | Consumer AMQP traite le message, retrouve les alertes correspondantes, declenche l'envoi SMS | Couvert (au sens pipeline, decision tranchee 1 assumee par la spec) | `AlerteCorrespondanceRetryListener.consommer(...)` (`@RabbitListener`) recharge alerte/piece et appelle `PasserelleSms.envoyer(...)`. Le rapprochement metier est fait en amont cote producteur, conformement a la decision documentee. Teste unitairement (`AlerteCorrespondanceRetryListenerTest.succes_neDoitPasRepublier`) et en integration. |
| AC3 | Delai d'envoi < 5 minutes en conditions normales | Couvert | `AlerteCorrespondanceIntegrationTest.alerteActiveCorrespondante_devraitRecevoirUnSmsRapidement` : `Awaitility.await().atMost(Duration.ofSeconds(10))` depuis l'appel HTTP de creation jusqu'a reception cote `FakePasserelleSms`, marge large par rapport a 5 minutes. |
| AC4 | Echec -> retry avec backoff -> dead-letter apres max tentatives | Couvert (perimetre "echec" limite aux exceptions synchrones listees, decision tranchee 3) | `AlerteCorrespondanceRetryListener.gererEchec(...)` implemente l'algorithme exact de la spec (`routingKeyPourTentative` : 1->30s, 2->2m, default->10m ; dead-letter si `tentativeSuivante > maxTentatives`). Couvert unitairement (`alerteIntrouvable_...`, `pieceIntrouvable_...`, `dechiffrementEnEchec_...`, `maxTentativesAtteint_devraitPartirEnDeadLetter`) et en integration (`dechiffrementTouoursEnEchec_devraitFinirEnDeadLetterApresMaxTentatives`, verifie `nombreTentatives == 4` en dead-letter avec `maxTentatives = 3`). |

Aucun critere non couvert ou partiel.

## Conformite au design/spec

- Les 4 decisions tranchees de `spec.md` (interpretation AC2 au niveau pipeline, pas de re-verification du statut `Piece` a l'envoi, perimetre exact de "l'echec", valeurs par defaut alignees sur `samapiece.sms.*`) sont respectees a la lettre -- aucune reouverture ni sur-ingenierie detectee (pas de verification `piece.getStatut()`, pas de tentative de detecter un echec de livraison SMS cote `PasserelleSms`).
- `PieceDisponibleEvent`, `AlerteCorrespondanceMessage`, `AlerteRepository.findByActiveTrueAndTypeDocumentAndNomTitulaireIgnoreCase`, `AlerteCorrespondanceRabbitConfig` (topologie, constantes, absence de second bean `MessageConverter`), `AlerteCorrespondanceProperties`, `application.yml`, la methode `correspond(...)`, et l'algorithme complet de `AlerteCorrespondanceRetryListener` correspondent mot pour mot au contrat technique de la spec.
- Migration `V8__index_alerte_correspondance.sql` fait bien suite a `V7__create_alerte.sql` (derniere version), index conforme (`CREATE INDEX idx_alerte_correspondance ON alerte(type_document, nom_titulaire) WHERE active = true;`).
- `PieceService.creer(...)` publie `PieceDisponibleEvent` juste apres `PieceIndexableEvent`, dans le meme bloc/memes garanties transactionnelles ; la publication de `PieceIndexableEvent` (Meilisearch, #19) n'est pas modifiee (diff confirme : ajout pur, aucune ligne supprimee dans `PieceService.java` avant l'ajout).
- Aucune modification de `PasserelleSms`, `PasserelleSmsHttpClient`, `SmsRabbitConfig`, `SmsRetryListener` (diff `sn/samapiece/notifications/` vide) -- le ticket ne touche que du nouveau code + `PieceService`/`AlerteRepository`.

## Points de vigilance specifiques -- verifies

1. **Pas de PII loguee** : grep exhaustif sur `LOG.(info|warn|error|debug)` dans `sn.samapiece.alertes` et `PieceService` -- aucune occurrence de `numeroDocumentClair`, `nomTitulaire`, `prenomTitulaire`, `dateNaissance` ou contact dechiffre. `AlerteCorrespondanceRetryListenerTest.succes_neDoitPasRepublier` confirme que `NumeroTelephone` (masque via `toString()`) est la seule donnee liee au contact manipulee apres reconstruction.
2. **Pas de second bean `MessageConverter`** : confirme par lecture complete de `AlerteCorrespondanceRabbitConfig.java` -- seuls `DirectExchange`, `Queue`, `Binding` sont declares.
3. **Re-verification `alerte.isActive()` a la consommation** : `AlerteCorrespondanceRetryListener.traiter(...)` fait bien un `return` simple (log INFO, pas d'exception, pas de republication, pas d'appel a `PasserelleSms`) si l'alerte rechargee est inactive -- teste par `alerteInactiveAuMomentDeLaConsommation_neDoitPasEnvoyerNiRepublier` (verifie `verifyNoInteractions(passerelleSms)` et `verifyNoInteractions(rabbitTemplate)`).
4. **Algorithme retry/dead-letter** conforme, `PasserelleSms`/`PasserelleSmsHttpClient` non touches (aucun mecanisme de detection d'echec de livraison invente).
5. **Contenu du SMS** : `construireMessage(Piece piece)` n'utilise que `piece.getNumeroFiche()` et `piece.getPoste().getNom()` -- verifie en lecture et par assertion explicite en integration (`doesNotContain("Fall").doesNotContain("Moussa").doesNotContain(NUMERO_DOCUMENT)`).
6. **Filtrage `correspond(...)`** : implementation identique au contrat technique (ordre prenom -> date de naissance -> numero de document via `NumeroDocumentHasher.verifier`, retour anticipe), shortlist SQL utilisee en amont dans `trouverEtNotifier`.
7. **`PieceService.creer(...)`** : publication confirmee dans le meme emplacement transactionnel, sans regression sur `PieceIndexableEvent` (diff = ajout pur).
8. **Aucune modification de `PasserelleSms`/`SmsRabbitConfig`/`SmsRetryListener`** : confirme (diff vide sur `sn/samapiece/notifications/`).
9. **Migration V8** : conforme (voir ci-dessus).
10. **Tests** : `AlerteCorrespondanceIntegrationTest` utilise systematiquement `getContentAsString(StandardCharsets.UTF_8)` ; ordre de nettoyage respecte (`alerteRepository.deleteAll()`, `pieceRepository.deleteAll()`, puis `DELETE FROM piece_sequence`, puis `agentRepository`/`posteRepository`/`regionRepository`).
11. Build/tests executes (voir ci-dessous).

## Bugs / risques reels recherches

Aucun bug bloquant trouve. Un point mineur releve a titre informatif (non bloquant, ne justifie pas `CHANGES_REQUESTED`) :

- `backend/src/test/resources/application.yml` ne definit pas `spring.rabbitmq.host` (contrairement a `application.yml` de prod qui exige `${RABBITMQ_HOST}` sans defaut). Cela signifie que les `@SpringBootTest` a contexte complet sans conteneur RabbitMQ dedie (ex. `PieceIndexationIntegrationTest`, herite de #19/#21, non modifie par ce ticket) demarrent avec le connection factory Rabbit par defaut (`localhost:5672`) sans broker reel disponible. Analyse : cela ne casse pas ces tests dans la pratique, car (a) Spring AMQP ne bloque pas le demarrage du contexte sur un echec de connexion (retry en arriere-plan), et (b) `AlerteCorrespondanceService.trouverEtNotifier` n'appelle `rabbitTemplate.convertAndSend` que si la shortlist SQL retourne au moins une alerte correspondante -- absent dans ces tests qui ne creent pas d'`Alerte`. Ce comportement preexistait a l'identique avec `SmsRabbitConfig` depuis #21 (deja approuve) ; #23 n'introduit pas de nouveau risque de ce type, juste une deuxieme config Rabbit soumise a la meme hypothese implicite. Signale pour information, pas comme regression de ce ticket.

## Build / tests

- `mvn -q -pl backend -am compile` -> succes (silencieux, code 0).
- `mvn -q -pl backend -am test-compile` -> succes (silencieux, code 0).
- `mvn -pl backend -am test -Dtest=PieceServiceTest,AlerteCorrespondanceServiceTest,AlerteCorrespondanceRetryListenerTest` -> BUILD SUCCESS, Tests run: 13, Failures: 0, Errors: 0.
- `mvn -pl backend -am test -Dtest="sn.samapiece.alertes.**,sn.samapiece.enregistrement.**"` -> 71 tests, 66 passent, 5 erreurs -- toutes les 5 sont des ContainerFetchException/Docker environment (Testcontainers indisponible dans ce sandbox Windows sans Docker), sur AlerteCorrespondanceIntegrationTest, AlerteIntegrationTest, PieceNumeroFicheGeneratorTest, PhotoIntegrationTest, PieceIntegrationTest -- limitation connue deja rencontree sur #5/#19/#21/#22, pas un echec de code.
- `mvn -pl backend -am test -Dtest="sn.samapiece.recherche.**"` (regression Meilisearch/#19, y compris PieceIndexationIntegrationTest qui partage le point de publication d'evenement AFTER_COMMIT avec PieceDisponibleEvent) -> 19 tests, 15 passent, 4 erreurs -- meme cause exclusive (Testcontainers/Docker indisponible : PieceIndexationIntegrationTest, PieceIndexationBestEffortIntegrationTest, RecherchePubliqueIntegrationTest, RecherchePubliqueMeilisearchIndisponibleIntegrationTest). Aucune trace d'echec logique ou de regression liee au code de #23 dans ces traces (toutes les erreurs s'arretent a DockerClientProviderStrategy/ContainerFetchException, avant toute execution de code applicatif).
- AlerteCorrespondanceIntegrationTest n'a pas pu etre execute (Testcontainers Postgres+Meilisearch+RabbitMQ indisponibles) ; compense par une relecture manuelle complete (voir sections ci-dessus) du test et du code de production qu'il exerce -- topologie Rabbit, nettoyage, assertions de contenu SMS, dead-letter, desinscription en cours de route.

## Conclusion

Le code respecte fidelement le contrat technique et les decisions tranchees de la spec, sans reinterpretation ni sur-ingenierie. Aucune donnee personnelle n'est loguee ou transmise dans le SMS au-dela de numeroFiche/nom du poste. La topologie AMQP, l'algorithme de retry/dead-letter, et le filtrage en memoire sont conformes. Le build compile et les tests executables localement passent tous ; les echecs restants sont exclusivement dus a l'indisponibilite de Docker/Testcontainers dans ce sandbox, limitation deja rencontree et actee sur les tickets precedents.

**Verdict : APPROVE**
