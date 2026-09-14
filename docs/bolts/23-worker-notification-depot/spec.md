# Spec -- #23 Worker d'envoi de notifications sur depot correspondant

## Resume

Livrer le pipeline complet declenche a la creation d'une Piece DISPONIBLE : rapprochement alerte<->piece en-process (AFTER_COMMIT, cote `enregistrement`), publication d'un message minimal par correspondance trouvee sur une nouvelle file RabbitMQ dediee `alerte.correspondance.*`, et un consumer (`AlerteCorrespondanceRetryListener`) qui recharge les donnees fraiches, re-verifie l'etat de l'alerte, declenche l'envoi SMS via `PasserelleSms`, et gere lui-meme le retry/backoff/dead-letter applicatif.

## Decisions tranchees (fermeture des 4 points ouverts du design)

Ces quatre decisions sont definitives pour ce ticket ; le codeur ne doit pas les rouvrir ni inventer d'alternative.

### 1. Interpretation de l'AC2

L'AC2 ("Un consumer Spring AMQP traite le message, retrouve les alertes correspondantes, declenche l'envoi SMS") est satisfait au niveau du **comportement observable du pipeline dans son ensemble** (publication -> consommation -> SMS), pas litteralement par le code execute dans la methode annotee `@RabbitListener`. Le rapprochement metier (shortlist SQL + filtrage en memoire, y compris `NumeroDocumentHasher.verifier`) est fait en producteur, dans `AlerteCorrespondanceService.trouverEtNotifier(...)`, appele depuis un `@TransactionalEventListener(phase = AFTER_COMMIT)` sur `PieceDisponibleEvent` -- exactement le pattern deja en place pour l'indexation Meilisearch (`PieceIndexationListener`). Le consumer AMQP (`AlerteCorrespondanceRetryListener`) re-verifie l'etat de l'alerte au moment de la consommation (voir section Risques du design) et declenche l'envoi SMS, mais ne refait pas le rapprochement type/nom/prenom/date/numero-document a partir de zero.

C'est un choix assume de confidentialite : le numero de document en clair n'est **jamais** serialise ni transmis sur le broker RabbitMQ, y compris dans les files de retry durables. Documenter cette divergence de lecture litterale de l'AC2 dans le message de PR/commentaire de review si necessaire, mais ne pas la re-questionner en codant.

### 2. Piece retiree entre creation et envoi

