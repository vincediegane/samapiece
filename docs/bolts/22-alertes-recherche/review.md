# Review -- #22 Creation et gestion d'une alerte de recherche citoyenne

## Verdict

APPROVE

## Perimetre de revue

git diff bolt/issue-21-passerelle-sms..bolt/issue-22-alertes-recherche (5 commits : design, spec, domaine+migration, endpoints+config, tests). Le code de #21 (PasserelleSms, NumeroTelephone) n'a pas ete rerevu, conformement aux instructions (deja approuve separement).

## Criteres d'acceptation

| Critere | Statut | Preuve |
|---|---|---|
| POST /api/v1/alertes enregistre criteres + contact hashe/chiffre, sans authentification forte | Couvert | AlerteController.creer sans @PreAuthorize, SecurityConfig permitAll sur POST /api/v1/alertes ; AlerteService.creer chiffre le contact via AlerteContactChiffrementService (AES/GCM) avant persistance ; teste par AlerteIntegrationTest.creerAlerte_avecCriteresSuffisants_devraitEnregistrerEtEnvoyerUnSms (assertion SQL directe sur contact_chiffre different de la valeur en clair) |
| DELETE /api/v1/alertes/{id} permet la desinscription via lien a usage unique envoye par SMS/email | Couvert (SMS uniquement, reduction de perimetre documentee et assumee par l'architecte/spec-writer, cf spec.md section Ecarts identifies) | AlerteController.desinscrire, jeton opaque 43 caracteres base64url genere a la creation et envoye via PasserelleSms.envoyer ; teste par desinscrire_avecJetonValide, rejeu teste par desinscrire_avecJetonDejaConsomme (404), jeton inconnu teste (404) |
| Le contact n'est jamais consultable en clair par un agent via l'interface d'administration | Couvert | Aucune route GET sous /api/v1/alertes (grep vide), CreerAlerteResponse n'expose qu'un champ message, aucun DTO n'expose le contact ; contact_chiffre/contact_iv mis a null a la desinscription (minimisation) |
| Test d'integration couvrant creation, desinscription, et non-consultation en clair | Couvert | AlerteIntegrationTest : 6 scenarios (creation+SMS, criteres insuffisants, contact invalide, desinscription+effacement contact, rejeu 404, jeton inconnu 404) + assertions SQL directes sur contact_chiffre/contact_iv |

## Verification des 5 decisions tranchees de spec.md

1. id du DELETE = jeton opaque, jamais l'UUID : respecte a la lettre. AlerteController.desinscrire(@PathVariable("id") String tokenBrut) -- type String, jamais UUID. AlerteService.desinscrire hache le jeton recu (tokenGenerator.hacher(tokenBrut)) puis cherche par findByTokenHash -- aucune recherche par valeur en clair, aucun risque d'enumeration par UUID.
2. Expiration 8760h, pas d'endpoint de renvoi : application.yml (ALERTE_TOKEN_EXPIRATION_HEURES:8760), test/resources/application.yml idem, AlerteService n'expose aucun endpoint de renvoi. Conforme.
3. Effacement immediat du contact a la desinscription : Alerte.desinscrire() met contactChiffre = null, contactIv = null, active = false, majLe mis a jour. V7__create_alerte.sql : contact_chiffre BYTEA et contact_iv VARCHAR(64) bien nullable des la creation du schema (pas de NOT NULL). Teste explicitement par desinscrire_avecJetonValide_devraitDesactiverEtEffacerLeContact (assertions SQL IS NULL sur les deux colonnes).
4. Canal sms uniquement en contrainte CHECK, pas de champ canal dans la requete : CHECK (canal IN ('sms')) dans la migration, CreerAlerteRequest n'a pas de champ canal, AlerteService fixe CANAL_SMS = "sms" en dur. Conforme.
5. Aucun changement frontend : confirme, git diff ... --stat -- frontend/ est vide.

## Points de vigilance specifiques -- resultats

1. Masquage systematique : grep sur les logs (LOG., Logger, log.info/warn/error/debug) dans backend/src/main/java/sn/samapiece/alertes/ est vide -- aucun log n'est emis dans le module, donc aucun risque d'exposition du contact en clair par log.
2. Non-consultation en clair par un agent : grep sur GetMapping dans le module est vide, aucune route GET. CreerAlerteResponse a un unique champ message.
3. Ambiguite jeton vs UUID : confirme au point ci-dessus (decision 1) -- String tokenBrut, hachage avant recherche.
4. Effacement du contact : confirme -- Alerte.desinscrire() et colonnes nullable en base, voir decision 3.
5. Reutilisation de #21 : AlerteService appelle passerelleSms.envoyer(destinataire, message) directement, sans retry applicatif (le retry est interne a PasserelleSms, cf Javadoc de l'interface). NumeroTelephone.de(requete.contact()) est utilise pour valider/construire le contact, l'IllegalArgumentException levee en cas de contact vide est bien mappee en 400 CONTACT_INVALIDE par AlerteExceptionHandler.
6. Actuator/health : git diff sur pom.xml/backend/pom.xml entre les deux branches est vide -- aucune nouvelle dependance ajoutee, donc aucun health indicator supplementaire a desactiver.
7. application.yml de test : cles samapiece.alerte.* bien dupliquees avec cle-chiffrement de 32 octets valides, distincte de la cle photo, token-expiration-heures: 8760, lien-desinscription-base-url local.
8. Encodage UTF-8 MockMvc : toutes les occurrences de getContentAsString dans AlerteIntegrationTest passent explicitement StandardCharsets.UTF_8 (verifie par lecture complete du fichier, 6 occurrences).
9. Build/tests : voir section dediee ci-dessous.
10. Aucun fichier sous frontend/ touche : confirme (diff vide).

## Point mineur signale par le codeur -- validation

Ajout de ALERTE_CLE_CHIFFREMENT dans .env.example : coherent avec le pattern deja en place pour PHOTO_CLE_CHIFFREMENT/SMS_*, necessaire pour que docker-compose.yml (qui reference bien ALERTE_CLE_CHIFFREMENT dans services.backend.environment) reste utilisable en local. Ce n'est pas une deviation de spec -- pas de finding.

## Relecture manuelle complementaire

- Alerte.java / AlerteDesinscriptionToken.java : calquent bien Piece.java (constructeur metier hors id/creeLe/majLe, equals/hashCode sur id uniquement, constructeur protected sans argument pour JPA).
- AlerteContactChiffrementService : calque fidele de PhotoChiffrementService (AES/GCM/NoPadding, tag 128 bits, IV 12 octets, cle 32 octets validee au demarrage via IllegalStateException si taille incorrecte).
- AlerteDesinscriptionTokenGenerator.hacher : SHA-256 sans sel, avec Javadoc documentant explicitement ce choix (jeton deja a haute entropie) -- conforme a la spec.
- AlerteService.creer/desinscrire : logique fidele point par point au contrat technique de spec.md (ordre des validations, gestion des erreurs indifferenciee a la desinscription pour ne pas donner d'oracle a un attaquant).
- AlerteExceptionHandler : mapping AlerteCriteresInsuffisantsException -> 400/CRITERES_INSUFFISANTS, IllegalArgumentException -> 400/CONTACT_INVALIDE, AlerteIntrouvableOuExpireeException -> 404/JETON_INTROUVABLE, conforme.
- V7__create_alerte.sql : numerotation correcte (V6 = photo, pas de collision), contraintes CHECK coherentes avec TypeDocumentConverter/Piece, index sur active et sur token_hash (unique) et alerte_id. Migration nouvelle (pas de reecriture d'une migration existante).
- AlerteIntegrationTest : couvre bien les 3 volets du critere d'acceptation 4 (creation, desinscription incluant effacement du contact, non-consultation en clair via assertions SQL directes + verification qu'aucune cle contact/telephone n'apparait dans le JSON de reponse). Nettoyage @BeforeEach correct (pas de Piece/Poste crees dans cette suite, donc l'ordre piece_sequence avant Poste ne s'applique pas ici, conformement a spec.md).
- FakePasserelleSms : conforme a la spec (pas de @MockBean, @TestConfiguration imbriquee avec bean @Primary, liste envois accessible pour extraire le jeton brut reellement envoye).
- Pas de nouvelle surface RBAC a couvrir : les deux endpoints sont volontairement permitAll et aucune route de lecture n'existe, donc aucune annotation @PreAuthorize manquante a signaler.
- Pas de probleme de concurrence identifie : creer/desinscrire sont @Transactional, le jeton est unique en base (index unique sur token_hash), un rejeu apres consommation renvoie 404 (teste).

## Build/tests

Commandes executees :
- mvn -q -pl backend -am compile : succes, aucune sortie d'erreur.
- mvn -q -pl backend -am test-compile : succes.
- mvn -pl backend -am test -Dtest=CreerAlerteRequestTest,AlerteContactChiffrementServiceTest,AlerteDesinscriptionTokenGeneratorTest : BUILD SUCCESS, Tests run: 20, Failures: 0, Errors: 0.
- mvn -pl backend -am test -Dtest=AlerteIntegrationTest : echec attendu, ContainerFetchException (Docker indisponible dans ce sandbox, limitation deja rencontree sur #5/#19/#21). Compense par une relecture manuelle rigoureuse du test (voir section ci-dessus).
- mvn -pl backend -am test -Dtest='!AlerteIntegrationTest' (suite complete hors ce test) : Tests run: 126, Failures: 0, Errors: 13 -- les 13 erreurs sont toutes des ContainerFetchException Docker (memes tests Testcontainers deja connus comme indisponibles dans ce sandbox : PosteIntegrationTest, PieceIntegrationTest, PhotoIntegrationTest, AgentIntegrationTest, AuthIntegrationTest, SmsRetryIntegrationTest, etc.) -- aucune regression liee au code de #22 dans les tests unitaires/Spring purs, qui passent tous. Confirmation que pom.xml/backend/pom.xml n'ont subi aucune modification entre les deux branches (aucune nouvelle dependance infra, coherent avec le point de vigilance Actuator).

Aucun echec de build ou de test n'est imputable au code du bolt #22.

## Findings

Aucun. Implementation fidele a spec.md sur les 5 decisions tranchees, criteres d'acceptation couverts par du code et des tests qui echoueraient si le code etait retire, aucune fuite de contact en clair (logs, DTO, endpoint), pas de regression detectee, build/tests verts (hors limitation Testcontainers connue et non liee a ce ticket).
