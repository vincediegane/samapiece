# Design — #20 Interface web de recherche citoyenne

## Approche

On ajoute une nouvelle feature frontend autonome, `recherche-publique`, qui consomme tel quel le
contrat `POST /api/v1/recherche-publique` livre par #18 (deja sur `main`) — aucun changement
backend. On reprend fidelement les conventions posees par #12 (`fetch` direct sans lib HTTP,
gestion d'erreur par code HTTP uniquement, pas de lecture du corps sur une reponse d'erreur,
composant fonctionnel avec `useState`, test Vitest/RTL) a une difference pres : ce formulaire est
public, donc `recherchePubliqueApi.ts` n'envoie ni jeton `localStorage` ni en-tete `Authorization`.

Faute de routeur dans le projet, le nouveau formulaire est branche comme un troisieme onglet dans
`App.tsx`, exactement sur le meme patron que `pieces`/`agents` — c'est la solution la moins
couteuse compatible avec l'etat reel du depot (pas de `react-router`, pas de page de connexion, pas
de garde d'authentification existante a ce jour), meme si elle a un prix documente en Risques :
un portail public et un outil interne partagent aujourd'hui le meme bundle et la meme navigation.
Verification faite sur `main` (comparaison des branches et des fichiers presents) : le rate
limiting/CAPTCHA de #19 n'est pas encore merge sur `main`. Le formulaire ne construit donc aucune UI
CAPTCHA specifique maintenant ; il reste seulement defensif via son fallback d'erreur generique deja
existant dans le patron #12 (message generique par code de statut), qui couvrira 429/428 sans code
special le jour ou #19 sera merge.

## Fichiers/modules impactes

Aucun fichier backend n'est touche (le contrat de #18 est consomme tel quel).

Nouveaux fichiers frontend (nouvelle feature, le module recherche publique n'existe pas encore
cote frontend) :
- `frontend/src/features/recherche-publique/types.ts` — types calques sur les DTO backend :
  requete (typeDocument, nomTitulaire, prenomTitulaire optionnel, numeroDocument optionnel,
  dateNaissanceTitulaire optionnel) et reponse (trouve, typeDocument optionnel, poste optionnel avec
  nom/adresse/horaires/telephone, referenceDossier optionnel). Reutilise le type TypeDocument et les
  libelles deja definis dans `frontend/src/features/pieces/types.ts` (import croise, pas de
  duplication de la liste des 8 valeurs d'enum).
- `frontend/src/features/recherche-publique/recherchePubliqueApi.ts` — fetch direct vers
  /api/v1/recherche-publique, pas d'en-tete Authorization, gestion d'erreur par statut HTTP
  uniquement (calque sur piecesApi.ts : message specifique pour 400, message generique pour tout le
  reste, y compris un futur 429/428).
- `frontend/src/features/recherche-publique/RecherchePubliquePage.tsx` — formulaire + affichage du
  resultat + bloc "proposer une alerte" (UI seule, voir Decisions cles).
- `frontend/src/features/recherche-publique/RecherchePubliquePage.test.tsx` — tests RTL (recherche
  avec resultat, recherche sans resultat), sur le patron de
  frontend/src/features/pieces/EnregistrementPiecePage.test.tsx.

Fichier modifie :
- `frontend/src/app/App.tsx` — ajout d'un troisieme onglet ("recherche") qui rend
  RecherchePubliquePage, meme patron useState<Onglet> que l'existant.

Fichier notable non touche : `frontend/src/features/home/HomePage.tsx` existe deja mais n'est
importe nulle part (composant orphelin, jamais rendu par App.tsx/main.tsx). Il ressemble a une
ebauche de page d'accueil citoyenne jamais cablee. Ce ticket ne le reutilise pas et ne le supprime
pas : le rebranchement du point d'entree public (page d'accueil vs onglet) est un choix de
produit/routage plus large, hors perimetre ici (voir Risques).

## Decisions cles

- Integration dans l'app existante plutot que point d'entree separe : un troisieme onglet dans
  App.tsx, pas de nouveau build Vite ni de react-router. Prix assume : en l'etat, rien n'empeche un
  agent d'utiliser l'onglet public depuis la meme session, et rien ne separe le deploiement
  public/interne — acceptable car aucune authentification ne protege deja les onglets pieces/agents
  aujourd'hui (pas de page de connexion dans le frontend a ce jour) ; introduire un vrai
  routeur/deploiement separe est un chantier plus large, pas demande par ce ticket.
- Aucune donnee sensible cote client : le composant n'affiche jamais rien au-dela de ce que renvoie
  deja la reponse backend (type de document, poste, reference de dossier) — conforme au paragraphe
  7.2 du document produit, deja garanti par #18 cote backend ; le frontend n'a donc pas de logique de
  masquage a implementer, seulement a ne rien inventer/ajouter.
