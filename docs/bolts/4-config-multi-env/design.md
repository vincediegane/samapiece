# Design - Ticket #4 : Configuration multi-environnements (dev/staging/prod)

Etat constate du repo (verifie via Read/Glob/Grep, branche bolt/issue-4-config-multi-env empilee sur #1/#2/#3, git log confirme que ces fichiers datent du commit cd27786 du ticket #1) : les trois criteres d'acceptation du ticket sont deja tres majoritairement satisfaits par le scaffolding livre au ticket #1 :

- backend/src/main/resources/application.yml (commun) + application-dev.yml, application-staging.yml, application-prod.yml existent deja, avec spring.profiles.active: SPRING_PROFILES_ACTIVE (defaut dev) dans le commun.
- Aucune valeur sensible en clair : DB_USER/DB_PASSWORD sont des variables d'environnement sans valeur par defaut dans les trois profils (y compris dev) ; seuls DB_HOST/DB_PORT/DB_NAME ont des defauts non sensibles en dev (localhost/5432/samapiece_dev), absents en staging/prod. Aucune cle JWT n'existe nulle part dans le repo (module iam encore un simple package-info.java, spring-security-test en dependance mais aucune classe de config Spring Security) - donc rien a corriger de ce cote, juste rien a creer prematurement.
- backend/README.md contient deja une section "Profils disponibles" documentant dev/staging/prod et la commande pour basculer (SPRING_PROFILES_ACTIVE=staging mvn -pl backend spring-boot:run).
- .env.example (racine, ticket #3) porte deja SPRING_PROFILES_ACTIVE=dev et les variables DB_* exactement alignees avec les noms attendus par les fichiers application-*.yml ; docker-compose.yml les propage au conteneur backend.

Le seul ecart reel avec les criteres d'acceptation : le README.md racine ne mentionne nulle part le multi-environnements ni le renvoi vers backend/README.md - seul backend/README.md en parle. Le ticket #1 avait explicitement tranche que "comment lancer/tester localement" (dont la bascule de profil fait partie) revient aux README de module, mais le ticket #4 redemande une doc "dans README.md" sans preciser lequel, et le README racine reste aujourd'hui muet sur le sujet alors qu'il documente deja docker-compose (qui, lui, pilote SPRING_PROFILES_ACTIVE).

## Approche

Ne pas re-livrer ce qui existe deja (cela recreerait un diff inutile et risquerait de regresser le commentaire pedagogique deja present dans application.yml/application-dev.yml sur l'exclusion DataSourceAutoConfiguration). Le perimetre reel de ce ticket se reduit a : (1) auditer/valider que les fichiers existants respectent bien les deux premiers criteres (fait ci-dessus, aucune correction de fond necessaire), et (2) combler le seul vrai manque - une doc courte dans le README.md racine qui renvoie explicitement a la section "Profils disponibles" de backend/README.md et rappelle comment SPRING_PROFILES_ACTIVE est pilote via .env/docker-compose. Cout : quasi nul, pas de risque de regression sur la configuration Spring existante. Le prix de cette approche minimaliste est qu'elle ne "cloture" pas visuellement le ticket avec un gros diff - le spec-writer doit l'assumer explicitement plutot que de chercher a justifier un travail plus large qui n'a pas lieu d'etre.

## Fichiers / modules impactes

- README.md (racine) - seul fichier a modifier. Ajouter un court paragraphe (dans la section "Stack" ou juste apres la section Docker Compose) : liste des trois profils (dev/staging/prod), rappel que SPRING_PROFILES_ACTIVE est lu depuis l'environnement (.env en local/Docker Compose, variable d'environnement reelle en staging/prod), et lien vers la section "Profils disponibles" de backend/README.md pour le detail (commande mvn spring-boot:run avec profil explicite, liste des variables requises).
- Aucun fichier Java, application*.yml, .env.example ou docker-compose.yml a creer/modifier - ils satisfont deja les criteres d'acceptation 1 et 2 (verifie par lecture directe, cf. ci-dessus).
- backend/README.md : pas de modification necessaire (section deja conforme), sauf si le spec-writer identifie une reformulation utile pour eviter la duplication avec le nouveau paragraphe racine - a trancher au cadrage, mais ce n'est pas un critere d'acceptation.

## Decisions cles

- Ne pas dupliquer la documentation de detail : le README racine renvoie vers backend/README.md plutot que de recopier la liste des variables - une seule source de verite, coherent avec la convention deja posee au ticket #1 ("les README de module restent focalises sur comment lancer/tester localement").
- Aucune modification des fichiers application-*.yml : ils respectent deja "aucune valeur sensible en clair" (pas de defaut pour DB_USER/DB_PASSWORD, y compris en dev) et la structure commun (application.yml) vs specifique par profil (application-{profil}.yml) demandee par le ticket. Toucher a ces fichiers sans besoin fonctionnel nouveau serait un risque gratuit (le commentaire pedagogique sur l'exclusion datasource, pose au ticket #1, est fragile a reformuler sans le casser).
- Pas de cle JWT a introduire : le module iam n'a aucune classe de securite aujourd'hui (juste package-info.java), donc il n'y a litteralement aucune valeur JWT a sortir du code - en ajouter une maintenant (ex. jwt.secret via une variable d'environnement factice) creerait une configuration orpheline, non consommee, qui anticiperait a tort sur un futur ticket IAM. Le critere "aucune cle JWT en clair" est donc satisfait par absence, pas par une variable preparee a l'avance.
- Portee de la doc : "courte", conformement au ticket - quelques lignes dans le README racine, pas une nouvelle section longue redondante avec backend/README.md.

## Risques / points d'attention

- Risque principal pour le spec-writer/codeur suivant : ne pas re-generer application-dev.yml/application-staging.yml/application-prod.yml/backend/README.md en pensant "creer" ce qui existe deja - un tel diff casserait potentiellement le commentaire explicatif sur l'exclusion DataSourceAutoConfiguration (pose au ticket #1) ou introduirait une incoherence de nommage de variable avec .env.example/docker-compose.yml (ticket #3). Le spec-writer doit citer les fichiers existants tels quels dans sa spec plutot que de redecrire une cible "a creer".
- Coherence avec .env.example/docker-compose.yml : deja verifiee - noms de variables identiques (DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASSWORD, SERVER_PORT, SPRING_PROFILES_ACTIVE). Toute variable ajoutee par erreur dans le nouveau paragraphe README qui ne correspondrait pas exactement a ces noms creerait une confusion pour le developpeur.
- Le datasource reste inactif (spring.autoconfigure.exclude, ticket #1) : la doc ajoutee ne doit pas laisser entendre que basculer de profil change le comportement reel de connexion DB aujourd'hui - seuls logging.level et l'URL/identifiants declares (mais non utilises tant que l'exclusion est active) different entre dev/staging/prod actuellement. Formuler ce point sans ambiguite pour ne pas induire en erreur un futur lecteur du README.
- Aucun test automatise ne couvre les profils staging/prod (seul dev est exerce implicitement par mvn -pl backend test/CI) - ce n'est pas un critere d'acceptation du ticket, donc pas d'action requise, mais bon a noter si un reviewer s'attend a voir un test par profil.

## Hors perimetre

- Reactivation de DataSourceAutoConfiguration/HibernateJpaAutoConfiguration ou ajout de migrations Flyway (couvert par un ticket futur dedie, deja signale par le TODO dans application.yml).
- Toute configuration Spring Security reelle (JWT, RBAC) - le module iam reste un scaffold vide ; ce ticket ne doit pas y toucher.
- Modification de .env.example, docker-compose.yml, des Dockerfile ou de la CI GitHub Actions - tous deja coherents avec les profils existants (tickets #2/#3), aucun changement requis par ce ticket.
- Deploiement reel des environnements staging/prod (infrastructure, secrets manager, CI/CD de deploiement - section 11.9 du document produit) : ce ticket couvre uniquement la configuration applicative Spring et sa documentation, pas l'infrastructure de deploiement elle-meme.
