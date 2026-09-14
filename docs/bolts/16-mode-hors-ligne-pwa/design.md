# Design — Ticket #16 : Mode hors-ligne (PWA offline-first) pour le formulaire agent

## Approche

On rend le frontend installable via `vite-plugin-pwa` (Workbox) pour le précache de l'app shell (JS/CSS/HTML/icônes) uniquement — aucune réponse d'API n'est mise en cache HTTP, car la vraie donnée hors-ligne (une fiche créée sans réseau) est un objet métier structuré, pas une réponse HTTP à rejouer telle quelle. On ajoute donc, en parallèle du service worker, une file de synchronisation applicative : les fiches créées hors-ligne sont écrites dans IndexedDB (via `idb`) avec un statut, puis un module JS (piloté par l'app, pas par le service worker) les rejoue vers `POST /api/v1/pieces` au retour de connexion, avec compteur de tentatives et backoff. Ce choix — synchronisation pilotée par le code applicatif plutôt que par la Background Sync API de Workbox — sacrifie la capacité de synchroniser en tâche de fond quand l'onglet est fermé (Background Sync n'est de toute façon pas supporté sur Safari/iOS, donc peu fiable comme solution unique), mais donne un contrôle total sur la logique métier du 409 doublon (qui nécessite d'inspecter le corps de la réponse) et sur l'affichage de la file, ce que Workbox ne permet pas nativement. Le formulaire existant (`EnregistrementPiecePage.tsx`) n'a aucune capture photo aujourd'hui ; ce ticket ne couvre donc que la mise en file d'un payload JSON, pas de pièce jointe binaire.

## Constat important — dépendance non résolue avec le ticket #15

Le ticket décrit le champ `confirmerMalgreDoublon` et le 409 `DOUBLON_POTENTIEL` comme déjà en place suite au ticket #15. Ce n'est pas le cas sur cette branche : `bolt/issue-16-mode-hors-ligne-pwa` a été créée à partir de `main`, qui ne contient pas encore ces commits (ils existent uniquement sur `bolt/issue-15-detection-doublons`, non mergée). Vérifié par `git merge-base --is-ancestor` (négatif) et par grep sur le backend (`confirmerMalgreDoublon`, `DOUBLON_POTENTIEL` : aucune occurrence sur cette branche). Forme exacte côté branche #15, pour référence du spec-writer :

```java
// backend/src/main/java/sn/samapiece/enregistrement/web/CreerPieceRequest.java
public record CreerPieceRequest(
    @NotNull TypeDocument typeDocument, @NotBlank String nomTitulaire,
    @NotBlank String prenomTitulaire, @NotBlank String numeroDocument,
    LocalDate dateNaissanceTitulaire, @NotNull LocalDate dateDepot,
    String etatDocument, String remarques, boolean confirmerMalgreDoublon) {}

// PieceExceptionHandler : 409 body = { code: "DOUBLON_POTENTIEL", message, numerosFicheCandidats: string[] }
// PieceResponse expose en plus : creeMalgreDoublon: boolean
```

Décision : concevoir la file locale et la logique de retry pour qu'elles fonctionnent dès aujourd'hui (sans `confirmerMalgreDoublon` côté frontend, sans lire de corps 409 typé), en distinguant les statuts uniquement par code HTTP (401, 400, 409, réseau/5xx). Le traitement fin du 409 (lecture de `numerosFicheCandidats`, proposition de `confirmerMalgreDoublon`) sera un raffinement à activer quand #15 sera mergée — voir « Points ouverts ». Le spec-writer doit signaler ce blocage : soit ce ticket attend le merge de #15, soit il livre une version dégradée du traitement 409 (traité comme conflit terminal générique, sans détail des candidats).

## Fichiers/modules impactés

Tout est frontend ; aucun de ces fichiers/répertoires n'existe encore sauf mention contraire.

- `frontend/package.json` — ajout dépendances : `vite-plugin-pwa` (dev, MIT), `idb` (prod, ISC, wrapper Promise autour d'IndexedDB), `fake-indexeddb` (dev, MIT, polyfill IndexedDB pour Vitest/jsdom).
- `frontend/vite.config.ts` (existant, à modifier) — ajout du plugin `VitePWA` (stratégie `generateSW`, `registerType: 'autoUpdate'`, `navigateFallback: '/index.html'`, pas de `runtimeCaching` sur `/api/**`).
- `frontend/index.html` (existant, à modifier) — meta `theme-color`, lien `apple-touch-icon` si nécessaire (le plugin injecte le lien manifest automatiquement).
- `frontend/public/` (existant, ne contient que `vite.svg`) — ajout d'icônes PWA réelles (192x192, 512x512, `maskable` si possible) : n'existent pas aujourd'hui, à fournir (placeholder acceptable pour ce ticket, cf. Risques).
- `frontend/src/vite-env.d.ts` (existant, à modifier) — ajout `/// <reference types="vite-plugin-pwa/client" />`.
- `frontend/src/shared/offline/db.ts` (nouveau) — ouverture de la base IndexedDB via `idb` (`openDB`), un seul object store `fiches-en-attente` (keyPath `id`).
- `frontend/src/shared/offline/types.ts` (nouveau) — type `FicheEnAttente` (voir Décisions clés).
- `frontend/src/shared/offline/fileSynchronisation.ts` (nouveau) — API : `mettreEnFile(payload)`, `listerFile()`, `synchroniser()` (parcourt la file, POST vers l'API, applique les transitions de statut/backoff), déclencheurs (`online`, minuteur de secours, appel manuel).
- `frontend/src/shared/offline/fileSynchronisation.test.ts` (nouveau) — tests Vitest + `fake-indexeddb`, couvrant mise en file, succès, échec réseau + retry/backoff, 409 -> statut terminal dédié, épuisement des tentatives.
- `frontend/src/features/pieces/EnregistrementPiecePage.tsx` (existant, à modifier) — en cas d'échec réseau (ou `!navigator.onLine`) à la soumission, appeler `mettreEnFile` au lieu de propager l'erreur ; afficher la file d'attente (nouveau sous-composant) sous le formulaire.
- `frontend/src/features/pieces/FileAttenteSynchronisation.tsx` (nouveau) — liste des fiches en attente avec statut, bouton « Réessayer maintenant » par item et global.
- `frontend/src/features/pieces/piecesApi.ts` (existant, à modifier) — `PieceApiError` doit exposer le statut HTTP (déjà le cas) ; distinguer explicitement l'échec réseau (exception `TypeError` de `fetch`, pas de `Response`) d'une erreur HTTP, car ce sont deux branches différentes du moteur de retry.
- `frontend/src/main.tsx` (existant, à modifier) — enregistrement du service worker (`virtual:pwa-register`), et déclenchement d'une synchronisation au chargement + sur l'événement `online`.
- `docs/bolts/16-mode-hors-ligne-pwa/test-manuel-coupure-reseau.md` (nouveau) — procédure de test manuel (DevTools -> Network -> Offline), livrable du critère d'acceptation 4.

## Décisions clés

1. **Bibliothèque IndexedDB : `idb`** plutôt qu'API native. Compromis : une dépendance de plus (environ 1 Ko gzip, licence ISC, très largement utilisée, pas de risque de maintenance) contre un code natif verbeux (callbacks/événements) et sujet à erreur pour un besoin aussi simple qu'un store unique avec CRUD. Alternative « natif sans dépendance » rejetée : pas de bénéfice réel ici, juste plus de code à maintenir.
2. **Modèle de la file locale** (`FicheEnAttente`) :
   ```ts
   interface FicheEnAttente {
     id: string;                 // uuid v4 local, crypto.randomUUID()
     payload: CreerPieceRequest; // même contrat que l'API (voir note #15 ci-dessus)
     statut: 'en_attente' | 'en_cours' | 'echec_reseau' | 'conflit_doublon' | 'echec_definitif' | 'synchronise';
     tentatives: number;
     creeLeLocal: string;        // ISO 8601, horloge du poste client
     derniereErreur: string | null;
     numeroFicheServeur: string | null; // renseigné une fois synchronisé
   }
   ```
   Le champ `payload` est le JSON exact déjà produit par `EnregistrementPiecePage` (pas de transformation), pour garantir la compatibilité avec l'API sans dupliquer la logique de mapping formulaire -> requête.
3. **Pas de cache HTTP des réponses d'API dans Workbox.** Seuls les assets statiques (JS/CSS/HTML/icônes) sont précachés. La donnée métier offline vit exclusivement en IndexedDB, gérée par l'app — évite la confusion entre « cache technique » et « données en attente », et évite de servir des données périmées (ex. liste de postes) comme si elles étaient fraîches.
4. **Synchronisation pilotée par l'app, pas par Workbox Background Sync.** Déclencheurs : (a) chargement de l'app, (b) événement `window.addEventListener('online', ...)`, (c) minuteur de secours (ex. toutes les 30s tant que l'app est ouverte et que la file n'est pas vide) car l'événement `online` n'est pas fiable à 100% (ex. connecté à un réseau local sans sortie internet réelle), (d) bouton manuel « Réessayer ». Un verrou en mémoire (`enCours`) empêche les envois concurrents du même item si plusieurs déclencheurs se chevauchent.
5. **Backoff et plafond de tentatives**, inspiré du principe de dégradation progressive déjà en place côté SMS (`SmsRabbitConfig` : 3 paliers 30s/2m/10m puis dead-letter) mais adapté au contexte client (pas de RabbitMQ) : paliers 15s -> 1min -> 5min, puis passage en `echec_definitif` après 3 échecs réseau consécutifs — statut qui nécessite une action manuelle (bouton « Réessayer ») plutôt qu'un retry automatique infini. Le retour de l'événement `online` réinitialise l'attente de backoff immédiatement (signal plus fort qu'un simple minuteur écoulé).
6. **Le 409 (conflit doublon) n'est jamais retenté automatiquement.** Dès qu'une tentative de synchronisation reçoit un 409, l'item passe au statut terminal dédié `conflit_doublon` (distinct de `echec_definitif`), sorti de la boucle de retry automatique. Sans le champ `confirmerMalgreDoublon` disponible sur cette branche (cf. dépendance #15 ci-dessus), l'agent ne peut pour l'instant que constater le conflit dans la file — pas de mécanisme de « forcer la création » proposé par ce ticket avec les fichiers actuels du backend.
7. **Erreur 401 pendant la synchronisation** = terminal immédiat, pas de retry (le jeton est probablement expiré et aucun call réseau ne peut le rafraîchir sans reconnexion) — statut regroupé sous `echec_definitif` avec message explicite invitant à se reconnecter.
8. **Emplacement du code : `frontend/src/shared/offline/`** plutôt que `frontend/src/features/pieces/offline/`. Le moteur de file/sync est générique (n'importe quel futur formulaire pourrait vouloir la même mécanique) même si, pour ce ticket, seul le payload `CreerPieceRequest` est concerné. Le composant d'affichage (`FileAttenteSynchronisation.tsx`), lui, reste dans `features/pieces/` car son contenu (libellés, aperçu de fiche) est spécifique au domaine pièce.
9. **`fake-indexeddb` importé localement dans le fichier de test offline**, pas dans `src/test/setup.ts` global, pour ne pas polluer les autres suites de tests avec un `indexedDB` global persistant entre fichiers ; réinitialisation de la base via `indexedDB.deleteDatabase(...)` dans un `beforeEach`.
10. **Stratégie Workbox : `generateSW`** (pas `injectManifest`) — suffisant car aucune logique de service worker personnalisée (type Background Sync) n'est nécessaire avec l'architecture retenue au point 4.

## Risques / points d'attention

- **Fiabilité de la détection online/offline** : `navigator.onLine`/l'événement `online` peut donner un faux positif (connecté à un réseau sans accès internet réel) — d'où le minuteur de secours (décision 4), mais cela reste une heuristique, pas une garantie ; un test manuel doit couvrir ce cas (voir livrable test manuel).
- **Photo/pièce jointe binaire — vérifié hors périmètre.** `EnregistrementPiecePage.tsx` actuel ne capture aucune photo (le module backend `sn.samapiece.enregistrement.photo` existe côté API mais n'a pas d'UI frontend). Ce design ne couvre donc que du JSON en IndexedDB. Si un ticket futur ajoute la capture photo à ce formulaire, le modèle de file devra être revu : stocker un `Blob`/`File` en IndexedDB change significativement les contraintes (quota de stockage du navigateur, taille par item, sérialisation) ; IndexedDB gère nativement les `Blob` mais pas de façon uniforme sur tous les navigateurs/versions — à ne pas anticiper maintenant, juste documenter le risque.
- **Données personnelles en clair dans IndexedDB.** `CreerPieceRequest` contient nom/prénom/numéro de document en clair (le hachage `numeroDocument` est fait côté backend, pas avant envoi). Le stockage local n'est pas chiffré (IndexedDB n'offre pas de chiffrement natif) : sur un poste partagé ou un appareil volé, une fiche en attente de sync est lisible. Le §10.4 exige un chiffrement au repos, actuellement uniquement mis en oeuvre côté serveur (DB + photos) — ce risque doit être signalé explicitement au spec-writer/product owner : accepté tel quel pour ce pilote (cohérent avec le fait qu'aucun chiffrement client n'existe ailleurs dans l'app), ou nécessite un chiffrement applicatif léger de la file locale (complexité supplémentaire, clé à gérer côté client — problème non trivial, risque de sur-ingénierie pour ce ticket).
- **Jeton d'authentification expiré pendant une longue coupure réseau** : la fiche reste en `echec_definitif` (statut 401) jusqu'à reconnexion de l'agent ; pas de mécanisme de refresh offline (`POST /api/v1/auth/refresh` nécessite aussi le réseau). À documenter dans le test manuel.
- **Dépendance non résolue avec #15** (détaillée plus haut) — impacte directement le traitement du 409 et doit être arbitrée avant que le spec-writer fige les critères d'acceptation détaillés.
- **Icônes PWA manquantes** : `frontend/public/` ne contient que `vite.svg` ; des icônes 192/512 réelles sont nécessaires pour une installation propre (écran d'accueil) — placeholder minimal acceptable pour ce ticket, branding définitif hors périmètre.
- **`tsc -b` en build** : `vite-plugin-pwa` génère des fichiers dans `dist/` (sw.js, manifest) au build, pas de conflit attendu avec `tsc -b && vite build` existant, mais à vérifier une fois la config posée (pas vérifié dans cette exploration, aucune config PWA n'existe encore pour comparer).
- **Duplication en cas de synchronisation concurrente** (deux onglets ouverts sur la même app) : IndexedDB est partagée par origine, donc les deux onglets verraient la même file ; le verrou (décision 4) est en mémoire par onglet, pas cross-onglet — un doublon d'envoi entre deux onglets ouverts simultanément reste possible. Non traité par ce ticket (cas rare en usage terrain agent mono-poste), à documenter comme limite connue.

## Hors périmètre

- Traitement fin du 409 doublon avec proposition de `confirmerMalgreDoublon` à l'agent (numéros de fiche candidats affichés, choix « confirmer malgré tout ») — bloqué par la dépendance au ticket #15, non livré ici en l'état des fichiers de cette branche.
- Capture/compression de photo hors-ligne — le formulaire actuel n'a pas de capture photo ; rien à faire ici.
- Synchronisation en arrière-plan quand l'onglet/l'app est complètement fermé (Background Sync API) — support navigateur trop inégal (absent sur Safari/iOS), écarté au profit d'une sync pilotée par l'app ouverte.
- Chiffrement applicatif de la file IndexedDB — signalé comme risque, pas construit dans ce ticket.
- Résolution de conflits multi-onglets (verrou cross-onglet via `BroadcastChannel`/`navigator.locks`) — limite connue, non traitée.
- Toute UI de gestion/écran dédié de la file en dehors de la page d'enregistrement de pièce (pas de nouvel onglet dans `App.tsx`) — la file s'affiche directement sous le formulaire existant.

## Points ouverts pour le spec-writer

1. Faut-il attendre le merge de la branche `bolt/issue-15-detection-doublons` avant de spécifier le comportement 409, ou spécifier une version dégradée (conflit générique, sans détail des candidats) pour ce ticket, à raffiner plus tard ?
2. Nombre exact de tentatives/paliers de backoff avant `echec_definitif` (proposé ici : 3 tentatives, paliers 15s/1min/5min) — à valider ou ajuster.
3. Faut-il garder les fiches au statut `synchronise` visibles quelques instants dans la liste (confirmation visuelle) avant suppression d'IndexedDB, ou les retirer immédiatement de la file dès succès ?
4. Faut-il un chiffrement léger de la file locale (cf. risque données personnelles en clair) pour ce pilote, ou l'accepter comme dette documentée ?
