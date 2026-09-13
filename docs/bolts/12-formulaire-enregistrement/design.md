# Design — Ticket #12 : Formulaire agent d'enregistrement (frontend)

## Approche

On ajoute un nouveau module `frontend/src/features/pieces/` calque sur le module `agents/` livre au ticket #9 (memes conventions : `fetch` direct avec jeton `localStorage`, `useState`/`useEffect` locaux, un seul composant page contenant formulaire + resultat). Le formulaire couvre exactement le contrat de `POST /api/v1/pieces` (ticket #11) : les 8 champs de `CreerPieceRequest`, pas plus (pas de photo, pas de detection de doublons, pas de generation PDF, hors contrat backend actuel). Comme le backend ne renvoie aujourd'hui aucun detail par champ sur les 400 (voir Risques), la validation client (`required`, garde-fous JS avant `fetch`) est la premiere ligne de defense pour rester sous la cible de 2 minutes et eviter les allers-retours serveur inutiles. Le prix de ce choix : une erreur 400 residuelle (contournement de la validation client, ou incoherence non couverte) ne pourra etre affichee que sous forme de message generique, pas champ par champ. Aucun routeur n'etant en place, la navigation AgentsPage / nouvelle page se fait par un composant App.tsx a onglets manuels (state React), coherent avec l'absence de react-router dans package.json.

Pour le test exige par le ticket, le repo n'a aucun outillage frontend (package.json ne contient ni vitest ni testing-library). On introduit Vitest + React Testing Library + jsdom, choix coherent avec Vite deja en place (integration native vite/vitest, pas de config webpack/jest a maintenir) et suffisant pour un test composant (remplissage + soumission + assertion sur le numero de fiche affiche), sans monter un vrai serveur E2E (Playwright/Cypress) que rien ne justifie ici.

## Fichiers/modules impactes