Le worker **ne verifie pas** le statut courant de la Piece au moment de l'envoi. `AlerteCorrespondanceRetryListener` recharge la `Piece` par id uniquement pour composer un message avec des donnees fraiches (`numeroFiche`, nom du poste) -- il ne compare jamais `piece.getStatut()` a `StatutPiece.DISPONIBLE`. Le raisonnement : le depot a bel et bien eu lieu, c'est l'evenement de depot qui declenche la notification, pas un etat courant remis en cause ensuite. Compromis assume : dans un cas limite tres rare (retrait de la piece dans la fenetre de quelques secondes/minutes entre creation et envoi), une notification pourrait annoncer une fiche qui n'est techniquement plus disponible au moment ou le citoyen se presente au poste. Ce compromis est volontaire et ne doit pas etre "corrige" par une verification de statut ajoutee de sa propre initiative (sur-ingenierie non demandee par les criteres d'acceptation).

### 3. Notion d'"echec" pour le retry/dead-letter de l'AC4

**Perimetre exact de l'"echec" couvert par ce ticket** : toute exception synchrone levee pendant le traitement du message par `AlerteCorrespondanceRetryListener`, a savoir :
- `Alerte` introuvable en base (`AlerteRepository.findById(...)` vide) ;
- `Piece` introuvable en base (`PieceRepository.findById(...)` vide) ;
- echec de dechiffrement du contact (`AlerteContactChiffrementService.dechiffrer(...)` leve `IllegalStateException`) ;
- `NumeroTelephone.de(...)` invalide (`IllegalArgumentException`).

Ces cas declenchent le meme algorithme de republication/backoff/dead-letter que `SmsRetryListener` (voir Contrat technique).

**Hors de ce perimetre, explicitement** : un echec de livraison SMS proprement dit (timeout HTTP, erreur reseau ou reponse non-2xx vers la passerelle) n'est **jamais** visible depuis ce worker. `PasserelleSms.envoyer(...)` est un contrat fire-and-forget (cf. Javadoc de `PasserelleSms`) : il ne renvoie rien et ne leve pas d'exception pour un echec de transport, qu'il gere entierement en interne via la file technique `sms.retry.*` (#21). **Ne pas** tenter d'inventer un mecanisme pour detecter un tel echec (pas de sondage, pas d'extension du contrat `PasserelleSms`, pas de retour de statut) : ce n'est pas demande par ce ticket et deborderait sur #21 (cf. Hors perimetre du design).

Une alerte trouvee mais desinscrite entre publication et consommation (`alerte.isActive() == false`) n'est **pas** un echec : c'est un arret normal du traitement (return sans exception, sans republication, sans dead-letter, sans appel a `PasserelleSms`).

### 4. Valeurs par defaut `max-tentatives` / TTL

Confirmees identiques a `samapiece.sms.*` : `max-tentatives = 3`, `retry-ttl-30s-ms = 30000`, `retry-ttl-2m-ms = 120000`, `retry-ttl-10m-ms = 600000`. Aucune exigence produit specifique ne justifie de les differencier a ce stade -- ne pas sur-ingenierer. La configuration reste neanmoins dans des cles distinctes (`samapiece.alerte-correspondance.*`) pour permettre un reglage independant plus tard sans redeploiement de code (cf. design, Decision cle 3).

## Rappels de vigilance (a faire respecter par le codeur)

- **Jamais** logger `PieceDisponibleEvent.numeroDocumentClair()` en clair, ni le contact dechiffre d'une alerte, ni aucune autre donnee personnelle en clair. Seul un `NumeroTelephone` (masque via son `toString()`) peut apparaitre dans un log impliquant un contact -- jamais `NumeroTelephone.valeurBrute()`, jamais une concatenation de `String` brute.
- Tout `assertThat(... .getResponse().getContentAsString(...))` dans un test MockMvc doit utiliser `getContentAsString(StandardCharsets.UTF_8)`, jamais sans argument.
- Si un test `@SpringBootTest` cree des `Piece`/`Poste`, respecter l'ordre de nettoyage : vider `piece_sequence` (via `JdbcTemplate`, `DELETE FROM piece_sequence`) avant `posteRepository.deleteAll()` (FK), meme correctif que sur `PieceIntegrationTest`/`PieceIndexationIntegrationTest`.
- Aucune nouvelle dependance d'infrastructure n'est introduite (RabbitMQ deja present depuis #21) -- pas de nouveau health indicator a gerer a priori, mais rester vigilant si un bean lie a RabbitMQ change de comportement au demarrage.
- Re-verifier `alerte.isActive()` (donc `contactChiffre`/`contactIv` non nuls, cf. `Alerte.desinscrire()`) **au moment de la consommation**, jamais seulement au moment de la publication.
- **Ne pas declarer de second bean `MessageConverter`** dans `AlerteCorrespondanceRabbitConfig` : `SmsRabbitConfig` en declare deja un (`Jackson2JsonMessageConverter`) partage par tout le contexte Spring. Un second bean du meme type casserait la resolution automatique du convertisseur par `RabbitAutoConfiguration` (bascule silencieuse vers le `SimpleMessageConverter` par defaut, qui ne sait pas serialiser un `record`). `AlerteCorrespondanceRabbitConfig` ne declare que l'exchange, les queues et les bindings.

## Taches

- [ ] `backend/src/main/resources/db/migration/V8__index_alerte_correspondance.sql` -- nouvelle migration Flyway : `CREATE INDEX idx_alerte_correspondance ON alerte(type_document, nom_titulaire) WHERE active = true;` (V7 est la derniere version utilisee).
- [ ] `backend/src/main/java/sn/samapiece/enregistrement/PieceDisponibleEvent.java` -- nouveau record : `PieceDisponibleEvent(UUID pieceId, TypeDocument typeDocument, String nomTitulaire, String prenomTitulaire, String numeroDocumentClair, LocalDate dateNaissanceTitulaire, String numeroFiche, String posteNom)`. Evenement Spring interne uniquement, jamais serialise sur le broker. Javadoc obligatoire rappelant que `numeroDocumentClair` ne doit jamais etre logue.
- [ ] `backend/src/main/java/sn/samapiece/enregistrement/PieceService.java` -- dans `creer(...)`, juste apres la publication de `PieceIndexableEvent` (meme emplacement, memes garanties transactionnelles), publier `new PieceDisponibleEvent(piece.getId(), piece.getTypeDocument(), piece.getNomTitulaire(), piece.getPrenomTitulaire(), request.numeroDocument(), piece.getDateNaissanceTitulaire(), piece.getNumeroFiche(), piece.getPoste().getNom())` via `eventPublisher.publishEvent(...)`. Le numero de document clair vient de `request.numeroDocument()` (jamais persiste par `Piece`, donc uniquement disponible ici).
- [ ] `backend/src/main/java/sn/samapiece/alertes/AlerteRepository.java` -- ajouter `List<Alerte> findByActiveTrueAndTypeDocumentAndNomTitulaireIgnoreCase(TypeDocument typeDocument, String nomTitulaire);`.
- [ ] `backend/src/main/java/sn/samapiece/alertes/AlerteCorrespondanceMessage.java` -- nouveau record : `AlerteCorrespondanceMessage(UUID alerteId, UUID pieceId, int nombreTentatives)`. Javadoc precisant la semantique de `nombreTentatives` (voir Contrat technique) et rappelant qu'aucune donnee personnelle ne doit y transiter.
- [ ] `backend/src/main/java/sn/samapiece/alertes/AlerteCorrespondanceRabbitConfig.java` -- topologie AMQP dediee : exchange `alerte.correspondance.exchange` (`DirectExchange`), queues `alerte.correspondance.retry.30s` / `.retry.2m` / `.retry.10m` (chacune avec TTL + `deadLetterExchange`/`deadLetterRoutingKey` vers `alerte.correspondance.consume`), `alerte.correspondance.consume`, `alerte.correspondance.dead-letter`, plus les bindings correspondants. Calquee sur `SmsRabbitConfig`. **Ne pas** y redeclarer de bean `MessageConverter` (cf. Rappels de vigilance).
- [ ] `backend/src/main/java/sn/samapiece/alertes/AlerteCorrespondanceProperties.java` -- `@Component @Validated @ConfigurationProperties(prefix = "samapiece.alerte-correspondance")` avec `maxTentatives` (`@Min(1)`), `retryTtl30sMs`/`retryTtl2mMs`/`retryTtl10mMs` (`@Positive`), copie structurelle de `SmsProperties` (getters/setters).
- [ ] `backend/src/main/resources/application.yml` -- ajouter sous `samapiece:` :
  ```yaml
  alerte-correspondance:
    max-tentatives: ${ALERTE_CORRESPONDANCE_MAX_TENTATIVES:3}
    retry-ttl-30s-ms: 30000
    retry-ttl-2m-ms: 120000
    retry-ttl-10m-ms: 600000
  ```
- [ ] `backend/src/main/java/sn/samapiece/alertes/AlerteCorrespondanceService.java` -- `@Service`, methode `void trouverEtNotifier(PieceDisponibleEvent evenement)` : shortlist via `AlerteRepository.findByActiveTrueAndTypeDocumentAndNomTitulaireIgnoreCase(evenement.typeDocument(), evenement.nomTitulaire())`, puis filtrage en memoire (`correspond(Alerte, PieceDisponibleEvent)`, voir Contrat technique), puis pour chaque alerte retenue : `rabbitTemplate.convertAndSend(AlerteCorrespondanceRabbitConfig.EXCHANGE, AlerteCorrespondanceRabbitConfig.QUEUE_CONSUME, new AlerteCorrespondanceMessage(alerte.getId(), evenement.pieceId(), 0))`. Log `INFO` uniquement avec des identifiants (`alerte.getId()`, `evenement.pieceId()`), jamais de donnee personnelle.
- [ ] `backend/src/main/java/sn/samapiece/alertes/AlerteCorrespondancePieceMatchingListener.java` -- `@Component`, `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` sur `PieceDisponibleEvent`, delegue integralement a `AlerteCorrespondanceService.trouverEtNotifier(...)`. Calque exact de `PieceIndexationListener`.
- [ ] `backend/src/main/java/sn/samapiece/alertes/AlerteCorrespondanceRetryListener.java` -- `@Component`, `@RabbitListener(queues = AlerteCorrespondanceRabbitConfig.QUEUE_CONSUME)`, methode publique `@Transactional void consommer(AlerteCorrespondanceMessage message)` qui delegue a un traitement interne (`try { traiter(message); } catch (Exception e) { gererEchec(message, e); }`), voir Contrat technique pour le detail exact du traitement et de l'algorithme de retry/dead-letter. `@Transactional` necessaire pour que `piece.getPoste().getNom()` (association lazy) reste accessible pendant la composition du message SMS.
- [ ] Tests unitaires (Mockito, sans Spring context) -- `backend/src/test/java/sn/samapiece/enregistrement/PieceServiceTest.java` (nouveau) : verifie que `creer(...)` publie bien un `PieceDisponibleEvent` avec les champs attendus (notamment `numeroDocumentClair == request.numeroDocument()`), en plus du `PieceIndexableEvent` deja publie.
- [ ] Tests unitaires (Mockito) -- `backend/src/test/java/sn/samapiece/alertes/AlerteCorrespondanceServiceTest.java` (nouveau) : couvre le filtrage en memoire (voir Plan de tests).
- [ ] Tests unitaires (Mockito) -- `backend/src/test/java/sn/samapiece/alertes/AlerteCorrespondanceRetryListenerTest.java` (nouveau) : couvre les cas d'echec/succes/inactivite et l'algorithme de backoff (voir Plan de tests).
- [ ] Test d'integration bout-en-bout -- `backend/src/test/java/sn/samapiece/alertes/AlerteCorrespondanceIntegrationTest.java` (nouveau, Testcontainers Postgres + Meilisearch + RabbitMQ) : couvre les AC1-AC4 et les cas limites du design (voir Plan de tests).

Frontend : hors perimetre, aucune tache.

## Contrat technique

### `PieceDisponibleEvent` (evenement Spring interne, jamais serialise)

```java
public record PieceDisponibleEvent(
        UUID pieceId,
        TypeDocument typeDocument,
        String nomTitulaire,
        String prenomTitulaire,
        String numeroDocumentClair,
        LocalDate dateNaissanceTitulaire,
        String numeroFiche,
        String posteNom) {}
```

### `AlerteCorrespondanceMessage` (payload AMQP, serialise en JSON via `Jackson2JsonMessageConverter`)

```java
public record AlerteCorrespondanceMessage(UUID alerteId, UUID pieceId, int nombreTentatives) {}
```

Exemple de payload : `{"alerteId":"...","pieceId":"...","nombreTentatives":0}`. Aucun champ ne porte de donnee personnelle.

Semantique de `nombreTentatives` (comme `SmsRetryMessage.nombreTentatives`, mais decalee car il n'y a pas de tentative directe hors file ici) :
- Le message initial, publie par `AlerteCorrespondanceService`, va **directement** sur `AlerteCorrespondanceRabbitConfig.QUEUE_CONSUME` (pas sur un etage de retry) avec `nombreTentatives = 0` -- aucune tentative n'a encore ete faite.
- A chaque echec dans `AlerteCorrespondanceRetryListener`, la valeur republiee est `nombreTentatives + 1`.
- `routingKeyPourTentative(int tentative)` : `1 -> QUEUE_RETRY_30S`, `2 -> QUEUE_RETRY_2M`, `default -> QUEUE_RETRY_10M` (identique a `SmsRetryListener.routingKeyPourTentative`).
- Quand `tentativeSuivante > proprietes.getMaxTentatives()` (donc `4` avec `maxTentatives = 3`), le message est republie vers `QUEUE_DEAD_LETTER` avec cette valeur, jamais consomme applicativement ensuite.

### Topologie AMQP -- `AlerteCorrespondanceRabbitConfig`

| Constante | Valeur |
|---|---|
| `EXCHANGE` | `alerte.correspondance.exchange` |
| `QUEUE_RETRY_30S` | `alerte.correspondance.retry.30s` |
| `QUEUE_RETRY_2M` | `alerte.correspondance.retry.2m` |
| `QUEUE_RETRY_10M` | `alerte.correspondance.retry.10m` |
| `QUEUE_CONSUME` | `alerte.correspondance.consume` |
| `QUEUE_DEAD_LETTER` | `alerte.correspondance.dead-letter` |

Chaque queue de retry est un `DirectExchange` binding avec routing key = nom de la queue (comme `SmsRabbitConfig`) ; chaque queue de retry a un TTL (`proprietes.getRetryTtl30sMs()` etc.) et route vers `QUEUE_CONSUME` via `deadLetterExchange(EXCHANGE)` + `deadLetterRoutingKey(QUEUE_CONSUME)` a expiration.

### Filtrage en memoire -- `AlerteCorrespondanceService.correspond(Alerte alerte, PieceDisponibleEvent evenement)`

La shortlist SQL (`findByActiveTrueAndTypeDocumentAndNomTitulaireIgnoreCase`) a deja filtre sur `active = true`, `typeDocument` exact et `nomTitulaire` (ignoreCase). Le filtrage en memoire restant, dans cet ordre (retour anticipe `false` des le premier critere non satisfait) :

```java
private boolean correspond(Alerte alerte, PieceDisponibleEvent evenement) {
    if (alerte.getPrenomTitulaire() != null && !alerte.getPrenomTitulaire().isBlank()
            && !alerte.getPrenomTitulaire().equalsIgnoreCase(evenement.prenomTitulaire())) {
        return false;
    }
    if (alerte.getDateNaissanceTitulaire() != null
            && !alerte.getDateNaissanceTitulaire().equals(evenement.dateNaissanceTitulaire())) {
        return false;
    }
    boolean numeroDocumentAlerteFourni =
            alerte.getNumeroDocumentHash() != null && alerte.getNumeroDocumentSel() != null;
    if (numeroDocumentAlerteFourni && !numeroDocumentHasher.verifier(
            evenement.numeroDocumentClair(), alerte.getNumeroDocumentSel(), alerte.getNumeroDocumentHash())) {
        return false;
    }
    return true;
}
```

Note : `evenement.numeroDocumentClair()` et `evenement.prenomTitulaire()` sont **toujours** renseignes (`CreerPieceRequest` les rend obligatoires), donc le critere numero de document ne s'applique en pratique que "si fourni cote alerte" (le cote evenement l'est toujours). `evenement.dateNaissanceTitulaire()` peut etre `null` (champ optionnel sur `Piece`) : si l'alerte precise une date et que la piece n'en a pas, `!alerte.getDateNaissanceTitulaire().equals(null)` vaut `true` -> pas de correspondance, ce qui est le comportement attendu.

### `AlerteCorrespondanceRetryListener.consommer(...)` -- algorithme complet

```java
@Transactional
@RabbitListener(queues = AlerteCorrespondanceRabbitConfig.QUEUE_CONSUME)
public void consommer(AlerteCorrespondanceMessage message) {
    try {
        traiter(message);
    } catch (Exception exception) {
        gererEchec(message, exception);
    }
}

private void traiter(AlerteCorrespondanceMessage message) {
    Alerte alerte = alerteRepository.findById(message.alerteId())
            .orElseThrow(() -> new IllegalStateException("Alerte introuvable pour la notification de correspondance."));
    if (!alerte.isActive()) {
        LOG.info("Alerte {} desinscrite avant l'envoi, notification annulee.", message.alerteId());
        return;
    }
    Piece piece = pieceRepository.findById(message.pieceId())
            .orElseThrow(() -> new IllegalStateException("Piece introuvable pour la notification de correspondance."));
    byte[] contactClair = chiffrementService.dechiffrer(alerte.getContactChiffre(), alerte.getContactIv());
    NumeroTelephone destinataire = NumeroTelephone.de(new String(contactClair, StandardCharsets.UTF_8));
    passerelleSms.envoyer(destinataire, construireMessage(piece));
}

private void gererEchec(AlerteCorrespondanceMessage message, Exception exception) {
    int tentativeSuivante = message.nombreTentatives() + 1;
    if (tentativeSuivante > proprietes.getMaxTentatives()) {
        LOG.error("Nombre maximal de tentatives ({}) atteint pour l'alerte {} / piece {}, envoi vers {}",
                proprietes.getMaxTentatives(), message.alerteId(), message.pieceId(),
                AlerteCorrespondanceRabbitConfig.QUEUE_DEAD_LETTER, exception);
        rabbitTemplate.convertAndSend(AlerteCorrespondanceRabbitConfig.EXCHANGE,
                AlerteCorrespondanceRabbitConfig.QUEUE_DEAD_LETTER,
                new AlerteCorrespondanceMessage(message.alerteId(), message.pieceId(), tentativeSuivante));
        return;
    }
    String routingKeySuivant = routingKeyPourTentative(tentativeSuivante);
    LOG.warn("Nouvel echec pour l'alerte {} / piece {}, republication vers {} (tentative {})",
            message.alerteId(), message.pieceId(), routingKeySuivant, tentativeSuivante, exception);
    rabbitTemplate.convertAndSend(AlerteCorrespondanceRabbitConfig.EXCHANGE, routingKeySuivant,
            new AlerteCorrespondanceMessage(message.alerteId(), message.pieceId(), tentativeSuivante));
}
```

`construireMessage(Piece piece)` : texte indicatif, le codeur peut ajuster la formulation exacte tant qu'il inclut `piece.getNumeroFiche()` et `piece.getPoste().getNom()`, et qu'il **n'inclut pas** `numeroDocument`, `nomTitulaire`/`prenomTitulaire` ni `dateNaissanceTitulaire` (minimisation des donnees dans le SMS sortant, la fiche suffit pour se presenter au poste).

Aucun log de cette classe ne doit afficher `contactClair` ni la valeur brute d'un numero de telephone : seul `destinataire` (un `NumeroTelephone`, masque via `toString()`) peut apparaitre dans un log, et uniquement apres sa construction reussie.

### RBAC / securite

Aucun nouvel endpoint HTTP n'est introduit par ce ticket (worker interne pur, cf. Hors perimetre du design) : pas de nouvelle regle `@PreAuthorize` a ecrire.

## Plan de tests

| Critere d'acceptation / risque | Test |
|---|---|
| AC1 -- un message est publie a la creation d'une fiche DISPONIBLE avec alerte correspondante | `AlerteCorrespondanceServiceTest.trouverEtNotifier_avecAlerteCorrespondante_devraitPublierUnMessage` (unitaire, Mockito : verifie `rabbitTemplate.convertAndSend(EXCHANGE, QUEUE_CONSUME, new AlerteCorrespondanceMessage(alerteId, pieceId, 0))`) + `AlerteCorrespondanceIntegrationTest` (integration, voir ci-dessous) |
| AC2 -- le consumer traite le message, retrouve les alertes correspondantes (au sens pipeline, cf. Decision tranchee 1), declenche l'envoi SMS | `AlerteCorrespondanceIntegrationTest.alerteActiveCorrespondante_devraitRecevoirUnSmsRapidement` : creation d'une `Piece` via `POST /api/v1/pieces` (agent authentifie), assertion `Awaitility` sur `FakePasserelleSms.envois` recevant un envoi pour le contact de l'alerte |
| AC3 -- delai d'envoi < 5 minutes en conditions normales | Meme test que ci-dessus : TTL de backoff reduits a ~200 ms via `@DynamicPropertySource` (`samapiece.alerte-correspondance.retry-ttl-*-ms`), assertion `Awaitility.await().atMost(Duration.ofSeconds(10))` sur la reception cote `FakePasserelleSms` depuis l'appel HTTP de creation -- demontre un delai de l'ordre de la seconde en chemin nominal (aucun echec), donc une marge tres large par rapport a 5 minutes, sans jamais attendre 5 minutes reelles en CI. Ne pas confondre avec le chemin d'echec (voir AC4), qui depasse volontairement 5 minutes par construction et n'est pas concerne par cette AC ("conditions normales"). |
| AC4 -- en cas d'echec, backoff puis dead-letter apres max tentatives | `AlerteCorrespondanceIntegrationTest.dechiffrementTouoursEnEchec_devraitFinirEnDeadLetterApresMaxTentatives` : alerte creee avec un `contact_iv` corrompu (ecrit directement via `JdbcTemplate` apres creation, pour forcer `AlerteContactChiffrementService.dechiffrer(...)` a lever systematiquement), TTL reduits, assertion `Awaitility` sur reception d'un `AlerteCorrespondanceMessage` avec `nombreTentatives == 4` dans `alerte.correspondance.dead-letter` (calque exact sur `SmsRetryIntegrationTest.numeroTouoursEnEchec_devraitFinirEnDeadLetterApresMaxTentatives`) |
| Algorithme de retry/backoff -- detail unitaire | `AlerteCorrespondanceRetryListenerTest` (Mockito, sans broker reel) : `alerteIntrouvable_devraitRepublierVersEtageSuivant`, `pieceIntrouvable_devraitRepublierVersEtageSuivant`, `dechiffrementEnEchec_devraitRepublierVersEtageSuivant`, `maxTentativesAtteint_devraitPartirEnDeadLetter` (message recu avec `nombreTentatives == proprietes.getMaxTentatives()`, verifie `convertAndSend(..., QUEUE_DEAD_LETTER, ...)`), `succes_neDoitPasRepublier` (verifie `passerelleSms.envoyer(...)` appele une fois, `rabbitTemplate` jamais sollicite) |
| Risque -- alerte inactive des la publication (desinscrite avant la creation de la piece) jamais notifiee | `AlerteCorrespondanceServiceTest.trouverEtNotifier_sansAlerteActive_neDevraitRienPublier` (le mock `AlerteRepository` renvoie une liste vide, simulant le filtre SQL `active = true`) + `AlerteCorrespondanceIntegrationTest.alerteDesinscriteAvantDepot_neDevraitJamaisEtreNotifiee` (integration : alerte desinscrite via `DELETE /api/v1/alertes/{token}` avant creation de la piece correspondante, assertion `Awaitility` que `FakePasserelleSms.envois` reste vide apres une fenetre d'attente courte) |
| Risque -- alerte desinscrite entre publication et consommation (change d'etat pendant le backoff) jamais notifiee | `AlerteCorrespondanceRetryListenerTest.alerteInactiveAuMomentDeLaConsommation_neDoitPasEnvoyerNiRepublier` (mock `AlerteRepository.findById(...)` renvoie une `Alerte` avec `isActive() == false` ; verifie qu'aucun appel a `passerelleSms.envoyer(...)` ni a `rabbitTemplate.convertAndSend(...)` n'est fait -- pas un echec, donc pas de republication) |
| Risque -- alerte avec `numeroDocument` non correspondant non notifiee | `AlerteCorrespondanceServiceTest.trouverEtNotifier_avecNumeroDocumentNonCorrespondant_neDevraitPasPublierPourCetteAlerte` |
| Risque -- alerte sans discriminant `numeroDocument` mais avec `dateNaissance` correspondante notifiee | `AlerteCorrespondanceServiceTest.trouverEtNotifier_sansDiscriminantNumeroDocumentMaisDateNaissanceCorrespondante_devraitPublier` |
| Risque -- alerte avec `prenom` non correspondant non notifiee | `AlerteCorrespondanceServiceTest.trouverEtNotifier_avecPrenomNonCorrespondant_neDevraitPasPublier` |
| Risque -- plusieurs alertes correspondantes pour une meme piece | `AlerteCorrespondanceServiceTest.trouverEtNotifier_plusieursAlertesCorrespondantes_devraitPublierUnMessageParAlerte` (verifie `rabbitTemplate.convertAndSend(...)` appele autant de fois qu'il y a d'alertes retenues, avec le bon `alerteId` a chaque fois) |
| Fondation AC1 -- `PieceService.creer()` publie bien l'evenement declencheur | `PieceServiceTest.creer_devraitPublierPieceDisponibleEventAvecLesChampsAttendus` (Mockito : capture de l'`ApplicationEventPublisher`, verifie `numeroDocumentClair == request.numeroDocument()`, `pieceId`, `typeDocument`, `nomTitulaire`, `prenomTitulaire`, `numeroFiche`, `posteNom` corrects), en plus de la publication deja existante de `PieceIndexableEvent` |
| Non regression -- pas de deuxieme bean `MessageConverter` | Verification manuelle (pas de test automatise dedie pertinent) : au demarrage de `AlerteCorrespondanceIntegrationTest` (contexte Spring complet avec les deux configs Rabbit chargees), l'absence d'erreur de demarrage et la bonne deserialisation JSON de `AlerteCorrespondanceMessage`/`SmsRetryMessage` dans les tests d'integration existants et nouveaux valent verification de non-regression. |

Pour `AlerteCorrespondanceIntegrationTest` : reprendre l'infrastructure de `PieceIndexationIntegrationTest` (Postgres + Meilisearch, agent/poste/login MockMvc pour `POST /api/v1/pieces`) **et** ajouter un `RabbitMQContainer` comme dans `SmsRetryIntegrationTest`, plutot que de desactiver `PieceIndexationListener` : cela evite toute incertitude sur l'enchainement des listeners `AFTER_COMMIT` (indexation Meilisearch et rapprochement d'alerte sont deux listeners independants sur deux evenements differents, mais partagent la meme transaction de creation). Nettoyer `alerteRepository`, `pieceRepository`, `piece_sequence` (avant `posteRepository`), `agentRepository`, `posteRepository`, `regionRepository`, et purger les queues `alerte.correspondance.*` avant chaque test (comme `SmsRetryIntegrationTest.nettoyerLesFiles`). Utiliser `FakePasserelleSms` (deja existant dans `sn.samapiece.alertes`, package de test) via `@TestConfiguration`/`@Primary`, comme dans `AlerteIntegrationTest`. Creer les alertes de test directement via `AlerteRepository`/`AlerteContactChiffrementService` (pas besoin de passer par l'endpoint public `POST /api/v1/alertes`, sauf pour le cas "desinscrite avant depot" qui a besoin du flux de desinscription reel).

## Ecarts identifies

Aucun ecart bloquant restant : les quatre points ouverts du design sont fermes ci-dessus et sont coherents avec les criteres d'acceptation du ticket une fois l'AC2 relue au niveau du pipeline (cf. Decision tranchee 1). Deux points de perimetre, deja actes dans le design comme hors scope, sont rappeles ici pour eviter toute derive du codeur :
- Le renforcement du contrat `PasserelleSms` (ajout d'un statut de succes/echec synchrone) reste explicitement hors perimetre de #23 -- ne pas le faire "au passage".
- La notification du citoyen deposant (accuse de reception, paragraphe 7.3 du cahier des charges) reste hors perimetre de #23, qui ne couvre que la notification du citoyen chercheur (alerte).
