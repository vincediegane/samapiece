# Design — #63 Interface retrait / signalement / déblocage d'une pièce

## Constat de départ (vérifié dans le code)

- Backend déjà livré et complet : `PieceController` (`backend/src/main/java/sn/samapiece/enregistrement/web/PieceController.java`) expose `GET /api/v1/pieces/{id}` (consulter), `POST /{id}/retrait`, `POST /{id}/signaler`, `GET /{id}/recu`, `POST /{id}/debloquer`, tous audités via `@ActionAuditee`. `PieceService`/`Piece` (règles de transition de statut) et les DTOs (`RetraitRequest`, `SignalerRequest`, `DeblocageRequest`, `PieceResponse`) existent déjà — aucune évolution backend n'est nécessaire pour ce ticket.
- Frontend : recherche confirmée dans `frontend/src` — aucune occurrence de "retrait", "signaler" ou de déblocage. `EnregistrementPiecePage.tsx` ne fait que `POST /pieces` puis affiche un panneau "Fiche enregistrée" en mémoire (état React `recu: PieceResponse`), sans bouton d'action ni lien de téléchargement du reçu.
- Confirmé également : il n'existe **aucune route/écran pour atteindre la fiche d'une pièce existante** autrement que via cet état `recu` en mémoire juste après création (pas de liste, pas de recherche — c'est l'objet du ticket #74, en Backlog). `App.tsx`/`AgentShell.tsx` n'ont que 3 onglets : `pieces` (enregistrement), `dashboard`, `agents`.
- Écart RBAC repéré : `consulter` (`GET /{id}`) est `@PreAuthorize("hasAnyRole('AGENT','CHEF_POSTE')")` alors que `debloquer` autorise en plus `ADMIN_REGIONAL`/`ADMIN_NATIONAL`. Un admin ne peut donc pas lire une fiche via cette route, alors qu'il peut la débloquer en aveugle.
- `GET /pieces/{id}/recu` répond en `Content-Disposition: inline` (pas `attachment`) et nécessite le header `Authorization: Bearer`, comme toutes les routes (cf. `piecesApi.ts::enTeteAutorisation`) — un simple `<a href=...>` ne fonctionnera pas.

## Approche

On ne construit aucune nouvelle API : uniquement l'UI React manquante branchée sur les endpoints déjà livrés. On crée un composant de "fiche pièce" (statut, actions retrait/signalement/déblocage, téléchargement du reçu) réutilisé à deux endroits : (1) le panneau de reçu existant d'`EnregistrementPiecePage`, qui a déjà l'objet `PieceResponse` (avec son `id`) en mémoire juste après création — cas d'usage principal du ticket ; (2) un nouvel écran minimal "Ouvrir une fiche" où l'agent colle l'identifiant (UUID) de la pièce et appelle `GET /pieces/{id}`. Ce deuxième point d'entrée est le strict minimum nécessaire pour qu'un chef de poste (à un autre moment, pas le créateur) puisse atteindre une pièce à débloquer, sans construire la liste/recherche complète du ticket #74. Prix de ce compromis : il faut connaître l'UUID technique (non le numéro de fiche imprimé sur le reçu), ce qui est peu ergonomique — limitation assumée et documentée plutôt que corrigée ici (voir Risques). Les actions retrait/signalement/déblocage restent strictement "en ligne" (pas de mise en file hors-ligne comme pour la création), car elles doivent refléter l'état serveur au moment de l'action.

## Fichiers/modules impactés

Frontend uniquement (aucun fichier backend à modifier) :

- **Nouveau** `frontend/src/features/pieces/FichePieceCard.tsx` — composant présentant une `PieceResponse` (badge de statut, infos fiche) et les actions disponibles (retrait, signalement, déblocage, téléchargement du reçu), gating par rôle courant + statut courant.
- **Nouveau** `frontend/src/features/pieces/RetraitForm.tsx`, `SignalerForm.tsx`, `DeblocageForm.tsx` (ou un unique composant modale paramétrable si le spec-writer préfère factoriser) — mini-formulaires appelés depuis `FichePieceCard`.
- **Nouveau** `frontend/src/features/pieces/ConsulterFichePage.tsx` — écran "Ouvrir une fiche" (saisie d'un UUID → `consulterPiece` → rend `FichePieceCard`).
- **Modifié** `frontend/src/features/pieces/piecesApi.ts` — ajout de `consulterPiece(id)`, `retirerPiece(id, payload)`, `signalerPiece(id, payload)`, `debloquerPiece(id, payload)`, `telechargerRecu(id)` (fetch + blob, puisque l'auth passe par header et que la réponse est `inline`).
- **Modifié** `frontend/src/features/pieces/types.ts` — ajout des types `RetraitRequest`, `SignalerRequest`, `DeblocageRequest`, union `StatutPiece`, libellés/couleurs de badge par statut (calqués sur le pattern `LIBELLES_ROLE`/`COULEUR_POINT_ROLE` de `AgentsPage.tsx`).
- **Modifié** `frontend/src/features/pieces/EnregistrementPiecePage.tsx` — le panneau "Fiche enregistrée" délègue à `<FichePieceCard piece={recu} onMisAJour={setRecu} />` au lieu d'afficher les champs en dur.
- **Modifié** `frontend/src/shared/layout/AgentShell.tsx` — nouvel onglet `OngletAgent` (ex. `'fiche'`, libellé "Ouvrir une fiche").
- **Modifié** `frontend/src/app/App.tsx` — câblage de la nouvelle route `ConsulterFichePage` dans `ONGLETS_AGENT`.
- **Tests** (convention du repo : un fichier de test par composant/module) : `FichePieceCard.test.tsx`, `ConsulterFichePage.test.tsx`, mise à jour de `EnregistrementPiecePage.test.tsx` et `AgentShell.test.tsx`, tests des nouvelles fonctions de `piecesApi.ts`.

## Décisions clés

1. **Deux points d'entrée vers la fiche, un seul composant d'affichage.** `FichePieceCard` est piloté par une prop `piece: PieceResponse` déjà chargée par l'appelant (post-création en mémoire, ou via `GET /{id}` depuis `ConsulterFichePage`) — pas de logique de fetch dans le composant lui-même, pour rester réutilisable et testable simplement.
2. **`ConsulterFichePage` restreint à AGENT/CHEF_POSTE**, aligné sur le `@PreAuthorize` réel de `consulter`. Conséquence assumée : ADMIN_REGIONAL/ADMIN_NATIONAL ne peuvent pas utiliser cet écran (403 sur le `GET`), donc ne peuvent pas non plus, via ce parcours, actionner le bouton "Débloquer" bien qu'ils y soient autorisés côté `POST /debloquer`. Seul CHEF_POSTE peut aujourd'hui débloquer via l'UI. Ce point est à trancher explicitement avec le spec-writer/PO (élargir le RBAC backend de `consulter` serait un changement hors périmètre "livré et fonctionnel" annoncé par le ticket).
3. **Boutons conditionnés par le statut courant**, en miroir des règles de `Piece.retirer()/signaler()/debloquer()` : retrait/signalement affichés seulement si `DISPONIBLE`/`RECLAMEE` ; déblocage affiché seulement si `RETIREE`/`ARCHIVEE`/`LITIGE`/`SIGNALEE`. Le backend reste seul juge (le 409 `TRANSITION_STATUT_INTERDITE` est géré comme filet de sécurité si l'état a changé entre-temps, ex. concurrence entre deux agents).
4. **Téléchargement du reçu via fetch+blob**, pas un simple lien `<a href>`, pour transmettre le header `Authorization` requis par toutes les routes `/api/v1/pieces/**` ; le nom de fichier suit le pattern déjà utilisé côté backend (numéro de fiche assaini).
5. **Aucune mise en file hors-ligne pour retrait/signalement/déblocage** (contrairement à `POST /pieces` qui utilise `mettreEnFile`/`fileSynchronisation.ts`) : ces actions doivent refléter l'état serveur en temps réel (éviter une double remise physique d'une pièce) ; en l'absence de réseau, l'action est bloquée avec un message explicite plutôt que mise en attente silencieuse.
6. **Formulaire de signalement** expose exactement le contrat `SignalerRequest` (`statutCible` : `LITIGE` ou `SIGNALEE`, `motif` texte libre) — pas de champ supplémentaire.

## Risques / points d'attention

- **Écart RBAC consulter vs debloquer** (voir Décision 2) — à faire trancher explicitement en spec plutôt que résolu silencieusement par un contournement frontend.
- **Pas de lookup par numéro de fiche** : le seul identifiant exploitable par l'UI est l'UUID technique (`PieceResponse.id`), jamais imprimé sur le reçu PDF (`PieceRecuPdfGenerator` n'imprime que `numeroFiche`) ni affiché aujourd'hui à l'écran. Il faudra donc probablement afficher explicitement cet UUID (ou un moyen de le copier) sur le panneau de reçu pour que `ConsulterFichePage` soit réellement utilisable en pratique — sinon le déblocage restera théorique tant que #74 n'a pas livré une vraie recherche/consultation par numéro de fiche.
- **Erreurs à gérer proprement** : 409 `TRANSITION_STATUT_INTERDITE` (statut déjà changé), 403 `AccesRefuseException` (poste/région hors périmètre — cf. `PerimetrePoste.estDansPerimetre` pour le déblocage vs simple égalité de poste pour retrait/signalement), 404 pièce introuvable, 401 session expirée — réutiliser le pattern `PieceApiError` déjà présent dans `piecesApi.ts`.
- **`PieceResponse` n'expose pas** `signalePar`/`motifSignalement`/`debloquePar`/`motifDeblocage` : seul le badge de statut est demandé par les critères d'acceptation, donc pas besoin d'enrichir le DTO backend ; à ne pas faire sans validation explicite du spec-writer (changement backend hors périmètre UI de ce ticket).
- **Minimisation des données** (§10.2 PROJET-SAMAPIECE.md) : le formulaire de retrait ne doit demander que `nomReclamant` + `pieceJustificativePresentee`, rien de plus (pas de numéro de pièce du réclamant en clair, pas de champ additionnel).
- **Offline-first** (§11.1 principe 2) : bien vérifier que le blocage réseau des actions retrait/signalement/déblocage (décision 5) est un choix assumé et visible pour l'agent (message clair), pas un échec silencieux.

## Hors périmètre

- Construire une liste ou une recherche de pièces en stock (ticket #74).
- Ajouter un endpoint de recherche par numéro de fiche, ou modifier le RBAC backend de `consulter`/`debloquer`.
- Enrichir `PieceResponse` avec l'historique complet (auteurs/motifs/dates) de signalement ou déblocage.
- Modifier le mécanisme de file de synchronisation hors-ligne existant (`shared/offline/fileSynchronisation.ts`, `db.ts`).
- Toute migration Flyway ou évolution du modèle de données (le schéma `piece`/`retrait` existant couvre déjà tous les champs nécessaires).