A creer :
- frontend/src/features/pieces/types.ts : types TypeDocument, CreerPieceRequest, PieceResponse (miroir de CreerPieceRequest.java / PieceResponse.java).
- frontend/src/features/pieces/piecesApi.ts : creerPiece(payload), calquee sur agentsApi.ts (memes conventions d'en-tete Authorization/jeton localStorage).
- frontend/src/features/pieces/EnregistrementPiecePage.tsx : formulaire + affichage du recu a l'ecran.
- frontend/src/features/pieces/EnregistrementPiecePage.test.tsx : test composant (RTL) : remplissage des champs requis, soumission, assertion sur le numero de fiche affiche ; un cas 401 (message session expiree) et un cas 400 (message generique).
- frontend/src/test/setup.ts : setup RTL (jest-dom), reference par vite.config.ts.

A modifier :
- frontend/src/app/App.tsx : actuellement `return <AgentsPage />;` uniquement ; passe a un etat local (useState) avec deux boutons/onglets pour choisir la page affichee. Pas de dependance a un routeur.
- frontend/package.json : ajout des devDependencies vitest, @testing-library/react, @testing-library/jest-dom, @testing-library/user-event, jsdom, et d'un script "test": "vitest run".
- frontend/vite.config.ts : ajout du bloc test (environment jsdom, setupFiles).
- frontend/eslint.config.js : verifier si besoin pour les fichiers *.test.tsx (on prefere importer explicitement depuis vitest plutot que d'activer des globals, pour ne pas toucher la config ESLint).

Non touches (reference uniquement) : backend/src/main/java/sn/samapiece/enregistrement/web/CreerPieceRequest.java, PieceResponse.java, PieceController.java ; frontend/src/features/agents/*.

## Decisions cles

- Un module par domaine metier (features/pieces/), pas d'ajout dans features/agents/ : suit la convention deja posee.
- TypeDocument redefini localement dans pieces/types.ts (union litterale des 8 valeurs de l'enum backend), pas de partage de types cross-module avec agents/types.ts : c'est deja le pattern existant (Role n'est pas partage non plus entre modules) ; coherent, mais voir Risques pour la derive possible.
- Pas de navigation par URL : onglets geres par useState dans App.tsx, decision imposee par l'absence de react-router (non installe, non demande par ce ticket).
- Affichage du recu = numero de fiche + recapitulatif a l'ecran, pas de PDF : conforme a la note du ticket #14 (futur) qui prendra en charge la generation PDF telechargeable. On affiche numeroFiche, typeDocument, nomTitulaire/prenomTitulaire, numeroDocumentMasque, dateDepot, statut, tous deja presents dans PieceResponse.
- Validation client exhaustive avant fetch : champs requis (typeDocument, nomTitulaire, prenomTitulaire, numeroDocument, dateDepot) bloquent la soumission cote React (pas seulement required HTML), car le 400 serveur ne donne pas de detail exploitable par champ (voir Risques). dateNaissanceTitulaire, etatDocument, remarques restent optionnels, fideles a CreerPieceRequest (nullable cote backend).
- etatDocument : String libre cote backend (pas d'enum) -> rendu comme un select ferme avec 3 options indicatives (Bon etat, Endommage, Illisible partiellement, cf. section 7.1 du PROJET-SAMAPIECE.md) mappees vers la valeur texte envoyee ; pas de contrainte serveur donc pas de risque de desync de validation.
- Gestion des erreurs par statut HTTP, pas par corps de reponse structure : piecesApi.creerPiece distingue 401 (message "Session expiree, reconnectez-vous.") de 400/autres (message generique "Verifiez les informations saisies." ou "Erreur {status}" par defaut), en s'appuyant uniquement sur le code HTTP puisque ni le corps 401 (AuthenticationEntryPoint par defaut de Spring Security, pas de JSON garanti) ni le corps 400 (pas de @ExceptionHandler(MethodArgumentNotValidException) dans le module enregistrement, server.error.include-message non configure donc "never" par defaut) ne sont exploitables de facon fiable.

## Risques / points d'attention

- 400 sans detail de champ cote backend : verifie dans PieceController / AgentAdminExceptionHandler / AuthExceptionHandler, aucun @ExceptionHandler ne couvre MethodArgumentNotValidException pour /api/v1/pieces, et application.yml ne configure ni server.error.include-message ni include-binding-errors. Un 400 reel (contournement de la validation client) affichera donc un message generique, pas "le champ X est invalide" comme le souhaiterait idealement le critere d'acceptation. Documente comme limitation connue plutot que corrige ici (modifier le backend est hors perimetre de ce ticket frontend).
- 401 sans body JSON garanti : le filtre JwtAuthenticationFilter ne pose pas d'authentification en cas de jeton expire/invalide et laisse Spring Security repondre 401 via son entry point par defaut (pas de JSON custom observe). Le frontend doit detecter le 401 uniquement sur response.status, sans tenter de lire un message dans le corps.
- Derive de types frontend/backend : TypeDocument duplique en dur dans pieces/types.ts (8 valeurs). Si l'enum backend change, rien ne le signalera cote frontend avant un echec en production, risque deja accepte implicitement par le pattern agents/types.ts existant, pas introduit par ce ticket.
- Cohabitation AgentsPage / nouvelle page sans routeur : la modification d'App.tsx change le comportement actuel (aujourd'hui AgentsPage est affichee sans condition) ; verifier qu'aucun test/usage externe ne depend du rendu direct d'AgentsPage par App.
- Minimisation des donnees : le formulaire ne doit afficher/renvoyer que ce que CreerPieceRequest accepte ; ne pas ajouter de champ "photo" ou "deposant" (prevus par la section 7.1 du PROJET-SAMAPIECE.md mais absents du contrat backend actuel et des criteres d'acceptation de ce ticket), les ajouter creerait un champ mort cote API.
- Offline-first : hors perimetre technique de ce ticket (pas de queue IndexedDB implementee), a ne pas simuler partiellement, au risque de donner une fausse impression de resilience hors ligne a l'agent.
- Outillage de test nouveau : premier package.json frontend a gagner un script test ; verifier que la CI (si elle existe) n'echoue pas faute d'executer ce script, et que l'ajout de devDependencies ne casse pas npm ci (lockfile a regenerer).

## Hors perimetre

- Generation/telechargement d'un recu PDF (ticket #14).
- Detection de doublons sur numeroDocument (mentionnee au 7.1 mais absente des criteres d'acceptation de ce ticket et du contrat backend actuel).
- Upload de photo(s) du document, floutage automatique.
- Fonctionnement hors-ligne / queue de synchronisation IndexedDB.
- Ecran de connexion / rafraichissement transparent de jeton (aucune UI de login n'existe encore dans le repo ; le 401 se traduit par un simple message invitant a se reconnecter).
- Introduction d'un routeur (react-router ou equivalent).
- Modification du backend (contrat CreerPieceRequest/PieceResponse, gestion des erreurs 400/401), signale en risque, pas corrige ici.
