# Spec — #63 Interface retrait / signalement / déblocage d'une pièce

## Résumé

Livrer les écrans React manquants (fiche pièce avec badge de statut, actions retrait/signalement/déblocage/téléchargement du reçu, et un écran d'ouverture de fiche par UUID) branchés sur les endpoints backend déjà existants de `PieceController`, sans aucune modification backend.

## Vérifications préalables faites dans le code (base pour cette spec)

- `RetraitRequest(String nomReclamant, String pieceJustificativePresentee)` — les deux `@NotBlank`.
- `SignalerRequest(StatutPiece statutCible, String motif)` — `statutCible` `@NotNull` (accepte n'importe quelle valeur de l'enum au niveau validation, mais `Piece.signaler()` rejette tout sauf `LITIGE`/`SIGNALEE` avec `IllegalArgumentException`, non catchée nulle part → 500 si mal utilisé ; le frontend doit donc **exclure toute autre valeur du choix utilisateur**), `motif` `@NotBlank`.
- `DeblocageRequest(String motif)` — `@NotBlank`.
- `PieceResponse` (backend) : `id, numeroFiche, posteId, agentCreateurId, typeDocument, nomTitulaire, prenomTitulaire, numeroDocumentMasque, dateNaissanceTitulaire, dateDepot, etatDocument, statut, remarques, creeLe, creeMalgreDoublon`. Le type TS `PieceResponse` (`frontend/src/features/pieces/types.ts`) est actuellement incomplet (il manque `creeMalgreDoublon`) — **hors périmètre de correction ici**, ne pas y toucher, `statut` (déjà présent, `string`) suffit pour ce ticket.
- `StatutPiece` (backend) : `DISPONIBLE, RECLAMEE, RETIREE, LITIGE, ARCHIVEE, DETRUITE, SIGNALEE`.
- Règles de transition (`Piece.java`) :
  - `retirer()` : autorisé seulement depuis `DISPONIBLE`/`RECLAMEE` → `RETIREE`, sinon `TransitionStatutInterditeException`.
  - `signaler(statutCible, motif, agent)` : `statutCible` doit être `LITIGE` ou `SIGNALEE` (sinon `IllegalArgumentException`, non gérée → 500) ; transition autorisée seulement depuis `DISPONIBLE`/`RECLAMEE`, sinon `TransitionStatutInterditeException`.
  - `debloquer(motif, agent)` : autorisé seulement depuis `RETIREE`/`ARCHIVEE`/`LITIGE`/`SIGNALEE` → `DISPONIBLE`, sinon `TransitionStatutInterditeException`.
- RBAC backend (`@PreAuthorize`, inchangé, confirmé dans `PieceController.java`) :
  - `GET /{id}` (consulter) : `AGENT`, `CHEF_POSTE`.
  - `POST /{id}/retrait` : `AGENT`, `CHEF_POSTE`.
  - `POST /{id}/signaler` : `AGENT`, `CHEF_POSTE`.
  - `GET /{id}/recu` : `AGENT`, `CHEF_POSTE`.
  - `POST /{id}/debloquer` : `CHEF_POSTE`, `ADMIN_REGIONAL`, `ADMIN_NATIONAL`.
- Contrôle de périmètre applicatif (`PieceService`, en plus du RBAC) : `consulter`/`retirer`/`signaler`/`genererRecu` exigent `appelant.poste.id == piece.poste.id` (égalité stricte de poste) → `AccesRefuseException` sinon ; `debloquer` utilise `PerimetrePoste.estDansPerimetre(appelant, piece.poste)` (périmètre élargi, ex. région pour un admin régional).
- Gestion d'erreurs HTTP confirmée dans le code (deux `@RestControllerAdvice` globaux, non scopés à un contrôleur, donc actifs pour `PieceController`) :
  - `TransitionStatutInterditeException` → **409**, body `{"code":"TRANSITION_STATUT_INTERDITE","message":"Transition refusee pour la piece <id> : action=<...>, statut actuel=<...>"}` (`PieceExceptionHandler`).
  - `AccesRefuseException` → **403**, body `{"code":"ACCES_REFUSE","message":"Acces refuse."}` (`AgentAdminExceptionHandler`, global).
  - `PieceIntrouvableException` → **404**, body `{"code":"PIECE_INTROUVABLE","message":"Piece introuvable."}` (`PhotoExceptionHandler`, global).
  - Échec de validation `@Valid` (`nomReclamant`/`pieceJustificativePresentee`/`motif` vides) ou UUID de path invalide → **400**, body Spring Boot par défaut (pas de `code` JSON exploitable) — non catché par un handler spécifique. Le frontend doit donc : (a) valider ces champs côté client avant envoi (empêche normalement ce cas), et (b) traiter tout 400 avec un message générique, comme le fait déjà `creerPiece` dans `piecesApi.ts`.
  - Session invalide/expirée → **401** (filtre de sécurité, en amont des contrôleurs).
  - `IllegalArgumentException` de `Piece.signaler()` sur `statutCible` invalide → **non catchée**, remonte en **500**. Le frontend ne doit donc proposer que `LITIGE`/`SIGNALEE` comme valeurs possibles (pas de saisie libre).
- `GET /{id}/recu` répond `Content-Type: application/pdf`, `Content-Disposition: inline; filename="<numeroFiche assaini>.pdf"`, corps binaire PDF. Nécessite le header `Authorization: Bearer` (comme tout `/api/v1/pieces/**`).
- Icônes disponibles dans `frontend/src/shared/icons.tsx` (à réutiliser, ne pas dupliquer) : `IconCheck`, `IconDocument`, `IconAlertTriangle`, `IconKey`, `IconSearch`, `IconChevronLeft`, etc. Pas d'icône de téléchargement existante.
- Classes CSS utilitaires déjà définies dans `frontend/src/index.css` (à réutiliser telles quelles, **ne pas modifier ce fichier**) : `btn-primary`, `btn-outline`, `btn-danger-outline`, `alert-error`, `alert-info`, `alert-success`, `field`, `field-label`, `field-input`, `field-error`, `badge-dot`, `card`, `section-title`. Couleurs de thème disponibles : `primary` (vert), `accent` (jaune/or), `danger` (rouge), `info` (bleu), `slate` (gris).
- `AgentCourant.role` (frontend, `features/dashboard/types.ts`) est un `string` brut (valeurs possibles : `AGENT`, `CHEF_POSTE`, `ADMIN_REGIONAL`, `ADMIN_NATIONAL`, `AUDITEUR`), obtenu via `recupererAgentCourant()` (`features/dashboard/dashboardApi.ts`, `GET /api/v1/agents/moi`). C'est le seul moyen déjà existant côté frontend de connaître le rôle de l'agent connecté.

## Tâches

### 1. Types et contrats frontend

- [ ] `frontend/src/features/pieces/types.ts` — ajouter :
  - `export type StatutPiece = 'DISPONIBLE' | 'RECLAMEE' | 'RETIREE' | 'LITIGE' | 'ARCHIVEE' | 'DETRUITE' | 'SIGNALEE';`
  - `export const STATUT_PIECE_LABELS: Record<StatutPiece, string>` = `{ DISPONIBLE: 'Disponible', RECLAMEE: 'Réclamée', RETIREE: 'Retirée', LITIGE: 'En litige', ARCHIVEE: 'Archivée', DETRUITE: 'Détruite', SIGNALEE: 'Signalée' }`.
  - `export const STATUT_PIECE_COULEURS: Record<StatutPiece, string>` (classe Tailwind pour `badge-dot`, pattern `COULEUR_POINT_ROLE` de `AgentsPage.tsx`) = `{ DISPONIBLE: 'bg-primary-500', RECLAMEE: 'bg-info-500', RETIREE: 'bg-slate-400', LITIGE: 'bg-danger-500', SIGNALEE: 'bg-accent-500', ARCHIVEE: 'bg-slate-600', DETRUITE: 'bg-slate-700' }`.
  - `export interface RetraitRequest { nomReclamant: string; pieceJustificativePresentee: string; }`
  - `export type StatutCibleSignalement = 'LITIGE' | 'SIGNALEE';` et `export interface SignalerRequest { statutCible: StatutCibleSignalement; motif: string; }`
  - `export interface DeblocageRequest { motif: string; }`
  - Ne pas toucher à l'interface `PieceResponse` existante (le champ `statut: string` suffit ; le caster en `StatutPiece` au point d'usage dans `FichePieceCard` via `piece.statut as StatutPiece`, avec repli visuel si la valeur est inconnue — cf. tâche 3).

