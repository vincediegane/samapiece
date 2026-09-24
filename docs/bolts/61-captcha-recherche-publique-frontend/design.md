# Design — #61 CAPTCHA de la recherche publique (frontend)

## Approche

Le contrat backend (#19, déjà sur `main`) est déjà fonctionnel : `GET /api/v1/recherche-publique/captcha` renvoie `{ captchaToken, question }`, et `POST /api/v1/recherche-publique` répond `428` avec `{ code: "CAPTCHA_REQUIS", message, captchaChallengeUrl }` quand le seuil d'échecs consécutifs est atteint pour l'IP, tant que les en-têtes `X-Captcha-Token`/`X-Captcha-Reponse` valides ne sont pas fournis. Le token est à usage unique côté backend (`DefiMathematiqueCaptchaVerifier.verifier` supprime la clé Redis qu'elle soit correcte ou non) : toute réponse incorrecte ou expirée renvoie de nouveau un 428, indistinguable du premier. Le frontend doit donc gérer ce cycle comme une boucle : 428 -> récupérer un défi -> soumettre avec en-têtes -> si encore 428, considérer la réponse comme fausse/expirée, en récupérer un nouveau. On reste dans le pattern existant de RecherchePubliquePage.tsx/recherchePubliqueApi.ts (fetch direct, pas de lib HTTP, gestion d'erreur par code de statut, état local useState), sans ajouter de dépendance ni de routeur/modale : le défi s'affiche en ligne dans le formulaire existant. Prix assumé : comme le backend ne distingue pas "jamais tenté" de "réponse fausse", le message affiché au 2e échec et suivants reste volontairement générique ("réponse incorrecte ou expirée").

## Fichiers/modules impactés

Aucun fichier backend touché (contrat de #19 consommé tel quel, déjà vérifié dans backend/src/main/java/sn/samapiece/recherche/securite/ et backend/src/main/java/sn/samapiece/recherche/web/).

Fichiers frontend existants à modifier :
- frontend/src/features/recherche-publique/recherchePubliqueApi.ts — ajouter obtenirDefiCaptcha() (GET /api/v1/recherche-publique/captcha) ; faire évoluer rechercher(payload, captcha) pour poser les en-têtes X-Captcha-Token/X-Captcha-Reponse quand un captcha est fourni ; ajouter une erreur dédiée (ex. CaptchaRequisApiError, sous-classe de RecherchePubliqueApiError) levée sur statut 428, distincte du fallback générique Erreur ${status} actuel.
- frontend/src/features/recherche-publique/types.ts — ajouter un type CaptchaDefi avec captchaToken et question, miroir de CaptchaController.CaptchaDefiResponse côté backend.
- frontend/src/features/recherche-publique/RecherchePubliquePage.tsx — nouvel état pour le défi courant, la réponse saisie et le statut (aucun défi / défi affiché / échec-nouveau défi), bloc UI conditionnel (question + champ réponse + bouton Valider), et logique de nouvelle tentative qui réutilise le dernier payload soumis, sans resaisie du formulaire.
- frontend/src/features/recherche-publique/RecherchePubliquePage.test.tsx — nouveaux cas de test (à la charge du codeur/spec-writer) couvrant le parcours complet exigé par le critère d'acceptation.

## Décisions clés

- Détection du 428 par une erreur dédiée plutôt qu'un champ générique : recherchePubliqueApi.ts distingue explicitement le statut 428 (nouvelle classe d'erreur) du fallback Erreur ${status} déjà utilisé pour les autres statuts, pour que RecherchePubliquePage puisse déclencher la UI CAPTCHA sans parser un message texte.
- Le composant ne lit pas captchaChallengeUrl du corps 428 : ce champ est informatif côté backend (RecherchePubliqueExceptionHandler.CaptchaRequisReponse) mais toujours égal à /api/v1/recherche-publique/captcha en pratique ; le frontend appelle directement obtenirDefiCaptcha() avec le chemin en dur, cohérent avec BASE_URL déjà codé en dur dans recherchePubliqueApi.ts.
- Pas de distinction UI fiable entre premier défi et réponse fausse : le backend renvoie le même 428 dans les deux cas (le token Redis est supprimé après chaque vérification, correcte ou non). Le frontend garde un état local (un défi a déjà été tenté pour cette soumission) pour choisir entre un message neutre au premier 428 (verification supplementaire requise) et un message d'échec aux suivants (reponse invalide ou expiree, nouveau code), sans prétendre distinguer techniquement les deux causes possibles côté backend.
- Re-fetch automatique d'un nouveau défi à chaque 428, y compris après une réponse fausse : puisque le token est à usage unique côté backend, réafficher l'ancien token/question serait un piège UX (toute nouvelle tentative avec le même token échouerait silencieusement). Le composant appelle systématiquement obtenirDefiCaptcha() après un 428, en remplaçant le défi affiché.
- Conservation du dernier payload de recherche en état local, pour permettre de relancer rechercher() avec les en-têtes captcha sans repasser par la validation/resaisie du formulaire ; le formulaire de recherche reste désactivé tant que le défi est en attente de réponse, pour éviter une divergence entre le payload affiché et celui effectivement soumis.
- Aucun message HTTP brut affiché : tous les messages liés au captcha (défi affiché, échec, erreur réseau sur GET /captcha) passent par le même bloc erreurServeur déjà utilisé pour les autres erreurs de la page, jamais un Erreur ${status} pour 428.

## Risques / points d'attention

- Expiration du défi (captcha.ttl-defi-secondes, 120s par défaut) : un citoyen qui met du temps à répondre obtient un 428 identique à une réponse fausse ; le message générique choisi ci-dessus couvre ce cas sans fausse promesse de précision.
- Comportement fail-open du backend si Redis est indisponible : dans ce cas le captcha n'est jamais exigé (captchaRequis=false) et toute réponse est acceptée (valide=true) : le frontend n'a rien à faire de spécial, mais il ne faut pas construire de test qui suppose que le captcha est toujours déclenché après 5 échecs (dépend de Redis en environnement réel, hors du contrôle du frontend).
- Tests frontend basés sur des mocks de fetch (pas de vrai backend/Redis) : le parcours 5 echecs -> defi -> succes du critère d'acceptation doit être simulé via une séquence de réponses mockées (428 puis GET captcha 200 puis POST 200), sans reproduire réellement 5 appels, cohérent avec le pattern déjà en place dans RecherchePubliquePage.test.tsx (vi.mocked(fetch).mockResolvedValueOnce chaîné).
- Changement de signature de rechercher() (nouveau paramètre captcha optionnel) : vérifier que les appels existants sans ce paramètre restent valides et que les tests déjà présents (soumission sans captcha, 400 générique) ne sont pas affectés par l'ajout conditionnel des en-têtes.
- Minimisation des données : la réponse au défi (un entier) n'a aucune valeur personnelle, rien à journaliser/masquer de spécifique ici, contrairement au numéro de document déjà traité par la page.

## Hors périmètre

- Toute modification backend (CaptchaController, RecherchePubliqueCaptchaFilter, EchecRechercheCounterService, seuils/TTL de CaptchaProperties) : déjà livré et fonctionnel par #19.
- Un captcha visuel (image, reCAPTCHA tiers, etc.) : le défi reste la question mathématique texte déjà générée par DefiMathematiqueCaptchaVerifier, aucune UI graphique captcha à concevoir.
- Le module Alertes (#22) et toute autre fonctionnalité de RecherchePubliquePage.tsx non liée au captcha.
- L'ajout d'un routeur (react-router) ou d'une modale générique réutilisable : le défi s'affiche en ligne dans le formulaire existant.
- La gestion du rate limiting 429 (distinct du 428 captcha, traité par un autre filtre côté backend) : non mentionné dans le ticket, le fallback générique existant reste inchangé.
