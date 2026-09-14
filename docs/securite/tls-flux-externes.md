# TLS sur les flux externes

**Statut actuel : Non applicable aujourd'hui.** Aucun reverse proxy/terminaison TLS n'est configuré dans ce repo (`frontend/nginx.conf` sert du HTTP nu, usage développement uniquement). Ce document répond au critère d'acceptation 3 du ticket #27 sous forme de checklist à exécuter au moment du déploiement réel, pas d'une conformité déjà acquise.

## 1. Flux externes concernés

- Portail public web (citoyen) vers l'API SamaPiece.
- Application agent vers l'API SamaPiece.
- L'API SamaPiece elle-même, exposée publiquement (entrée du trafic).
- Appel sortant de l'API SamaPiece vers l'API SMS externe (`samapiece.sms.api-endpoint`).
- Tout futur webhook entrant en provenance du fournisseur SMS (accusés de réception, statuts de livraison) — non implémenté à ce jour, mais à couvrir dès son introduction.

## 2. Exigence

- TLS 1.2 minimum, TLS 1.3 recommandé.
- Certificats valides émis par une autorité reconnue (Let's Encrypt ou certificat fourni par le fournisseur cloud/CDN retenu).
- Redirection HTTP → HTTPS obligatoire (aucun flux applicatif ne doit rester accessible en clair une fois l'infra réelle en place).
- En-tête `Strict-Transport-Security` (HSTS) recommandé sur les réponses HTTP du portail public et de l'API.

## 3. Checklist à exécuter/vérifier au moment du déploiement réel

| Flux | Propriétaire | Méthode de vérification |
|---|---|---|
| [ ] Portail public web | DevOps | `curl -Iv https://<host-portail>` (confirmer réponse 2xx/3xx sur HTTPS, absence d'erreur de certificat) ; scan SSL Labs (`https://www.ssllabs.com/ssltest/`) pour la note globale et la liste des protocoles/ciphers actifs. |
| [ ] Application agent → API | DevOps | `openssl s_client -connect <host-api>:443 -tls1_2` (confirmer une négociation réussie en TLS 1.2 au minimum) et vérification que la configuration client mobile/web de l'app agent pointe bien vers une URL `https://`. |
| [ ] API SamaPiece (entrée) | DevOps | `curl -Iv https://<host-api>/actuator/health` ; vérifier la redirection HTTP→HTTPS avec `curl -Iv http://<host-api>` (doit répondre par un `301`/`308` vers `https://`, jamais un `200` en clair). |
| [ ] Appel sortant vers l'API SMS | DevOps / Intégrateur SMS | Vérifier la valeur de `samapiece.sms.api-endpoint` en staging/prod (voir section 5) ; `openssl s_client -connect <host-sms>:443 -tls1_2` côté fournisseur SMS. |
| [ ] Futur webhook entrant SMS | DevOps (au moment de son introduction) | Même méthode que pour l'API SamaPiece (entrée), appliquée à l'URL de webhook exposée. |

## 4. Cas particulier de l'API SMS

`samapiece.sms.api-endpoint` vaut `http://127.0.0.1:1` uniquement dans `backend/src/test/resources/application.yml` (profil test) : cette valeur cible volontairement un port fermé sur la boucle locale, pour que les tests d'intégration échouent rapidement sur un appel réseau réel sans jamais atteindre un vrai service externe. C'est un choix délibéré pour les tests, pas une régression à corriger.

**Avant toute mise en service staging/prod**, vérifier explicitement que la valeur configurée pour `SMS_API_ENDPOINT` (variable d'environnement consommée par `application.yml`/`application-prod.yml`/`application-staging.yml`) est bien en `https://` et pointe vers le vrai endpoint du fournisseur SMS retenu — jamais une valeur `http://` en dehors du profil test.

## 5. Hors périmètre

- Aucun test automatisé n'est possible ici sans infrastructure réelle (pas de reverse proxy/certificat à tester dans ce repo à ce jour) : les vérifications de ce document sont volontairement manuelles, à exécuter au moment du déploiement réel.
- La configuration d'un reverse proxy (nginx en mode terminaison TLS, ou équivalent côté cloud/CDN) n'existe pas dans ce repo et n'est pas créée par ce ticket.

Pour le garde-fou continu sur la gestion des secrets applicatifs (dont `SMS_API_KEY`), voir `docs/securite/gestion-secrets-et-revue-historique.md`.
