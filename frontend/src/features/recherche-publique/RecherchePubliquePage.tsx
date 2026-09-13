import { useState } from 'react';
import type { FormEvent } from 'react';
import { rechercher } from './recherchePubliqueApi';
import type { RecherchePubliqueRequest, RecherchePubliqueResponse } from './types';
import { TYPE_DOCUMENT_LABELS } from '../pieces/types';
import type { TypeDocument } from '../pieces/types';

interface FormState {
  typeDocument: TypeDocument | '';
  nomTitulaire: string;
  prenomTitulaire: string;
  numeroDocument: string;
  dateNaissanceTitulaire: string;
}

const FORMULAIRE_INITIAL: FormState = {
  typeDocument: '',
  nomTitulaire: '',
  prenomTitulaire: '',
  numeroDocument: '',
  dateNaissanceTitulaire: '',
};

interface ErreursValidation {
  typeDocument?: string;
  nomTitulaire?: string;
  discriminant?: string;
}

function validerFormulaire(f: FormState): ErreursValidation {
  const erreurs: ErreursValidation = {};
  if (!f.typeDocument) erreurs.typeDocument = 'Le type de document est requis.';
  if (!f.nomTitulaire.trim()) erreurs.nomTitulaire = 'Le nom du titulaire est requis.';
  if (!f.numeroDocument.trim() && !f.dateNaissanceTitulaire) {
    erreurs.discriminant = 'Renseignez le numéro du document ou la date de naissance du titulaire.';
  }
  return erreurs;
}

function RecherchePubliquePage() {
  const [formulaire, setFormulaire] = useState<FormState>(FORMULAIRE_INITIAL);
  const [erreursValidation, setErreursValidation] = useState<ErreursValidation>({});
  const [erreurServeur, setErreurServeur] = useState<string | null>(null);
  const [enEnvoi, setEnEnvoi] = useState(false);
  const [resultat, setResultat] = useState<RecherchePubliqueResponse | null>(null);
  const [alerteProposee, setAlerteProposee] = useState(false);

  async function soumettreFormulaire(evenement: FormEvent) {
    evenement.preventDefault();
    const erreurs = validerFormulaire(formulaire);
    setErreursValidation(erreurs);
    if (Object.keys(erreurs).length > 0) return;

    setErreurServeur(null);
    setEnEnvoi(true);
    try {
      const payload: RecherchePubliqueRequest = {
        typeDocument: formulaire.typeDocument as TypeDocument,
        nomTitulaire: formulaire.nomTitulaire.trim(),
        prenomTitulaire: formulaire.prenomTitulaire.trim() || null,
        numeroDocument: formulaire.numeroDocument.trim() || null,
        dateNaissanceTitulaire: formulaire.dateNaissanceTitulaire || null,
      };
      const reponse = await rechercher(payload);
      setResultat(reponse);
      setAlerteProposee(false);
    } catch (e) {
      setErreurServeur(e instanceof Error ? e.message : 'Erreur inconnue lors de la recherche.');
    } finally {
      setEnEnvoi(false);
    }
  }

  return (
    <main>
      <h1>Recherche publique</h1>

      {erreurServeur && <p role="alert">{erreurServeur}</p>}

      {resultat &&
        (resultat.trouve ? (
          <section aria-label="Résultat de la recherche">
            <h2>Pièce retrouvée</h2>
            <p>
              Type de document :{' '}
              {resultat.typeDocument ? TYPE_DOCUMENT_LABELS[resultat.typeDocument] : ''}
            </p>
            <p>Poste : {resultat.poste?.nom}</p>
            <p>Adresse : {resultat.poste?.adresse}</p>
            <p>Horaires : {resultat.poste?.horaires}</p>
            <p>Téléphone : {resultat.poste?.telephone}</p>
            <p>Référence de dossier : {resultat.referenceDossier}</p>
          </section>
        ) : (
          <section aria-label="Aucun résultat">
            <p>Aucune pièce correspondant à ces critères n&apos;a été retrouvée.</p>
            {alerteProposee ? (
              <p>Cette fonctionnalité arrive bientôt.</p>
            ) : (
              <button type="button" onClick={() => setAlerteProposee(true)}>
                Recevoir une alerte si cette pièce est déposée
              </button>
            )}
          </section>
        ))}

      <form onSubmit={soumettreFormulaire}>
        <label>
          Type de document
          <select
            value={formulaire.typeDocument}
            onChange={(e) =>
              setFormulaire({ ...formulaire, typeDocument: e.target.value as TypeDocument })
            }
          >
            <option value="" disabled>
              Sélectionner un type
            </option>
            {(Object.keys(TYPE_DOCUMENT_LABELS) as TypeDocument[]).map((type) => (
              <option key={type} value={type}>
                {TYPE_DOCUMENT_LABELS[type]}
              </option>
            ))}
          </select>
        </label>
        {erreursValidation.typeDocument && (
          <span role="alert">{erreursValidation.typeDocument}</span>
        )}

        <label>
          Nom du titulaire
          <input
            type="text"
            value={formulaire.nomTitulaire}
            onChange={(e) => setFormulaire({ ...formulaire, nomTitulaire: e.target.value })}
          />
        </label>
        {erreursValidation.nomTitulaire && (
          <span role="alert">{erreursValidation.nomTitulaire}</span>
        )}

        <label>
          Prénom du titulaire
          <input
            type="text"
            value={formulaire.prenomTitulaire}
            onChange={(e) => setFormulaire({ ...formulaire, prenomTitulaire: e.target.value })}
          />
        </label>

        <label>
          Numéro du document
          <input
            type="text"
            value={formulaire.numeroDocument}
            onChange={(e) => setFormulaire({ ...formulaire, numeroDocument: e.target.value })}
          />
        </label>

        <label>
          Date de naissance du titulaire
          <input
            type="date"
            value={formulaire.dateNaissanceTitulaire}
            onChange={(e) =>
              setFormulaire({ ...formulaire, dateNaissanceTitulaire: e.target.value })
            }
          />
        </label>
        {erreursValidation.discriminant && (
          <span role="alert">{erreursValidation.discriminant}</span>
        )}

        <button type="submit" disabled={enEnvoi}>
          Rechercher
        </button>
      </form>
    </main>
  );
}

export default RecherchePubliquePage;
