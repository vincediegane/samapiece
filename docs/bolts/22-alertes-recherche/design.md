# Design -- #22 Creation et gestion d'une alerte de recherche citoyenne

## Approche

Nouveau module sn.samapiece.alertes (bounded context inexistant a ce jour, a creer en respectant les conventions deja etablies par enregistrement/recherche/notifications). Le contact du citoyen (numero de telephone) est stocke chiffre de facon reversible (AES-256/GCM, sur le meme principe que PhotoChiffrementService) car il doit pouvoir etre dechiffre cote serveur pour l'envoi du SMS de confirmation/desinscription et, plus tard, par le worker du ticket #23. Les criteres de recherche (type de document, nom/prenom, numero de document) reutilisent le meme schema que Piece (hash+sel+masque via NumeroDocumentHasher existant) pour rester coherents avec le modele de non-stockage en clair du numero de document et pour permettre au futur worker #23 de faire correspondre une Piece a une Alerte sans jamais manipuler de numero en clair. La desinscription se fait via un jeton opaque a usage unique, distinct de l'id metier de l'alerte, stocke hashe en base, avec expiration -- jamais l'id de l'Alerte lui-meme n'est utilisable pour la supprimer. Compromis assume : pas de canal email (aucune infra n'existe dans le projet) et pas de rate limiting dedie (aucun n'existe non plus sur /api/v1/recherche-publique, malgre le paragraphe 7.2/10.4 du cahier des charges) -- ces deux points sont documentes comme risques plutot que traites dans ce ticket, pour ne pas elargir le perimetre a une infra transverse (Bucket4j+Redis, passerelle email) qui merite son propre ticket.

## Fichiers/modules impactes

Module entierement nouveau -- aucun fichier existant dans ce perimetre. A creer :

Backend -- backend/src/main/java/sn/samapiece/alertes/
- package-info.java
- Alerte.java -- entite JPA (immuable hors active/majLe, GenerationType.UUID, equals/hashCode sur id, comme Piece).
- AlerteDesinscriptionToken.java -- entite JPA (id, alerteId FK, tokenHash, expireLe, consommeLe nullable, creeLe).
- AlerteRepository.java (JpaRepository<Alerte, UUID>).
- AlerteDesinscriptionTokenRepository.java (recherche par tokenHash).
- AlerteContactChiffrementService.java -- AES-256/GCM, calque sur sn.samapiece.enregistrement.photo.PhotoChiffrementService (cle distincte, voir Decisions cles).
- AlerteDesinscriptionTokenGenerator.java -- genere le jeton brut (aleatoire, SecureRandom, au moins 32 octets encodes base64url) plus son hash SHA-256 (meme schema que NumeroDocumentHasher.calculerHash, sans sel necessaire puisque le jeton est deja a haute entropie).
- AlerteService.java -- creer(CreerAlerteRequest), desinscrire(String tokenBrut) ; depend de NumeroDocumentHasher (reutilise depuis enregistrement, deja un precedent de reutilisation inter-module via recherche), AlerteContactChiffrementService, PasserelleSms, NumeroTelephone.
- AlerteCriteresInsuffisantsException.java -- dediee au module (pas de reutilisation de celle de recherche, pour garder les bounded contexts decouples).
- AlerteIntrouvableOuExpireeException.java -- jeton inconnu/expire/deja consomme.
- web/AlerteController.java -- POST /api/v1/alertes, DELETE /api/v1/alertes/{id}.
- web/CreerAlerteRequest.java, web/CreerAlerteResponse.java.
- web/AlerteExceptionHandler.java (RestControllerAdvice).

