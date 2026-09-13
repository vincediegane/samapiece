import { useState } from 'react';
import type { FormEvent } from 'react';
import { creerPiece } from './piecesApi';
import type { CreerPieceRequest, EtatDocumentOption, PieceResponse, TypeDocument } from './types';
import { ETAT_DOCUMENT_OPTIONS, TYPE_DOCUMENT_LABELS } from './types';

type ChampRequis =
  'typeDocument' | 'nomTitulaire' | 'prenomTitulaire' | 'numeroDocument' | 'dateDepot';

interface FormState {
  typeDocument: TypeDocument | '';
  nomTitulaire: string;
  prenomTitulaire: string;
  numeroDocument: string;
  dateNaissanceTitulaire: string;
  dateDepot: string;
  etatDocument: EtatDocumentOption | '';
  remarques: string;
}

const FORMULAIRE_INITIAL: FormState = {
  typeDocument: '',
  nomTitulaire: '',
  prenomTitulaire: '',
  numeroDocument: '',
  dateNaissanceTitulaire: '',
  dateDepot: '',
  etatDocument: '',
  remarques: '',
};

function validerFormulaire(f: FormState): Partial<Record<ChampRequis, string>> {
  const erreurs: Partial<Record<ChampRequis, string>> = {};
  if (!f.typeDocument) erreurs.typeDocument = 'Le type de document est requis.';
  if (!f.nomTitulaire.trim()) erreurs.nomTitulaire = 'Le nom du titulaire est requis.';
  if (!f.prenomTitulaire.trim()) erreurs.prenomTitulaire = 'Le prénom du titulaire est requis.';
  if (!f.numeroDocument.trim()) erreurs.numeroDocument = 'Le numéro du document est requis.';
  if (!f.dateDepot) erreurs.dateDepot = 'La date de dépôt est requise.';
  return erreurs;
}

function EnregistrementPiecePage() {
  const [formulaire, setFormulaire] = useState<FormState>(FORMULAIRE_INITIAL);
  const [erreursValidation, setErreursValidation] = useState<Partial<Record<ChampRequis, string>>>(
    {},
  );
  const [erreurServeur, setErreurServeur] = useState<string | null>(null);
  const [enEnvoi, setEnEnvoi] = useState(false);
  const [recu, setRecu] = useState<PieceResponse | null>(null);

  async function soumettreFormulaire(evenement: FormEvent) {
    evenement.preventDefault();
    setErreurServeur(null);
    const erreurs = validerFormulaire(formulaire);
    setErreursValidation(erreurs);
    if (Object.keys(erreurs).length > 0) return;

    setEnEnvoi(true);
    try {
      const payload: CreerPieceRequest = {
        typeDocument: formulaire.typeDocument as TypeDocument,
        nomTitulaire: formulaire.nomTitulaire.trim(),
        prenomTitulaire: formulaire.prenomTitulaire.trim(),
        numeroDocument: formulaire.numeroDocument.trim(),
        dateNaissanceTitulaire: formulaire.dateNaissanceTitulaire || null,
        dateDepot: formulaire.dateDepot,
        etatDocument: formulaire.etatDocument || null,
        remarques: formulaire.remarques.trim() || null,
      };
      const resultat = await creerPiece(payload);
      setRecu(resultat);
      setFormulaire(FORMULAIRE_INITIAL);
      setErreursValidation({});
    } catch (e) {
      setErreurServeur(
        e instanceof Error ? e.message : 'Erreur inconnue lors de l’enregistrement.',
      );
    } finally {
      setEnEnvoi(false);
    }
  }

  return (
    <main>
      <h1>Enregistrement d&apos;une pièce</h1>

      {erreurServeur && <p role="alert">{erreurServeur}</p>}

      {recu && (
        <section aria-label="Reçu d'enregistrement">
          <h2>Fiche enregistrée</h2>
          <p>
            Numéro de fiche : <strong>{recu.numeroFiche}</strong>
          </p>
          <p>
            Type de document :{' '}
            {TYPE_DOCUMENT_LABELS[recu.typeDocument as TypeDocument] ?? recu.typeDocument}
          </p>
          <p>
            Titulaire : {recu.prenomTitulaire} {recu.nomTitulaire}
          </p>
          <p>Numéro de document : {recu.numeroDocumentMasque}</p>
          <p>Date de dépôt : {recu.dateDepot}</p>
          <p>Statut : {recu.statut}</p>
        </section>
      )}

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
        {erreursValidation.prenomTitulaire && (
          <span role="alert">{erreursValidation.prenomTitulaire}</span>
        )}

        <label>
          Numéro du document
          <input
            type="text"
            value={formulaire.numeroDocument}
            onChange={(e) => setFormulaire({ ...formulaire, numeroDocument: e.target.value })}
          />
        </label>
        {erreursValidation.numeroDocument && (
          <span role="alert">{erreursValidation.numeroDocument}</span>
        )}

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

        <label>
          Date de dépôt
          <input
            type="date"
            value={formulaire.dateDepot}
            onChange={(e) => setFormulaire({ ...formulaire, dateDepot: e.target.value })}
          />
        </label>
        {erreursValidation.dateDepot && <span role="alert">{erreursValidation.dateDepot}</span>}

        <label>
          État du document
          <select
            value={formulaire.etatDocument}
            onChange={(e) =>
              setFormulaire({ ...formulaire, etatDocument: e.target.value as EtatDocumentOption })
            }
          >
            <option value=""></option>
            {ETAT_DOCUMENT_OPTIONS.map((etat) => (
              <option key={etat} value={etat}>
                {etat}
              </option>
            ))}
          </select>
        </label>

        <label>
          Remarques
          <textarea
            value={formulaire.remarques}
            onChange={(e) => setFormulaire({ ...formulaire, remarques: e.target.value })}
          />
        </label>

        <button type="submit" disabled={enEnvoi}>
          Enregistrer la pièce
        </button>
      </form>
    </main>
  );
}

export default EnregistrementPiecePage;