- Validation client miroir de la regle backend estSuffisant() : typeDocument et nomTitulaire
  obligatoires, et au moins un de numeroDocument/dateNaissanceTitulaire. Validation faite avant
  l'appel reseau (evite un aller-retour inutile sur mobile/connexion lente) ; le 400
  CRITERES_INSUFFISANTS backend reste le filet de securite final, mais son corps JSON n'est pas
  parse (message generique fixe pour le 400, coherent avec le choix deja fait dans piecesApi.ts de
  ne jamais lire le corps d'une reponse d'erreur).

- 429/428 non geres specifiquement : puisque le code de #19 n'est pas sur main, aucune UI CAPTCHA
  (pas de champ pour la reponse CAPTCHA, pas d'appel a l'endpoint CAPTCHA) n'est construite
  maintenant. Le fallback generique par code de statut suffit a ne pas planter si ces codes
  apparaissent un jour, mais un futur ticket devra ajouter le vrai flux CAPTCHA/retry une fois #19
  merge — explicitement note en Hors perimetre.
- Proposer une alerte = UI seule, non branchee : quand trouve vaut false, affichage d'un message et
  d'un bouton du type "Recevoir une alerte si cette piece est deposee" qui, au clic, affiche un texte
  du type "Cette fonctionnalite arrive bientot" (etat local, pas d'appel API). Aucune tentative
  d'appeler le futur endpoint alertes, qui n'existe pas (backend prevu par #22). Le libelle du bouton
  est choisi pour rester stable quand #22 le rendra reellement fonctionnel (branchement ulterieur
  sans reecrire le formulaire de recherche).
- Pas de nouvelle dependance npm : reutilisation de HTML semantique brut + index.css existant, pas
  de librairie de formulaire/date/UI, pas d'image dans cette page — coherent avec le budget de poids
  de page du paragraphe 11.7 et avec l'absence totale de dependance CSS/JS ajoutee par #12.

## Risques / points d'attention

- Melange portail public / outil interne dans le meme bundle et la meme navigation : c'est le
  compromis assume ci-dessus ; si une authentification frontend (page de connexion, garde de route)
  est introduite par un ticket futur, il faudra explicitement exclure l'onglet recherche de cette
  garde — a signaler au moment ou ce chantier demarrera pour ne pas rendre le portail public
  accidentellement prive.
- HomePage.tsx orphelin : sa presence peut laisser croire qu'un point d'entree citoyen distinct est
  deja prevu ; ce n'est pas le cas (jamais importe). Ne pas le confondre avec le nouveau
  RecherchePubliquePage pendant la revue.
- Absence de rate limiting/CAPTCHA en production tant que #19 n'est pas merge : l'endpoint reste
  ouvert au scraping/force brute cote backend ; ce n'est pas un defaut de ce ticket frontend (hors
  perimetre backend) mais un point a rappeler pour ne pas deployer #20 seul trop longtemps en
  production sans #19.
- i18n wolof (paragraphe 7.2) : le document produit mentionne les libelles cles en wolof ; aucune
  infrastructure i18n n'existe dans le frontend actuel (aucun fichier de traduction, aucune lib
  detectee). Ce ticket livre uniquement en francais — a documenter comme ecart connu, pas a corriger
  ici (chantier i18n plus large, non cadre).
- Degradation gracieuse des images / mode texte allege : cette page n'affiche aucune image, la
  recommandation du paragraphe 11.7 ne s'applique donc pas litteralement ici ; le point d'attention
  reel pour ce ticket est le poids du bundle JS (ne pas ajouter de dependance lourde) plutot que des
  images a degrader.
- Double emploi de TypeDocument : le type et ses libelles sont importes depuis
  features/pieces/types.ts. Si un futur refactor deplace ce type vers un module partage, ce ticket
  cree une dependance croisee entre deux features qu'il faudra corriger a cette occasion — non
  bloquant maintenant vu la taille du projet.

## Hors perimetre

- Toute modification backend (contrat #18, rate limiting/CAPTCHA #19).
- Le vrai flux CAPTCHA cote frontend (en-tetes de jeton/reponse CAPTCHA, appel a l'endpoint CAPTCHA
  de #19) — a faire dans un ticket ulterieur une fois #19 merge sur main.
- Le module Alertes fonctionnel (creation/suppression d'alerte, ticket #22) — seul un bouton
  d'intention non branche est livre ici.
- Toute infrastructure de routage (react-router), de page de connexion ou de garde
  d'authentification frontend.
- Le canal USSD (paragraphe 11.7, phase 2/3).
- L'internationalisation (wolof) mentionnee au paragraphe 7.2.
- Le rebranchement ou la suppression de HomePage.tsx.