### 2. Client API

- [ ] `frontend/src/features/pieces/piecesApi.ts` — ajouter, dans le style déjà utilisé par `creerPiece` (même `enTeteAutorisation()`, même classe `PieceApiError`, mêmes conventions de message d'erreur) :
  - `export async function consulterPiece(id: string): Promise<PieceResponse>` → `GET ${BASE_URL}/${id}`, headers `enTeteAutorisation()`. Mapping erreurs : 401 → `'Session expirée, reconnectez-vous.'` (401) ; 403 → `"Cette pièce n'est pas dans votre périmètre."` (403) ; 404 → `'Pièce introuvable.'` (404) ; sinon → `` `Erreur ${status}` `` (status).
  - `export async function retirerPiece(id: string, payload: RetraitRequest): Promise<PieceResponse>` → `POST ${BASE_URL}/${id}/retrait`, body JSON. Mapping erreurs : 401 → `'Session expirée, reconnectez-vous.'` ; 403 → `"Cette pièce n'est pas dans votre périmètre."` ; 404 → `'Pièce introuvable.'` ; 409 → `'Le statut de la pièce a changé entre-temps, rafraîchissez la fiche avant de réessayer.'` ; 400 → `'Vérifiez les informations saisies.'` ; sinon → `` `Erreur ${status}` ``.
  - `export async function signalerPiece(id: string, payload: SignalerRequest): Promise<PieceResponse>` → `POST ${BASE_URL}/${id}/signaler`, même mapping d'erreurs que `retirerPiece`.
  - `export async function debloquerPiece(id: string, payload: DeblocageRequest): Promise<PieceResponse>` → `POST ${BASE_URL}/${id}/debloquer`, même mapping d'erreurs que `retirerPiece`, sauf 403 → `"Cette pièce n'est pas dans votre périmètre (poste/région)."`.
  - `export async function telechargerRecu(id: string): Promise<{ blob: Blob; nomFichier: string }>` → `GET ${BASE_URL}/${id}/recu`, headers `{ Authorization: `Bearer ${jeton}` }` uniquement (pas de `Content-Type` sur un GET). Sur `!reponse.ok` : même mapping 401/403/404 que `consulterPiece` (message `'Pièce introuvable.'` etc.), sinon `` `Erreur ${status}` ``. Sur succès : lire le header `Content-Disposition` (`reponse.headers.get('Content-Disposition')`), en extraire le nom de fichier via une regex `/filename="([^"]+)"/`, repli sur `` `recu-${id}.pdf` `` si absent/non parsable ; retourner `{ blob: await reponse.blob(), nomFichier }`. **Ne pas** faire de manipulation du DOM (`<a>`, `URL.createObjectURL`) dans cette fonction — cette logique de présentation reste dans `FichePieceCard` (tâche 3) pour que `piecesApi.ts` reste pur et facilement testable côté réseau.
  - Toutes ces fonctions laissent remonter telles quelles les erreurs réseau (ex. `fetch` qui rejette avec `TypeError: Failed to fetch`) — pas de capture ici ; c'est aux appelants (formulaires/pages) de distinguer `PieceApiError` d'une panne réseau, comme le fait déjà `EnregistrementPiecePage.soumettreFormulaire`.

### 3. Composant d'affichage de fiche

- [ ] `frontend/src/features/pieces/FichePieceCard.tsx` — nouveau composant.
  - Props : `{ piece: PieceResponse; roleAgentCourant: string | null; onMisAJour: (piece: PieceResponse) => void }`.
  - Affiche dans un `<section className="card">` (ou équivalent) : numéro de fiche, type de document (via `TYPE_DOCUMENT_LABELS`), titulaire (`prenomTitulaire nomTitulaire`), `numeroDocumentMasque`, `dateDepot`, et le **badge de statut** : `<span className="badge-dot {STATUT_PIECE_COULEURS[statut] ?? 'bg-slate-400'}" /> {STATUT_PIECE_LABELS[statut] ?? piece.statut}` où `statut = piece.statut as StatutPiece`.
  - Calcule deux booléens de gating (pas de logique métier dupliquée au-delà de cette lecture directe des règles de transition ci-dessus) :
    - `peutRetirerOuSignaler = piece.statut === 'DISPONIBLE' || piece.statut === 'RECLAMEE'`.
    - `peutDebloquer = roleAgentCourant === 'CHEF_POSTE' && ['RETIREE', 'ARCHIVEE', 'LITIGE', 'SIGNALEE'].includes(piece.statut)`. **Note explicite** : `ADMIN_REGIONAL`/`ADMIN_NATIONAL` sont volontairement exclus ici bien qu'autorisés côté API — voir section Écarts identifiés. Ne pas élargir cette condition sans validation explicite.
  - État local : `formulaireActif: 'retrait' | 'signaler' | 'debloquer' | null` (un seul formulaire ouvert à la fois) et `messageConfirmation: string | null` / `erreur: string | null`.
  - Boutons d'action (visibles selon les booléens ci-dessus, `btn-outline` pour retrait/signalement, `btn-danger-outline` pour signalement si préféré, `btn-outline` pour déblocage) :
    - "Enregistrer un retrait" → `formulaireActif = 'retrait'`, affiche `<RetraitForm pieceId={piece.id} onSucces={gererSucces} onAnnuler={() => setFormulaireActif(null)} />`.
    - "Signaler cette pièce" → `formulaireActif = 'signaler'`, affiche `<SignalerForm .../>` (mêmes props).
    - "Débloquer" → `formulaireActif = 'debloquer'`, affiche `<DeblocageForm .../>` (mêmes props).
    - "Télécharger le reçu" (toujours visible, aucune condition de statut ni de rôle au-delà d'être sur l'écran) → `onClick` async : appelle `telechargerRecu(piece.id)`, puis construit une URL objet (`URL.createObjectURL(blob)`), crée un `<a>` invisible avec `href` = cette URL et `download` = `nomFichier`, l'ajoute au DOM, déclenche `.click()`, le retire, puis `URL.revokeObjectURL(...)`. Attrape les erreurs (mêmes règles que ci-dessous) et les affiche dans `erreur`.
  - `gererSucces(pieceMiseAJour: PieceResponse)` : appelle `onMisAJour(pieceMiseAJour)`, remet `formulaireActif = null`, affiche un message de confirmation bref (ex. `` `Statut mis à jour : ${STATUT_PIECE_LABELS[pieceMiseAJour.statut as StatutPiece]}.` ``) rendu via `<p className="alert-success">`.
  - Gestion d'erreur commune (affichée dans un `<p role="alert" className="alert-error">`), appliquée par chaque formulaire enfant lors du `catch` de son appel API, selon ce pattern (identique à `EnregistrementPiecePage`) :
    ```
    catch (e) {
      if (!(e instanceof PieceApiError) || !navigator.onLine) {
        setErreur('Action impossible hors connexion. Réessayez une fois la connexion rétablie.');
      } else {
        setErreur(e.message);
      }
    }
    ```
    (Aucune mise en file : conforme à la décision 5 du design — l'action est bloquée avec un message explicite, jamais mise en attente silencieuse.)

### 4. Formulaires d'action

- [ ] `frontend/src/features/pieces/RetraitForm.tsx` — nouveau composant `{ pieceId: string; onSucces: (p: PieceResponse) => void; onAnnuler: () => void }`. Deux champs texte requis : "Nom du réclamant" (`nomReclamant`), "Pièce justificative présentée" (`pieceJustificativePresentee`) — **aucun autre champ** (minimisation des données, §10.2 PROJET-SAMAPIECE.md : pas de numéro de pièce du réclamant en clair). Validation client `@NotBlank`-like (chaînes non vides après `trim()`) avant appel. Bouton "Confirmer le retrait" (`btn-primary`, `disabled` pendant l'envoi) + bouton "Annuler" (`onClick={onAnnuler}`). Appelle `retirerPiece(pieceId, { nomReclamant: nomReclamant.trim(), pieceJustificativePresentee: pieceJustificativePresentee.trim() })`, puis `onSucces(resultat)` sur succès.
- [ ] `frontend/src/features/pieces/SignalerForm.tsx` — nouveau composant, mêmes props que `RetraitForm`. Champs : sélection `statutCible` restreinte à `'LITIGE' | 'SIGNALEE'` (deux boutons radio ou un `<select>` avec exactement ces deux options, libellés "Litige" / "Signalée" — **jamais de valeur libre**, cf. risque 500 documenté plus haut) et `motif` (`<textarea>` requis, non vide après `trim()`). Bouton "Signaler la pièce" + "Annuler". Appelle `signalerPiece(pieceId, { statutCible, motif: motif.trim() })`.
- [ ] `frontend/src/features/pieces/DeblocageForm.tsx` — nouveau composant, mêmes props. Un seul champ `motif` (`<textarea>` requis, non vide après `trim()`). Bouton "Débloquer la pièce" + "Annuler". Appelle `debloquerPiece(pieceId, { motif: motif.trim() })`.
- [ ] Les trois formulaires exposent un message de validation client sous le même pattern que `EnregistrementPiecePage` (`role="alert"`, classe `field-error`) et désactivent leur bouton de soumission pendant l'appel réseau (`enEnvoi`).

### 5. Écran "Ouvrir une fiche"

- [ ] `frontend/src/features/pieces/ConsulterFichePage.tsx` — nouveau composant. Champ texte "Identifiant de la fiche (UUID)" + bouton "Ouvrir la fiche". Au clic :
  1. Validation client du format UUID (regex `/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i`) — si invalide, afficher `"Format d'identifiant invalide (UUID attendu)."` **sans appeler l'API** (évite un 400 générique Spring non exploitable, cf. constat ci-dessus).
  2. Sinon, appelle `consulterPiece(id)`. En parallèle (ou avant), récupère le rôle de l'agent courant via `recupererAgentCourant()` (`features/dashboard/dashboardApi.ts`) pour le passer à `FichePieceCard` (voir tâche 6 pour la même logique côté `EnregistrementPiecePage`).
  3. Sur succès : affiche `<FichePieceCard piece={piece} roleAgentCourant={role} onMisAJour={setPiece} />`.
  4. Sur erreur : affiche le message renvoyé par `PieceApiError` (déjà localisé par `consulterPiece`), ou le message hors-ligne si échec réseau (même pattern que la tâche 3).
  - Ajouter un texte d'aide explicite sous le champ, ex. *"Cet identifiant technique est visible sur la fiche affichée juste après l'enregistrement d'une pièce."* — documente la limitation UUID-only assumée (voir Écarts identifiés).

### 6. Intégration dans les écrans existants

- [ ] `frontend/src/features/pieces/EnregistrementPiecePage.tsx` — remplacer le contenu actuel de la `<section aria-label="Reçu d'enregistrement">` (les `<p>` en dur lignes ~265-307) par `<FichePieceCard piece={recu} roleAgentCourant={roleAgentCourant} onMisAJour={setRecu} />`, en conservant le conteneur/titre `"Fiche enregistrée"` existant. Ajouter un état local `roleAgentCourant: string | null` (initial `null`) rempli via `useEffect(() => { recupererAgentCourant().then(a => setRoleAgentCourant(a.role)).catch(() => setRoleAgentCourant(null)); }, [])` (import depuis `../dashboard/dashboardApi`). Aucune autre modification du formulaire de création.
- [ ] `frontend/src/shared/layout/AgentShell.tsx` — étendre `export type OngletAgent = 'pieces' | 'dashboard' | 'agents'` en `'pieces' | 'fiche' | 'dashboard' | 'agents'`, et ajouter un bouton de nav juste après "Enregistrement" :
  ```
  <button type="button" className={actif === 'fiche' ? 'sidebar-link-active' : 'sidebar-link'} onClick={() => onNaviguer('fiche')}>
    <IconSearch width={18} height={18} />
    Ouvrir une fiche
  </button>
  ```
  (importer `IconSearch` depuis `../icons`, déjà exporté).
- [ ] `frontend/src/app/App.tsx` — ajouter `'fiche'` dans `ONGLETS_AGENT`, importer `ConsulterFichePage` depuis `../features/pieces/ConsulterFichePage`, et ajouter `{onglet === 'fiche' && <ConsulterFichePage />}` dans le bloc `<AgentShell>`.

### 7. Icône de téléchargement (optionnel mais recommandé pour cohérence visuelle)

- [ ] `frontend/src/shared/icons.tsx` — ajouter `export function IconDownload(props: IconProps = {})` (flèche vers le bas + trait de base, même style `icone(...)` que les autres icônes du fichier), utilisée sur le bouton "Télécharger le reçu" de `FichePieceCard`. Si le codeur préfère ne pas ajouter d'icône pour rester strictement minimal, le bouton peut rester texte seul — dans ce cas, cocher cette tâche comme "non fait, décision assumée" plutôt que de la supprimer silencieusement de la spec.

### 8. Tests

- [ ] `frontend/src/features/pieces/piecesApi.test.ts` (nouveau, ou étendre un fichier existant si le codeur en trouve un — à date de cette spec aucun test de `piecesApi.ts` n'existe) : un test par fonction ajoutée (`consulterPiece`, `retirerPiece`, `signalerPiece`, `debloquerPiece`, `telechargerRecu`) couvrant le cas succès (200, payload/URL corrects, headers `Authorization` présents) et au moins les cas 401/403/404 (+ 409 pour `retirerPiece`/`signalerPiece`/`debloquerPiece`), en suivant le pattern `vi.stubGlobal('fetch', vi.fn())` de `EnregistrementPiecePage.test.tsx`.
- [ ] `frontend/src/features/pieces/RetraitForm.test.tsx`, `SignalerForm.test.tsx`, `DeblocageForm.test.tsx` (nouveaux) : rendu des champs, validation client (bouton bloqué / message si champ vide), appel de la fonction API mockée avec le bon payload, appel de `onSucces` sur 200, affichage d'erreur sur échec.
- [ ] `frontend/src/features/pieces/FichePieceCard.test.tsx` (nouveau) :
  - test paramétré (`it.each`) sur les 7 valeurs de `StatutPiece` vérifiant le libellé et la couleur de badge affichés (couvre AC5).
  - boutons retrait/signalement visibles seulement si `statut` ∈ `{DISPONIBLE, RECLAMEE}`, absents sinon (couvre AC1/AC2 partiellement, gating).
  - bouton déblocage visible seulement si `roleAgentCourant === 'CHEF_POSTE'` **et** `statut` ∈ `{RETIREE, ARCHIVEE, LITIGE, SIGNALEE}` ; absent si `roleAgentCourant` est `'AGENT'`, `'ADMIN_REGIONAL'` ou `'ADMIN_NATIONAL'` même à statut compatible (couvre AC3 + documente explicitement l'écart RBAC assumé).
  - clic sur "Télécharger le reçu" : `telechargerRecu` (mocké) appelé avec l'id de la pièce ; vérifier la création d'un lien de téléchargement (mock de `URL.createObjectURL`/`document.createElement`) (couvre AC4).
  - après succès d'un formulaire enfant (mock), `onMisAJour` est appelé avec la nouvelle `PieceResponse` et le badge affiché change en conséquence.
- [ ] `frontend/src/features/pieces/ConsulterFichePage.test.tsx` (nouveau) : UUID valide → appelle `consulterPiece` et affiche `FichePieceCard` ; UUID mal formé → message d'erreur client, `consulterPiece` **non appelé** ; 404/403/401 → messages correspondants affichés, `FichePieceCard` non rendu.
- [ ] `frontend/src/features/pieces/EnregistrementPiecePage.test.tsx` (mise à jour) : le test existant `'affiche le reçu après soumission réussie'` doit continuer à passer en vérifiant la présence du `numeroFiche` (rendu désormais par `FichePieceCard`) ; ajouter un test vérifiant que le badge de statut de `PIECE_RESPONSE_MOCK.statut` ('EN_ATTENTE' dans le mock actuel — à corriger en `'DISPONIBLE'` pour rester cohérent avec l'enum réel, cf. Écarts identifiés) s'affiche via `STATUT_PIECE_LABELS`.
- [ ] `frontend/src/shared/layout/AgentShell.test.tsx` (mise à jour) : ajouter un test vérifiant la présence du bouton "Ouvrir une fiche" et que le clic appelle `onNaviguer('fiche')`.
- [ ] `frontend/src/app/App.test.tsx` (mise à jour) : ajouter un test vérifiant que la navigation vers l'onglet `'fiche'` (via le futur bouton) rend bien `ConsulterFichePage`.
- [ ] Test manuel (pas d'automatisation E2E dans ce repo à ce jour) : parcours complet décrit dans le Plan de tests ci-dessous, section "Manuel".

## Contrat technique

### Endpoints consommés (aucun changement backend)

| Méthode | Chemin | Rôles autorisés (backend) | Payload | Réponse succès | Erreurs |
|---|---|---|---|---|---|
| GET | `/api/v1/pieces/{id}` | AGENT, CHEF_POSTE | — | 200 `PieceResponse` | 401, 403 `ACCES_REFUSE`, 404 `PIECE_INTROUVABLE` |
| POST | `/api/v1/pieces/{id}/retrait` | AGENT, CHEF_POSTE | `{ nomReclamant: string, pieceJustificativePresentee: string }` | 200 `PieceResponse` | 400, 401, 403 `ACCES_REFUSE`, 404 `PIECE_INTROUVABLE`, 409 `TRANSITION_STATUT_INTERDITE` |
| POST | `/api/v1/pieces/{id}/signaler` | AGENT, CHEF_POSTE | `{ statutCible: 'LITIGE' \| 'SIGNALEE', motif: string }` | 200 `PieceResponse` | idem + 500 si `statutCible` hors `{LITIGE,SIGNALEE}` (à ne jamais envoyer côté UI) |
| GET | `/api/v1/pieces/{id}/recu` | AGENT, CHEF_POSTE | — | 200 `application/pdf`, `Content-Disposition: inline; filename="..."` | 401, 403 `ACCES_REFUSE`, 404 `PIECE_INTROUVABLE` |
| POST | `/api/v1/pieces/{id}/debloquer` | CHEF_POSTE, ADMIN_REGIONAL, ADMIN_NATIONAL | `{ motif: string }` | 200 `PieceResponse` | 400, 401, 403 `ACCES_REFUSE`, 404 `PIECE_INTROUVABLE`, 409 `TRANSITION_STATUT_INTERDITE` |

Toutes les requêtes nécessitent `Authorization: Bearer <accessToken>` (même mécanisme que `creerPiece`, via `window.localStorage.getItem('samapiece.accessToken')`).

### Nouveaux types TS (`frontend/src/features/pieces/types.ts`)

```ts
export type StatutPiece =
  | 'DISPONIBLE' | 'RECLAMEE' | 'RETIREE' | 'LITIGE' | 'ARCHIVEE' | 'DETRUITE' | 'SIGNALEE';

export interface RetraitRequest {
  nomReclamant: string;
  pieceJustificativePresentee: string;
}

export type StatutCibleSignalement = 'LITIGE' | 'SIGNALEE';

export interface SignalerRequest {
  statutCible: StatutCibleSignalement;
  motif: string;
}

export interface DeblocageRequest {
  motif: string;
}
```

### Nouvelles fonctions (`frontend/src/features/pieces/piecesApi.ts`)

```ts
consulterPiece(id: string): Promise<PieceResponse>
retirerPiece(id: string, payload: RetraitRequest): Promise<PieceResponse>
signalerPiece(id: string, payload: SignalerRequest): Promise<PieceResponse>
debloquerPiece(id: string, payload: DeblocageRequest): Promise<PieceResponse>
telechargerRecu(id: string): Promise<{ blob: Blob; nomFichier: string }>
```

### Règle de gating UI (miroir des règles backend, filet de sécurité = 409)

| Action | Visible si `piece.statut` ∈ | Visible si rôle courant ∈ |
|---|---|---|
| Retrait | `{DISPONIBLE, RECLAMEE}` | AGENT, CHEF_POSTE (implicite : seuls rôles pouvant atteindre l'écran) |
| Signalement | `{DISPONIBLE, RECLAMEE}` | AGENT, CHEF_POSTE (idem) |
| Déblocage | `{RETIREE, ARCHIVEE, LITIGE, SIGNALEE}` | **CHEF_POSTE uniquement** (voir Écarts identifiés) |
| Téléchargement du reçu | tous | AGENT, CHEF_POSTE (idem) |

## Plan de tests

| Critère d'acceptation (ticket #63) | Test(s) |
|---|---|
| Un agent peut enregistrer un retrait (nom réclamant + pièce justificative) depuis une fiche consultée | `RetraitForm.test.tsx` (soumission → payload correct → `onSucces`) ; `FichePieceCard.test.tsx` (bouton visible/gating, mise à jour du badge après succès) ; `piecesApi.test.ts::retirerPiece` |
| Un agent peut signaler une pièce (litige/fraude) avec un motif | `SignalerForm.test.tsx` (choix restreint `LITIGE`/`SIGNALEE` + motif requis) ; `FichePieceCard.test.tsx` (gating) ; `piecesApi.test.ts::signalerPiece` |
| Un chef de poste/admin peut débloquer une pièce signalée | `DeblocageForm.test.tsx` ; `FichePieceCard.test.tsx` (bouton visible pour CHEF_POSTE, absent pour AGENT/ADMIN_* — documente l'écart) ; `piecesApi.test.ts::debloquerPiece`. **Parcours ADMIN_REGIONAL/ADMIN_NATIONAL non testable via l'UI dans ce ticket** (pas de composant à tester, cf. Écarts identifiés) — seul un test manuel via API directe (Postman/curl) reste possible et déjà couvert côté backend par les tests d'intégration existants. |
| Le reçu PDF est téléchargeable depuis l'écran de reçu existant d'`EnregistrementPiecePage` | `FichePieceCard.test.tsx` (clic déclenche `telechargerRecu` + création du lien de téléchargement) ; `piecesApi.test.ts::telechargerRecu` (parsing `Content-Disposition`, blob) ; `EnregistrementPiecePage.test.tsx` (le panneau reçu rend bien `FichePieceCard` avec le bon `piece.id`) |
| Les badges de statut reflètent DISPONIBLE/RETIREE/LITIGE/SIGNALEE/etc. | `FichePieceCard.test.tsx` (test paramétré sur les 7 valeurs de `StatutPiece`) |
| Navigation vers le nouvel écran "Ouvrir une fiche" | `AgentShell.test.tsx` (bouton + `onNaviguer('fiche')`) ; `App.test.tsx` (rendu de `ConsulterFichePage`) ; `ConsulterFichePage.test.tsx` (UUID valide/invalide, 404/403/401) |
| **Manuel** (pas d'E2E automatisé dans ce repo) : parcours complet — créer une pièce (AGENT), noter/copier son UUID depuis le panneau reçu, se reconnecter en CHEF_POSTE, ouvrir la fiche via "Ouvrir une fiche", signaler la pièce (`SIGNALEE`), vérifier le badge, débloquer, vérifier le retour à `DISPONIBLE`, puis retirer la pièce et télécharger le reçu PDF généré. | Manuel — à exécuter avant merge, en complément des tests automatisés ci-dessus. |

## Écarts identifiés

1. **RBAC déblocage vs consultation — tranché : RBAC backend inchangé.** `consulter` (`GET /{id}`) reste `AGENT`/`CHEF_POSTE` uniquement, alors que `debloquer` autorise en plus `ADMIN_REGIONAL`/`ADMIN_NATIONAL` côté API. Modifier ce `@PreAuthorize` est **hors périmètre** de ce ticket (le backend est décrit comme "livré et fonctionnel", et le ticket ne demande aucune évolution backend). En conséquence :
   - `ConsulterFichePage` reste accessible uniquement aux rôles `AGENT`/`CHEF_POSTE` (comme le RBAC réel de `consulter`) — ne pas tenter de la rendre accessible aux admins pour contourner ce point.
   - Le bouton "Débloquer" de `FichePieceCard` n'est visible que pour `roleAgentCourant === 'CHEF_POSTE'`.
   - **Résultat assumé** : `ADMIN_REGIONAL`/`ADMIN_NATIONAL` restent autorisés à débloquer une pièce via l'API (`POST /debloquer`), mais n'ont **aucun parcours UI** pour le faire dans ce ticket. Ce n'est pas un bug de cette spec, c'est une limitation connue héritée du RBAC backend existant, à documenter dans la PR plutôt qu'à corriger silencieusement (par ex. en élargissant à tort `ConsulterFichePage` aux admins, ce qui provoquerait un 403 sur leur `GET /{id}` et une UX cassée).
   - Si le produit veut réellement que les admins débloquent depuis l'UI, cela nécessite un ticket dédié pour soit élargir `@PreAuthorize` de `consulter`, soit construire un parcours de recherche/consultation propre aux admins (recoupe le ticket #74).
2. **Identifiant technique (UUID) requis pour `ConsulterFichePage`, pas de recherche par numéro de fiche.** `PieceResponse.id` (UUID) n'est actuellement affiché nulle part avant ce ticket ; il devient visible/copiable dans le panneau `FichePieceCard` rendu par `EnregistrementPiecePage` juste après création (implicitement, car `FichePieceCard` peut afficher `piece.id` si besoin — **ne pas l'omettre** : sans cet affichage, `ConsulterFichePage` est inutilisable en pratique par un chef de poste qui n'a pas créé la fiche lui-même). Le reçu PDF, lui, n'imprime que `numeroFiche` (jamais l'UUID) — aucune recherche par numéro de fiche n'existe côté backend. Cette limitation est **assumée et non corrigée dans ce ticket** ; elle dépend du ticket #74 (liste/recherche de pièces en stock) pour être résolue ergonomiquement. Ne pas construire de mécanisme de recherche par numéro de fiche ici (hors périmètre explicite du design).
3. **Mock de test existant à corriger en passant** : `EnregistrementPiecePage.test.tsx` utilise actuellement `statut: 'EN_ATTENTE'` dans `PIECE_RESPONSE_MOCK`, une valeur qui n'existe pas dans l'enum réel `StatutPiece` (`DISPONIBLE, RECLAMEE, RETIREE, LITIGE, ARCHIVEE, DETRUITE, SIGNALEE`). À corriger en `'DISPONIBLE'` lors de la mise à jour de ce fichier de test (tâche 8), pour que le test reflète un état réaliste et que le badge affiché soit cohérent avec `STATUT_PIECE_LABELS`.
4. **`IllegalArgumentException` non catchée dans `Piece.signaler()`** (statut cible invalide) remonte en 500 générique côté backend — non corrigé ici (changement backend hors périmètre), mais le frontend doit strictement empêcher l'envoi d'une valeur autre que `LITIGE`/`SIGNALEE` (cf. `SignalerForm`, tâche 4) pour ne jamais déclencher ce cas.
