import { useState } from 'react';
import type { FormEvent } from 'react';
import { signalerPiece, PieceApiError } from './piecesApi';
import type { PieceResponse, StatutCibleSignalement } from './types';

interface SignalerFormProps {
  pieceId: string;
  onSucces: (piece: PieceResponse) => void;
  onAnnuler: () => void;
}

function SignalerForm({ pieceId, onSucces, onAnnuler }: SignalerFormProps) {
  const [statutCible, setStatutCible] = useState<StatutCibleSignalement>('LITIGE');
  const [motif, setMotif] = useState('');
  const [erreurValidation, setErreurValidation] = useState<string | null>(null);
  const [erreur, setErreur] = useState<string | null>(null);
  const [enEnvoi, setEnEnvoi] = useState(false);

  async function soumettre(evenement: FormEvent) {
    evenement.preventDefault();
    setErreur(null);
    if (!motif.trim()) {
      setErreurValidation('Le motif est requis.');
      return;
    }
    setErreurValidation(null);
    setEnEnvoi(true);
    try {
      const resultat = await signalerPiece(pieceId, { statutCible, motif: motif.trim() });
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
        <span className="field-label">Type de signalement</span>
        <select
          className="field-input"
          value={statutCible}
          onChange={(e) => setStatutCible(e.target.value as StatutCibleSignalement)}
        >
          <option value="LITIGE">Litige</option>
          <option value="SIGNALEE">Signalée</option>
        </select>
      </label>
      <label className="field">
        <span className="field-label">Motif</span>
        <textarea
          className="field-input min-h-20 resize-y"
          value={motif}
          onChange={(e) => setMotif(e.target.value)}
        />
      </label>
      <div className="flex gap-3">
        <button type="submit" className="btn-primary" disabled={enEnvoi}>
          Signaler la pièce
        </button>
        <button type="button" className="btn-outline" onClick={onAnnuler}>
          Annuler
        </button>
      </div>
    </form>
  );
}

export default SignalerForm;
