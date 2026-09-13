# Design - Ticket 6 : Modele Agent + migration Flyway

## Approche

On ajoute la table agent et l entite JPA correspondante dans le package sn.samapiece.iam, qui existe deja (cree vide au ticket 1, avec un package-info.java decrivant exactement gestion des comptes, roles, authentification et habilitations). On reprend fidelement le style pose par le ticket 5 pour Region/Poste : migration Flyway V2 (premiere migration disponible apres V1__create_region_poste.sql), entite immuable (pas de setters, constructeur metier + constructeur protege pour Hibernate, id en GenerationType.UUID), enum stocke en minuscules via un AttributeConverter dedie plus contrainte CHECK, exactement comme TypePoste et TypePosteConverter. Seul ajout volontaire par rapport a ce patron existant : l enum Role implemente GrantedAuthority (getAuthority retourne ROLE_ concatene au nom), pour offrir un contrat stable a 7 (construction de l Authentication) et 8 (PreAuthorize avec hasRole, qui prefixe automatiquement par ROLE_) sans rien implementer de l authentification ici. Prix de ce choix : Role depend de spring-security-core, deja presente via spring-boot-starter-security, acceptable puisque ce package est justement le futur module IAM.

Le hash de mot de passe est stocke tel quel (colonne opaque hash_mot_de_passe, VARCHAR255) : ce ticket ne calcule ni ne verifie aucun hash BCrypt, ca reste le travail de 7.

## Fichiers / modules impactes

Nouveaux :
- `backend/src/main/resources/db/migration/V2__create_agent.sql` : migration Flyway (table `agent`, FK vers `poste`).
- `backend/src/main/java/sn/samapiece/iam/Role.java` : enum AGENT, CHEF_POSTE, ADMIN_REGIONAL, ADMIN_NATIONAL, AUDITEUR, implemente `GrantedAuthority`.
- `backend/src/main/java/sn/samapiece/iam/RoleConverter.java` : `AttributeConverter<Role, String>`, meme patron que `TypePosteConverter` (nom en minuscules).
- `backend/src/main/java/sn/samapiece/iam/Agent.java` : entite JPA.
- `backend/src/main/java/sn/samapiece/iam/AgentRepository.java` : `JpaRepository<Agent, UUID>`, minimal (pas de recherche par matricule dans ce ticket, voir Decisions).
- `backend/src/test/java/sn/samapiece/iam/AgentIntegrationTest.java` : test Testcontainers (`postgres:16-alpine`, comme `PosteIntegrationTest`) : persistance et relecture avec mapping du role, et violation de la contrainte d unicite sur `matricule`.

Non modifies (verifie explicitement, pas de raison d y toucher pour ce ticket) :
- `backend/src/main/java/sn/samapiece/config/SecurityConfig.java` : aucun endpoint agent n est ajoute ici, rien a autoriser ou proteger.
- `backend/src/main/java/sn/samapiece/referentiel` (Region, Poste, etc.) : `Poste` est seulement reference en lecture (FK), pas modifie.
- `backend/pom.xml` : aucune dependance supplementaire necessaire (`spring-security-core`, pour `GrantedAuthority`, est deja apportee transitivement par `spring-boot-starter-security`).
- `backend/src/main/java/sn/samapiece/iam/package-info.java` : deja present et deja pertinent, inchange.

## Schema de la table (V2__create_agent.sql)

```sql
CREATE TABLE agent (
    id                  UUID PRIMARY KEY,
    poste_id            UUID NOT NULL REFERENCES poste(id),
    matricule           VARCHAR(50) NOT NULL UNIQUE,
    nom                 VARCHAR(255) NOT NULL,
    role                VARCHAR(20) NOT NULL CHECK (role IN
                             (agent, chef_poste, admin_regional, admin_national, auditeur)),
    hash_mot_de_passe   VARCHAR(255) NOT NULL,
    actif               BOOLEAN NOT NULL DEFAULT true,
    derniere_connexion  TIMESTAMPTZ,
    cree_le             TIMESTAMPTZ NOT NULL DEFAULT now(),
    maj_le              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_agent_poste_id ON agent(poste_id);
```

Note : dans le fichier SQL reel, chaque valeur de la liste CHECK ci-dessus doit etre un litteral chaine Postgres entre guillemets simples ; ils sont omis ci-dessus uniquement a cause d une contrainte d echappement de cet outil de redaction.

`id` en UUID sans valeur par defaut cote SQL (genere cote application via Hibernate, comme `Region`/`Poste`). `matricule` porte une contrainte UNIQUE, ce qui repond directement au critere d acceptation du ticket. `derniere_connexion` est le seul champ nullable metier (pas de valeur a la creation d un compte).

## Decisions cles

