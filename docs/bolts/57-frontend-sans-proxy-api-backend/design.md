# Design - #57 Frontend sans proxy vers l'API backend

## Approche

Tous les appels fetch du frontend utilisent deja des chemins relatifs (/api/v1/pieces,
/api/v1/agents, /api/v1/recherche-publique, cf. frontend/src/features/*/*.ts) - il n'y
a donc pas besoin d'introduire une URL absolue ni de VITE_API_BASE_URL : il suffit de faire
en sorte que /api/* soit route vers le backend a la fois par nginx (prod/docker) et par le
serveur de dev Vite (local). C'est la solution la plus simple et coherente avec le code
existant, et elle evite d'avoir a configurer CORS cote backend (aucune config CORS actuelle
dans SecurityConfig, cf. backend/src/main/java/sn/samapiece/config/SecurityConfig.java) :
en passant par un proxy, le navigateur voit une seule origine (le frontend), donc pas de
preflight CORS a gerer. Le prix a payer : le port interne du backend (SERVER_PORT, pilote
par .env) doit etre connu de nginx au demarrage du conteneur frontend, ce qui impose de
passer d'un nginx.conf statique a un template resolu via envsubst (mecanisme natif de
l'image nginx:alpine) plutot que de coder en dur backend:8080.

## Fichiers/modules impactes

- frontend/nginx.conf renomme en frontend/nginx.conf.template (ajout d'un bloc
  location /api/ avec proxy_pass vers http://backend:SERVER_PORT/api/, resolu par
  envsubst au demarrage du conteneur).
- frontend/Dockerfile : la ligne COPY nginx.conf /etc/nginx/conf.d/default.conf devient
  COPY nginx.conf.template /etc/nginx/templates/default.conf.template (mecanisme d'entree
  standard de l'image nginx:alpine, qui applique envsubst sur tout fichier de
  /etc/nginx/templates/*.template vers /etc/nginx/conf.d/ avant de demarrer nginx -
  aucun script d'entrypoint custom a ecrire).
- docker-compose.yml : service frontend - ajout de environment: SERVER_PORT: ${SERVER_PORT}
  (meme variable que celle deja passee au service backend, pas de nouvelle variable
  d'environnement a documenter dans .env.example) et d'un depends_on: backend avec
  condition: service_healthy (actuellement absent - le service frontend n'a aucun depends_on).
- frontend/vite.config.ts : ajout de server.proxy pour /api en dev, vers
  http://localhost:8080 (port par defaut du backend en local, cf. backend/README.md),
  avec changeOrigin: true.
- README.md (racine) : mise a jour du tableau des URLs et ajout d'une commande curl
  documentee de verification (curl -i http://localhost:8081/api/v1/postes doit renvoyer
  Content-Type: application/json, pas text/html).
- frontend/README.md : mention que npm run dev necessite le backend demarre sur
  localhost:8080 (via docker-compose up backend ou ./mvnw spring-boot:run) pour que les
  appels /api/* aboutissent, avec la meme commande curl de verification adaptee au port
  5173.
- Aucun fichier backend ni aucun fichier de test unitaire existant n'est modifie.

## Decisions cles

- Pas de VITE_API_BASE_URL : les appels fetch existants utilisent des chemins relatifs ;
  introduire une base URL absolue casserait cette convention et forcerait a gerer CORS cote
  backend pour rien. Le proxy (nginx en prod, Vite en dev) est la solution la plus proche du
  comportement deja ecrit dans le code.
- Template nginx via envsubst plutot que valeur codee en dur : SERVER_PORT est deja
  une variable pilotee par .env et consommee par le service backend - un nginx.conf
  statique avec backend:8080 en dur romprait silencieusement si SERVER_PORT est change
  dans .env sans toucher au frontend. Le mecanisme /etc/nginx/templates/*.template de
  l'image officielle nginx:alpine couvre ce besoin sans script custom. Le proxy_pass
  reecrit vers le contexte /api/ du backend (les controleurs sont deja tous mappes sous
  /api/v1/..., donc un simple proxy_pass sans reecriture de prefixe suffit).
- depends_on backend (service_healthy) ajoute au service frontend : actuellement
  absent de docker-compose.yml. Sans lui, nginx peut demarrer avant que le hostname Docker
  backend soit resolvable de facon stable, provoquant des 502 intermittents au premier
  docker-compose up. Le cout est un demarrage frontend legerement plus lent (attend jusqu'a
  ~30s de start_period du healthcheck backend), juge acceptable.
- Verification par curl documentee dans les README plutot qu'un test automatise :
  aucun framework e2e/integration n'existe dans le repo (les tests frontend mockent
  systematiquement fetch, cf. RecherchePubliquePage.test.tsx) ; ecrire un vrai test qui
  demarre nginx/Vite + backend depasserait le perimetre de ce ticket d'infra. La commande
  curl -i .../api/v1/postes sert de critere de verification reproductible et rapide, en
  s'appuyant sur GET /api/v1/postes, endpoint public existant
  (PosteController, permitAll() dans SecurityConfig).
- Endpoint de verification = /api/v1/postes (celui cite dans le ticket) : public,
  sans JWT requis, donc testable par un simple curl sans jeton - coherent avec le fait que
  le proxy ne doit rien changer a l'auth, seulement au routage.

## Risques / points d'attention

- Le renommage nginx.conf en nginx.conf.template et le deplacement vers
  /etc/nginx/templates/ change le mecanisme de generation de la conf nginx : bien verifier
  que l'image nginx:alpine utilisee (FROM nginx:alpine AS runtime) inclut bien le script
  d'entree 20-envsubst-on-templates.sh (present sur toutes les versions recentes de l'image
  officielle, mais a confirmer si jamais la version epinglee changeait).
- envsubst substitue toutes les variables trouvees dans le template, y compris celles
  qui font partie de la syntaxe nginx elle-meme (variables internes comme $uri). Il faut
  restreindre envsubst a la liste explicite des variables voulues (ex. la commande
  envsubst avec l'argument '$SERVER_PORT' uniquement, via la variable d'environnement
  NGINX_ENVSUBST_FILTER si disponible dans la version d'image utilisee) pour ne pas
  corrompre la ligne try_files $uri /index.html;.
- L'ajout de depends_on backend (service_healthy) ralentit le demarrage du frontend en
  dev docker-compose ; a mentionner dans le README pour ne pas surprendre.
- Le champ workbox.navigateFallbackDenylist avec le motif ^/api/ (deja present dans
  vite.config.ts) exclut correctement /api/* du fallback SPA du service worker - bon
  signe que l'intention "ne jamais servir index.html pour /api/*" etait deja la cote
  PWA offline-first, seul le routage reseau manquait. Ne pas toucher a cette config PWA.
- Le proxy /api/ doit etre place avant (ou distinct de) la location / avec
  try_files $uri /index.html;, sinon nginx continuera de servir index.html pour les
  routes API (c'est exactement le bug actuel).
- Verifie qu'aucun autre appel reseau frontend (websocket, SSE) n'existe hors du
  perimetre /api/* - recherche effectuee sur frontend/src/features/* : seuls les 3
  fichiers *Api.ts cites font des fetch, tous sous /api/v1/....

## Hors perimetre

- Configuration CORS cote backend (SecurityConfig) : non necessaire, le proxy elimine le
  besoin de CORS cross-origin.
- Ajout d'un test automatise (Vitest/Playwright) qui demarre reellement nginx ou le dev
  server Vite : hors perimetre, remplace par une verification curl documentee.
- Modification des endpoints backend, de l'authentification JWT ou du modele de donnees.
- Introduction d'une variable VITE_API_BASE_URL ou de tout mecanisme de base URL absolue.
- Gestion HTTPS/TLS du reverse proxy nginx (hors perimetre de ce ticket, qui porte
  uniquement sur le routage /api).
- Tout changement du comportement PWA/offline existant (vite-plugin-pwa, workbox) au-dela
  de la verification que la configuration actuelle est deja correcte.