Backend -- fichiers modifies
- backend/src/main/resources/db/migration/V7__create_alerte.sql (nouveau, V6 est la derniere version utilisee).
- backend/src/main/java/sn/samapiece/config/SecurityConfig.java -- ajout de permitAll() sur POST /api/v1/alertes et DELETE /api/v1/alertes/** (pattern wildcard, a verifier avec Spring Security que le PathPattern par defaut supporte bien ** sur cette version).
- backend/src/main/resources/application.yml -- cles samapiece.alerte.cle-chiffrement et samapiece.alerte.token-expiration-heures.
- docker-compose.yml -- variable d'environnement ALERTE_CLE_CHIFFREMENT (meme schema que PHOTO_CLE_CHIFFREMENT).

Tests (nouveau repertoire backend/src/test/java/sn/samapiece/alertes/)
- Test d'integration SpringBootTest + MockMvc couvrant : creation (201, SMS envoye via un PasserelleSms mocke/fake), desinscription (204 avec jeton valide, 404 avec jeton invalide/rejoue), et non-consultation en clair (assertion directe sur la colonne SQL : contact_chiffre ne contient jamais le numero en clair, et aucun DTO de reponse ne l'expose).
- Attention (memoire projet) : utiliser getContentAsString(StandardCharsets.UTF_8) dans les assertions MockMvc, et si le test cree des donnees dans des tables avec sequences/FK (piece_sequence, etc.) dans la meme classe de test, respecter l'ordre de nettoyage existant.

Frontend : hors perimetre de ce ticket (aucun composant citoyen de creation d'alerte n'a ete identifie dans le repo pour l'instant -- a confirmer par le spec-writer si le frontend citoyen existe deja quelque part ; seul le backend Java a ete explore ici).

## Decisions cles

1. Chiffrement reversible du contact : AES-256/GCM via une cle dediee ALERTE_CLE_CHIFFREMENT (nouvel env var, pattern identique a PHOTO_CLE_CHIFFREMENT), et non une reutilisation de la cle photo. Prix : une cle/secret supplementaire a provisionner en exploitation, mais isolation du rayon d'impact en cas de compromission d'une seule cle.
2. {id} du DELETE /api/v1/alertes/{id} = jeton de desinscription opaque, pas l'id UUID de l'entite Alerte. Le ticket et le paragraphe 12 du cahier des charges nomment le parametre {id}, mais l'utiliser comme cle de suppression directe permettrait a quiconque connaissant/devinant l'UUID de desinscrire une alerte sans jamais avoir prouve la possession du contact. Le jeton est donc une valeur distincte, a forte entropie, envoyee uniquement par SMS, stockee hashee (jamais en clair) dans alerte_desinscription_token, avec expiration (valeur par defaut proposee : 72h, configurable). Point ouvert pour le spec-writer : documenter clairement ce choix dans l'OpenAPI/spec pour eviter toute confusion sur la semantique de {id}.
3. Un jeton est genere et envoye par SMS des la creation de l'alerte (satisfait le critere d'acceptation "lien a usage unique envoye par SMS" sans dependre du futur worker #23). La table alerte_desinscription_token est concue en relation un-vers-plusieurs vis-a-vis de alerte pour permettre a #23 d'emettre un nouveau jeton a chaque notification de correspondance, sans modification de schema.
4. Canal MVP = SMS uniquement. Aucune infra email n'existe dans le projet (confirme par recherche dans le code : seule mention du mot email est un commentaire dans notifications/package-info.java, aucune classe). La colonne canal accepte deja les valeurs sms et email en contrainte CHECK pour eviter une migration future, mais seul sms est implemente et valide cote service (rejet explicite si email est demande en V1, plutot que silencieux).
5. Pas de rate limiting/CAPTCHA sur POST /api/v1/alertes. Aucune infra Bucket4j/Redis/CAPTCHA n'existe deja dans le repo (verifie : absente aussi de recherche-publique, malgre le paragraphe 7.2/10.4 du cahier des charges qui la prescrit). Ajouter cette brique uniquement pour ce ticket creerait une dependance transverse hors du perimetre initial de #22. Documente comme risque ci-dessous plutot que traite.
6. Non-consultation en clair par un agent = absence totale d'endpoint de lecture. Aucune route GET ou back-office n'est creee par ce ticket pour lister/consulter les alertes. Le contact n'est donc jamais expose via aucune API -- la seule voie de dechiffrement est interne au service, au moment de l'envoi SMS, jamais renvoyee dans une reponse HTTP ni journalisee en clair (loguer uniquement via l'objet NumeroTelephone, qui masque systematiquement toString()).
7. Reutilisation de NumeroDocumentHasher et PasserelleSms telles quelles, sans reimplementer de logique de hash ou de retry -- coherent avec le contrat deja etabli par #18-#21.
8. Validation des criteres dupliquee localement dans CreerAlerteRequest (methode estSuffisant() du meme esprit que RecherchePubliqueRequest.estSuffisant()), sans extraction d'un utilitaire partage entre recherche et alertes -- coherent avec le style actuel du repo (chaque DTO porte sa propre validation), a reconsiderer seulement si un troisieme consommateur de cette regle apparait.

## Risques / points d'attention

- Absence de rate limiting/CAPTCHA : POST /api/v1/alertes est un endpoint public non authentifie qui ecrit en base et declenche un envoi SMS (donc un cout direct par operateur telecom) -- un abus (spam de creation d'alertes, flood SMS vers un tiers) est possible. Risque a traiter dans un ticket dedie (infra transverse avec recherche-publique), pas dans #22.
- Email hors perimetre : le critere d'acceptation mentionne SMS/email ; le MVP ne couvre que SMS. Si le spec-writer/PO considere l'email comme requis des #22, il faudra soit construire une infra email minimale ici, soit confirmer explicitement le report a #23/ulterieur.
- Expiration du jeton et alerte orpheline : si le jeton expire avant que le citoyen ne se desinscrive, il n'existe aujourd'hui aucun mecanisme de regeneration a la demande (pas de POST /api/v1/alertes/{id}/renvoyer-lien dans ce ticket) -- un citoyen resterait inscrit sans moyen simple de se desinscrire apres 72h. A evaluer : soit allonger fortement l'expiration (ex. pas d'expiration tant que l'alerte est active, uniquement a usage unique), soit prevoir cet endpoint. Point ouvert pour le spec-writer.
- Couplage avec #21 non merge : ce ticket depend de code present uniquement sur bolt/issue-21-passerelle-sms (PasserelleSms, NumeroTelephone). Le developpement de #22 doit rester base sur cette branche jusqu'au merge dans main.
- Minimisation des donnees : nomTitulaire/prenomTitulaire sont stockes en clair sur alerte (comme sur piece), ce qui est coherent avec l'existant mais reste une donnee personnelle conservee sans politique de purge definie dans ce ticket -- la duree de retention d'une alerte (et sa purge apres desinscription ou expiration) n'est pas specifiee par le ticket ; a documenter dans la spec ou a traiter comme dette explicite.
- Suppression physique vs desactivation logique : le design ci-dessus propose active = false (soft-delete) plutot qu'un DELETE SQL reel, pour conserver une trace d'audit minimale (coherent avec le paragraphe 7.5 tracabilite) -- mais cela signifie que le contact chiffre reste en base apres desinscription. Si la minimisation stricte est requise, il faudrait effacer contact_chiffre/contact_iv a la desinscription (garder uniquement les metadonnees non identifiantes). Point ouvert pour le spec-writer : soft-delete avec contact conserve, ou effacement immediat du contact a la desinscription.
- Frontend : aucun composant citoyen existant identifie pour la creation/desinscription d'alerte dans l'exploration effectuee ; si un tel composant existe ailleurs dans le repo (hors du perimetre backend explore ici), le spec-writer doit le signaler.

## Hors perimetre

- Canal email (voir Decisions cles 4 et Risques).
- Rate limiting / CAPTCHA anti-abus (voir Decisions cles 5 et Risques).
- Le worker de rapprochement alerte vers piece deposee (ticket #23) : ce ticket #22 ne fait qu'enregistrer/desinscrire une alerte, il ne declenche aucune notification de correspondance.
- Toute interface d'administration/back-office de consultation des alertes (statistiques, listing) -- non demandee par le ticket, et volontairement absente pour garantir trivialement le critere jamais consultable en clair.
- Regeneration/renvoi du lien de desinscription apres expiration (endpoint dedie) -- voir Risques.
- Purge/retention automatique des alertes anciennes ou expirees.