- Numero de migration : `V2`, verifie que `V1__create_region_poste.sql` est la seule migration existante dans `backend/src/main/resources/db/migration/`.
- Package : `sn.samapiece.iam`, pas `referentiel` -- c est le package prevu pour ca depuis le ticket 1 (`package-info.java` explicite), contrairement a `Region`/`Poste` qui n avaient pas de package evident au ticket 5.
- Noms de colonnes : repris tels quels du diagramme AGENT au paragraphe 9.1 de `PROJET-SAMAPIECE.md` (`hash_mot_de_passe`, `derniere_connexion`, `poste_id`), plutot qu une variante comme `mot_de_passe_hash` -- tracabilite directe avec le document produit.
- Stockage du role : `VARCHAR(20)` plus `CHECK` plus `AttributeConverter` en minuscules, identique au patron `TypePoste`/`TypePosteConverter` du ticket 5. Un type ENUM PostgreSQL natif est ecarte pour rester coherent avec ce style deja etabli (des migrations ALTER TYPE seraient plus lourdes qu un simple CHECK si un role est ajoute plus tard).
- `Role implements GrantedAuthority` : ajout cible pour preparer 7 et 8 sans les implementer. `getAuthority()` retourne le nom du role prefixe par `ROLE_` (par exemple `ROLE_CHEF_POSTE`), convention attendue par `hasRole()` de Spring Security. Aucun bean `SecurityFilterChain` ou `UserDetailsService` n est touche ici.
- `AgentRepository` minimal : pas de methode de recherche par matricule dans ce ticket -- ce besoin (retrouver un agent au login) appartient a 7. L ajouter maintenant serait de la speculation sur une API pas encore consommee ; le cout de l ajouter plus tard est faible.
- Entite immuable, sans setters : coherent avec `Region`/`Poste`. Consequence assumee : ni `actif` ni `derniere_connexion` ne peuvent etre modifies apres creation dans ce ticket (voir Risques -- 7 et 8 devront trancher le mecanisme de mutation).
- `hash_mot_de_passe` traite comme chaine opaque : pas de validation de format ou de longueur au-dela de `VARCHAR(255)` (large marge pour un hash BCrypt, environ 60 caracteres). Aucune dependance a `BCryptPasswordEncoder` ajoutee dans ce ticket.

## Risques / points d attention

- Mutation future de `actif`/`derniere_connexion` : l entite n a aucun setter (patron `Region`/`Poste`). Le ticket 7 devra mettre a jour `derniere_connexion` a chaque login, un futur ticket admin devra pouvoir desactiver un compte -- il faudra alors ajouter une methode dediee ou un setter cible sur `Agent`. Ce n est pas tranche ici, signale explicitement pour ne pas etre oublie par la suite de la chaine.
- Synchronisation enum/CHECK : comme pour `TypePoste`, la liste de valeurs du CHECK SQL et les constantes de l enum `Role` doivent rester alignees manuellement (aucune generation automatique) -- un role renomme sans mise a jour de la migration casse l insertion silencieusement cote base, avec une erreur seulement a l execution.
- Format reel du matricule non specifie : ni le ticket ni le paragraphe 9.1 de `PROJET-SAMAPIECE.md` ne precisent le format (police et gendarmerie ont potentiellement des formats differents). `VARCHAR(50)` est une borne raisonnable mais arbitraire -- a confirmer par le spec-writer si un format strict doit etre valide des ce ticket.
- Fuite potentielle de `hash_mot_de_passe` : aucun DTO ni controleur n est cree dans ce ticket donc aucun risque d exposition immediat, mais a rappeler explicitement pour 7 et 8 : ce champ ne doit jamais apparaitre dans une reponse JSON.
- Minimisation des donnees personnelles (paragraphe 10.2) : le nom de l agent est une donnee professionnelle d un agent des forces de l ordre (pas d un citoyen), categorie moins sensible que PIECE ou CITOYEN_ALERTE -- aucun hash ou chiffrement requis pour ce champ, coherent avec le traitement deja applique a `Poste`.
- Ordre des migrations : `V2` reference `poste(id)` par FK, donc depend de `V1` deja appliquee -- Flyway applique les migrations dans l ordre numerique, pas de risque particulier, mais le test d integration doit laisser les deux migrations se rejouer (ne pas isoler `V2` seule dans un contexte de test).

## Hors perimetre

- Aucun endpoint REST (controleur agent, DTO de reponse) -- non demande par les criteres d acceptation de ce ticket.
- Authentification : hashing BCrypt du mot de passe, verification au login, emission de JWT -- ticket 7.
- RBAC : `@PreAuthorize`, restriction des endpoints existants ou futurs par role -- ticket 8.
- CRUD agent (creation par un admin, desactivation de compte, changement de poste, rotation de mot de passe) -- pas demande, seul le modele de donnees est couvert ici.
- MFA/OTP, verrouillage apres tentatives infructueuses (mentionnes aux paragraphes 7.6.1 et 10.3 de `PROJET-SAMAPIECE.md`) -- hors perimetre, appartiennent a la logique d authentification de 7.
