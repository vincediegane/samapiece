# Chiffrement au repos (PostgreSQL / MinIO)

**Statut actuel : Non applicable aujourd'hui.** Aucun environnement staging/prod n'est provisionné dans ce repo (seul `docker-compose.yml` existe, pour le développement local). Ce document répond au critère d'acceptation 1 du ticket #27 sous forme de procédure à exécuter au moment du provisioning réel, pas d'une conformité déjà acquise.

## 1. Répartition des responsabilités

Le chiffrement au repos (chiffrement des données stockées sur disque, au niveau du volume/du système de fichiers) est une responsabilité de la **plateforme d'hébergement**, pas de l'application SamaPiece elle-même :

- L'application (backend Spring Boot) ne gère, ne configure et ne vérifie aucun chiffrement de disque. Elle se contente de lire/écrire ses données via les pilotes standards (JDBC pour PostgreSQL, S3 pour MinIO), sans connaissance de la manière dont les blocs sont stockés physiquement.
- C'est l'infrastructure (fournisseur cloud managé, ou hyperviseur/OS de la machine auto-hébergée) qui doit activer le chiffrement du volume de stockage sous-jacent.

## 2. Options selon le choix d'hébergement futur

- **PostgreSQL/MinIO managés (offre cloud)** : activer le chiffrement de disque/volume géré par le fournisseur à la création de l'instance (option explicite, généralement activable en un clic ou un paramètre Terraform/CLI — à vérifier dans la documentation du fournisseur retenu, ce choix n'étant pas encore fait).
- **Auto-hébergé (VM/Docker sur serveur dédié)** : chiffrement au niveau du système de fichiers hôte (LUKS/dm-crypt sur Linux) appliqué aux volumes Docker utilisés par les conteneurs PostgreSQL et MinIO, de sorte que les fichiers de données restent illisibles si le disque physique est extrait ou copié hors du serveur.

## 3. Ce qui ne compte pas comme chiffrement au repos

Attention à ne pas confondre ce critère avec le **chiffrement applicatif au niveau champ** déjà livré par les tickets #13 (`PHOTO_CLE_CHIFFREMENT`) et #22 (`ALERTE_CLE_CHIFFREMENT`). Ces deux mécanismes chiffrent une donnée précise (photo de pièce, contact citoyen) côté application avant écriture, avec une clé applicative gérée par variable d'environnement. Ils sont utiles et répondent à un besoin de confidentialité au niveau champ, mais :

- ils ne chiffrent qu'un sous-ensemble des données (pas la base entière, pas tous les objets MinIO) ;
- ils ne remplacent pas le chiffrement de disque/volume, qui protège l'intégralité des données stockées (y compris les données non chiffrées au niveau champ) contre un accès physique au support de stockage.

`PHOTO_CLE_CHIFFREMENT`/`ALERTE_CLE_CHIFFREMENT` ne doivent donc jamais être cités comme preuve de conformité au critère d'acceptation 1 de ce ticket.

## 4. Checklist à cocher avant toute mise en environnement partagé

| Étape | Propriétaire |
|---|---|
| [ ] Choisir le mode d'hébergement (managé cloud vs auto-hébergé) | Décideur technique / DevOps |
| [ ] Si managé : activer explicitement le chiffrement de volume à la création de l'instance PostgreSQL et du bucket/instance MinIO | DevOps |
| [ ] Si auto-hébergé : configurer LUKS/dm-crypt sur les volumes hôtes avant le premier démarrage des conteneurs PostgreSQL/MinIO | DevOps / Administrateur système |
| [ ] Documenter le choix retenu et la méthode d'activation dans ce document (mettre à jour la section 6) | DevOps |
| [ ] Vérifier que les sauvegardes (dumps PostgreSQL, snapshots MinIO) héritent également du chiffrement (un dump non chiffré exporté hors du volume chiffré annule la protection) | DevOps |

## 5. Procédure de vérification une fois l'infra provisionnée

- **Fournisseur cloud managé** : utiliser la commande/console `describe`/`show` du fournisseur pour confirmer que le chiffrement est actif sur l'instance (ex. `aws rds describe-db-instances --query 'DBInstances[].StorageEncrypted'` pour AWS RDS, équivalent selon le fournisseur retenu).
- **Auto-hébergé (LUKS/dm-crypt)** : `cryptsetup status <nom-du-volume>` sur l'hôte doit confirmer que le volume est bien déverrouillé via un mapping chiffré (`type: LUKS1`/`LUKS2`), et non un volume en clair monté directement.
- Dans les deux cas, consigner le résultat de cette vérification (date, commande exécutée, sortie obtenue) dans ce document au moment du provisioning réel, sur le même modèle que la revue documentée dans `docs/securite/gestion-secrets-et-revue-historique.md`.

## 6. Hors périmètre

- L'introduction d'un KMS/Vault pour la gestion des clés de chiffrement est un écart connu (cf. `docs/securite/gestion-secrets-et-revue-historique.md`, section 8), traité par un ticket futur distinct.
- Le provisioning réel d'une infrastructure staging/prod (choix du fournisseur, Terraform/Kubernetes) n'est pas réalisé par ce ticket ni ce document.
