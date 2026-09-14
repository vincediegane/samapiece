# Review - Ticket #15 : Detection de doublons a la creation d'une fiche

APPROVE

## Criteres d'acceptation

| # | Critere | Statut |
|---|---|---|
| 1 | 409 explicite si doublon actif detecte | Couvert |
| 2 | Confirmation forcee possible | Couvert |
| 3 | Test d'integration couvrant les deux cas | Couvert |

## Verifications ciblees

1. Constructeur 13 params inchange : confirme par le diff, le corps du constructeur a 13 parametres delegue via this(..., false) vers la nouvelle surcharge a 14 parametres ; signature identique a l'avant, aucun appelant existant modifie.
2. Point d'insertion de detecterDoublon : appelee avant numeroDocumentHasher.hacher(...), et seulement si !request.confirmerMalgreDoublon().
3. Court-circuit total verifie par test unitaire dedie (never() sur findByTypeDocumentAndStatutIn).
4. Statuts actifs exacts : DISPONIBLE, RECLAMEE, LITIGE, SIGNALEE.
5. Minimisation stricte du 409 verifiee par jsonPath doesNotExist sur nomTitulaire, prenomTitulaire, numeroDocumentMasque.
6. ErreurReponse et son handler existant non modifies (diff vide sur cette portion).
7. Migration V11 conforme au DDL fige (colonne + index composite), pas de collision de numerotation Flyway.
8. PieceResponse.creeMalgreDoublon correctement alimente (false et true testes).
9. Tests d'integration utilisent un vrai NumeroDocumentHasher via un nouveau helper dedie.
10. getContentAsString(UTF_8) present sur les assertions de corps 409.
11. Ordre de nettoyage piece_sequence puis Poste preserve dans nettoyer().
12. findByTypeDocumentAndStatutAndNomTitulaireIgnoreCase inchangee, ajout pur de la nouvelle methode.

## Point signale par le coder : modification de PieceTest

Legitime. Le nouveau test verifie factuellement l'existence de deux constructeurs publics, et controle sur les deux signatures que seuls des parametres deja existants (hash/sel/masque) et le nouveau boolean creeMalgreDoublon sont acceptes, sans introduire de parametre de type numero brut. L'invariant de securite initial reste verifie sur l'ancienne signature et est etendu correctement a la nouvelle. Limite preexistante inchangee (verification par type, pas par nom), non introduite par ce ticket.

## Findings

Aucun finding bloquant.

- Mineur, cosmetique : dans PieceServiceTest, un des nouveaux tests utilise une reference qualifiee inline vers InstanceOfAssertFactories plutot qu'un import statique en entete, incoherent avec le style du reste du fichier. Aucun impact fonctionnel.

## Build et tests

- mvn -q -pl backend -am test-compile : succes.
- mvn -q -pl backend -am test -Dtest=PieceServiceTest,PieceTest,CreerPieceRequestTest : succes, aucun echec.
- mvn -q -pl backend -am test sur les modules enregistrement, recherche, alertes, audit : 158 tests, 0 failure, 13 errors. Les 13 erreurs proviennent exclusivement de l'indisponibilite de Docker pour Testcontainers (ContainerFetchException / Previous attempts to find a Docker environment failed) sur les classes SpringBootTest et Testcontainers (PieceIntegrationTest et les tests d'integration de recherche publique), limitation connue du sandbox, pas une regression du code. Aucun test unitaire pur n'a echoue.
- Relecture manuelle complete du diff de production et des tests ajoutes ou modifies pour compenser l'indisponibilite de Testcontainers.
