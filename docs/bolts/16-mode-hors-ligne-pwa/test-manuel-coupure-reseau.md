# Test manuel — Mode hors-ligne (PWA) du formulaire agent

Ce document décrit la procédure de test manuel du ticket #16. Le service worker n'est actif
qu'en build de production : toute vérification doit passer par `npm run build && npm run preview`,
jamais par `npm run dev`.

## Prérequis

1. Dans `frontend/` : `npm run build && npm run preview`.
2. Navigateur Chrome ou Edge récent, avec les DevTools.
3. Un compte agent valide (jeton stocké dans `localStorage` sous la clé `samapiece.accessToken`)
   permettant de se connecter au poste et d'accéder au formulaire d'enregistrement de pièce.

## Scénario 1 — Installation PWA

1. Ouvrir l'URL affichée par `npm run preview`.
2. DevTools > Application > Manifest : vérifier que le manifest se charge sans erreur (nom
   « SamaPièce », `display: standalone`, icônes 192x192 / 512x512 / 512x512 maskable visibles).
3. DevTools > Application > Service Workers : vérifier qu'un service worker est enregistré et au
   statut « activated and is running ».
4. Vérifier la présence d'une icône/bouton d'installation dans la barre d'adresse du navigateur
   (ou dans le menu « Installer SamaPièce… »).

## Scénario 2 — Création d'une fiche hors-ligne

1. DevTools > Network > passer le mode réseau sur « Offline ».
2. Remplir le formulaire d'enregistrement de pièce (tous les champs requis) et soumettre.
3. Vérifier l'affichage du message informatif (pas de `role="alert"` d'erreur) :
   « Pas de connexion : la fiche a été enregistrée localement, elle sera synchronisée
   automatiquement. » et que le formulaire est réinitialisé.
4. DevTools > Application > IndexedDB > `samapiece-offline` > `fiches-en-attente` : vérifier la
   présence de l'enregistrement avec `statut: "en_attente"`, `tentatives: 0`.
5. Vérifier que la fiche apparaît dans la section « File d'attente de synchronisation » sous le
   formulaire avec le libellé « En attente de connexion ».

## Scénario 3 — Synchronisation automatique au retour de connexion

1. Repasser le mode réseau sur « Online ».
2. Vérifier que la fiche passe automatiquement par « Envoi en cours… » puis disparaît de la file
   après un bref affichage (~5 s) du statut « Synchronisé — numéro de fiche : … ».
3. Vérifier côté backend (ou via l'écran de recherche publique de pièces) que la fiche existe bien
   avec le numéro de fiche affiché.

## Scénario 4 — Échec réseau intermittent avec retry

1. Passer en « Offline », soumettre une fiche.
2. Repasser brièvement en « Online » puis revenir en « Offline » avant l'écoulement du premier
   palier de backoff (15 s), de façon à laisser la tentative automatique échouer à nouveau.
3. Inspecter l'enregistrement IndexedDB correspondant : vérifier la progression du champ
   `tentatives` et la mise à jour de `prochaineTentativeAuPlusTotLe` à chaque échec.
4. Laisser suffisamment de temps (plusieurs minutes, coupure réseau maintenue) pour observer le
   passage en `echec_definitif` après la 4ᵉ tentative, et vérifier que le bouton
   « Réessayer maintenant » apparaît alors pour cet item.

## Scénario 5 — Conflit doublon (409)

Ce scénario nécessite de provoquer un 409 côté backend. Sur cette branche, sans le ticket #15
(détection de doublons) mergé, le backend ne renvoie pas nécessairement de 409 applicatif sur un
doublon exact — dans ce cas, documenter le scénario comme « à rejouer une fois #15 mergée ».

En alternative, le comportement frontend peut être vérifié isolément via DevTools > Network en
interceptant la requête `POST /api/v1/pieces` et en forçant une réponse `409` (bloc de script
d'interception, ou throttling/override de requête selon les capacités du navigateur) :

1. Soumettre une fiche hors-ligne, revenir en ligne pour déclencher la synchronisation, avec la
   requête interceptée pour renvoyer un statut 409.
2. Vérifier que l'item affiche le libellé « Conflit détecté (doublon potentiel) — nécessite une
   vérification manuelle au poste. ».
3. Vérifier qu'aucun bouton « Réessayer maintenant » n'apparaît pour cet item, et qu'aucune
   nouvelle tentative automatique n'est effectuée par la suite.

## Scénario 6 — Session expirée (401) pendant une synchronisation en attente

1. Avec une fiche en attente dans la file (statut `en_attente` ou `echec_reseau`), supprimer ou
   corrompre la valeur de `samapiece.accessToken` dans `localStorage`.
2. Forcer une synchronisation (bouton « Réessayer tout maintenant », ou attendre le minuteur de
   secours de 30 s).
3. Vérifier le passage immédiat de l'item en `echec_definitif` avec le message
   « Session expirée : reconnectez-vous puis cliquez sur Réessayer. ».

## Limites connues (dette documentée, hors périmètre de ce ticket)

- **Faux positifs online/offline** : `navigator.onLine`/l'événement `online` peuvent indiquer une
  connexion active alors que l'accès internet réel est indisponible (réseau local sans sortie
  internet). Le minuteur de secours (30 s) atténue ce risque sans le supprimer complètement.
- **Pas de synchronisation en tâche de fond** : si l'onglet ou l'application est complètement
  fermé, aucune synchronisation n'a lieu tant qu'il n'est pas rouvert (pas d'utilisation de la
  Background Sync API, pour compatibilité multi-navigateurs).
- **Données en clair dans IndexedDB** : la file locale (`fiches-en-attente`, y compris nom,
  prénom, numéro de document) n'est pas chiffrée. Sur un poste partagé ou un appareil volé, une
  fiche en attente de synchronisation reste lisible. Dette assumée pour ce pilote, cohérente avec
  l'absence de chiffrement client ailleurs dans l'application.
- **Pas de résolution de conflit multi-onglets** : le verrou anti-concurrence est en mémoire, par
  onglet. Deux onglets ouverts simultanément sur la même session peuvent tenter d'envoyer le même
  item en parallèle.
