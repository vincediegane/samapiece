import { useState } from 'react';
import type { FormEvent } from 'react';
import { retirerPiece, PieceApiError } from './piecesApi';
import type { PieceResponse } from './types';

interface RetraitFormProps {
  pieceId: string;
  onSucces: (piece: PieceResponse) => void;
  onAnnuler: () => void;
}

interface FormState {
  nomReclamant: string;
  pieceJustificativePresentee: string;
}

const FORMULAIRE_INITIAL: FormState = {
  nomReclamant: '',
  pieceJustificativePresentee: '',
};

function RetraitForm({ pieceId, onSucces, onAnnuler }: RetraitFormProps) {
  const [formulaire, setFormulaire] = useState<FormState>(FORMULAIRE_INITIAL);
  const [erreurValidation, setErreurValidation] = useState<string | null>(null);
  const [erreur, setErreur] = useState<string | null>(null);
  const [enEnvoi, setEnEnvoi] = useState(false);

  async function soumettre(evenement: FormEvent) {
    evenement.preventDefault();
    setErreur(null);
    if (!formulaire.nomReclamant.trim() || !formulaire.pieceJustificativePresentee.trim()) {
      setErreurValidation('Le nom du réclamant et la pièce justificative sont requis.');
      return;
    }
    setErreurValidation(null);
    setEnEnvoi(true);
    try {
      const resultat = await retirerPiece(pieceId, {
        nomReclamant: formulaire.nomReclamant.trim(),
        pieceJustificativePresentee: formulaire.pieceJustificativePresentee.trim(),
      });
      onSucces(resultat);
    } catch (e) {
      if (!(e instanceof PieceApiError) || !navigator.onLine) {
        setErreur('Action impossible hors connexion. Réessayez une fois la connexion rétablie.');
      } else {
        setErreur(e.message);
      }
    } finally {
      setEnEnvoi(false);
    }
  }

  return (
    <form onSubmit={soumettre} className="flex flex-col gap-4">
      {erreur && (
        <p role="alert" className="alert-error">
          {erreur}
        </p>
      )}
      {erreurValidation && (
        <span role="alert" className="field-error">
          {erreurValidation}
        </span>
      )}
      <label className="field">
        <span className="field-label">Nom du réclamant</span>
        <input
          type="text"
          className="field-input"
          value={formulaire.nomReclamant}
          onChange={(e) => setFormulaire({ ...formulaire, nomReclamant: e.target.value })}
        />
      </label>
      <label className="field">
        <span className="field-label">Pièce justificative présentée</span>
        <input
          type="text"
          className="field-input"
          value={formulaire.pieceJustificativePresentee}
          onChange={(e) =>
            setFormulaire({ ...formulaire, pieceJustificativePresentee: e.target.value })
          }
        />
      </label>
      <div className="flex gap-3">
        <button type="submit" className="btn-primary" disabled={enEnvoi}>
          Confirmer le retrait
        </button>
        <button type="button" className="btn-outline" onClick={onAnnuler}>
          Annuler
        </button>
      </div>
    </form>
  );
}

export default RetraitForm;
