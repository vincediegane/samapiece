import { useState } from 'react';
import { telechargerRecu, PieceApiError } from './piecesApi';
import type { PieceResponse, StatutPiece, TypeDocument } from './types';
import { STATUT_PIECE_COULEURS, STATUT_PIECE_LABELS, TYPE_DOCUMENT_LABELS } from './types';
import RetraitForm from './RetraitForm';
import SignalerForm from './SignalerForm';
import DeblocageForm from './DeblocageForm';
import { IconDownload } from '../../shared/icons';

interface FichePieceCardProps {
  piece: PieceResponse;
  roleAgentCourant: string | null;
  onMisAJour: (piece: PieceResponse) => void;
}

type FormulaireActif = 'retrait' | 'signaler' | 'debloquer' | null;

const STATUTS_RETRAIT_SIGNALEMENT = ['DISPONIBLE', 'RECLAMEE'];
const STATUTS_DEBLOCAGE = ['RETIREE', 'ARCHIVEE', 'LITIGE', 'SIGNALEE'];

function FichePieceCard({ piece, roleAgentCourant, onMisAJour }: FichePieceCardProps) {
  const [formulaireActif, setFormulaireActif] = useState<FormulaireActif>(null);
  const [messageConfirmation, setMessageConfirmation] = useState<string | null>(null);
  const [erreur, setErreur] = useState<string | null>(null);

  const statut = piece.statut as StatutPiece;
  const peutRetirerOuSignaler = STATUTS_RETRAIT_SIGNALEMENT.includes(piece.statut);
  // ADMIN_REGIONAL/ADMIN_NATIONAL restent autorisés côté API (POST /debloquer) mais n'ont
  // volontairement aucun parcours UI ici : limitation assumée, cf. spec #63 "Écarts identifiés".
  const peutDebloquer =
    roleAgentCourant === 'CHEF_POSTE' && STATUTS_DEBLOCAGE.includes(piece.statut);

  function gererSucces(pieceMiseAJour: PieceResponse) {
    onMisAJour(pieceMiseAJour);
    setFormulaireActif(null);
    setErreur(null);
    setMessageConfirmation(
      `Statut mis à jour : ${STATUT_PIECE_LABELS[pieceMiseAJour.statut as StatutPiece] ?? pieceMiseAJour.statut}.`,
    );
  }

  function basculerFormulaire(formulaire: FormulaireActif) {
    setMessageConfirmation(null);
    setErreur(null);
    setFormulaireActif(formulaire);
  }

  async function telecharger() {
    setErreur(null);
    try {
      const { blob, nomFichier } = await telechargerRecu(piece.id);
      const url = URL.createObjectURL(blob);
      const lien = document.createElement('a');
      lien.href = url;
      lien.download = nomFichier;
      document.body.appendChild(lien);
      lien.click();
      document.body.removeChild(lien);
      URL.revokeObjectURL(url);
    } catch (e) {
      if (!(e instanceof PieceApiError) || !navigator.onLine) {
        setErreur('Action impossible hors connexion. Réessayez une fois la connexion rétablie.');
      } else {
        setErreur(e.message);
      }
    }
  }

  return (
    <section className="card">
      {erreur && (
        <p role="alert" className="alert-error">
          {erreur}
        </p>
      )}
      {messageConfirmation && <p className="alert-success">{messageConfirmation}</p>}

      <div className="flex flex-col gap-3">
        <p>
          <span className="block text-[11px] font-semibold uppercase tracking-wide text-slate-400">
            N° de fiche
          </span>
          <strong className="text-base font-bold text-slate-900">{piece.numeroFiche}</strong>
        </p>
        <p>
          <span className="block text-[11px] font-semibold uppercase tracking-wide text-slate-400">
            Identifiant technique
          </span>
          <span className="break-all text-xs text-slate-500">{piece.id}</span>
        </p>
        <p>
          <span className="block text-[11px] font-semibold uppercase tracking-wide text-slate-400">
            Type de document
          </span>
          {TYPE_DOCUMENT_LABELS[piece.typeDocument as TypeDocument] ?? piece.typeDocument}
        </p>
        <p>
          <span className="block text-[11px] font-semibold uppercase tracking-wide text-slate-400">
            Titulaire
          </span>
          {piece.prenomTitulaire} {piece.nomTitulaire}
        </p>
        <p>
          <span className="block text-[11px] font-semibold uppercase tracking-wide text-slate-400">
            Numéro de document
          </span>
          {piece.numeroDocumentMasque}
        </p>
        <p>
          <span className="block text-[11px] font-semibold uppercase tracking-wide text-slate-400">
            Date de dépôt
          </span>
          {piece.dateDepot}
        </p>
        <p>
          <span className="block text-[11px] font-semibold uppercase tracking-wide text-slate-400">
            Statut
          </span>
          <span className="inline-flex items-center gap-1.5 font-semibold text-slate-700">
            <span className={`badge-dot ${STATUT_PIECE_COULEURS[statut] ?? 'bg-slate-400'}`} />
            {STATUT_PIECE_LABELS[statut] ?? piece.statut}
          </span>
        </p>
      </div>

      <div className="mt-5 flex flex-wrap gap-3">
        {peutRetirerOuSignaler && (
          <button
            type="button"
            className="btn-outline"
            onClick={() => basculerFormulaire('retrait')}
          >
            Enregistrer un retrait
          </button>
        )}
        {peutRetirerOuSignaler && (
          <button
            type="button"
            className="btn-danger-outline"
            onClick={() => basculerFormulaire('signaler')}
          >
            Signaler cette pièce
          </button>
        )}
        {peutDebloquer && (
          <button
            type="button"
            className="btn-outline"
            onClick={() => basculerFormulaire('debloquer')}
          >
            Débloquer
          </button>
        )}
        <button type="button" className="btn-outline" onClick={telecharger}>
          <IconDownload width={16} height={16} />
          Télécharger le reçu
        </button>
      </div>

      {formulaireActif === 'retrait' && (
        <div className="mt-5 border-t border-slate-200 pt-5">
          <RetraitForm
            pieceId={piece.id}
            onSucces={gererSucces}
            onAnnuler={() => setFormulaireActif(null)}
          />
        </div>
      )}
      {formulaireActif === 'signaler' && (
        <div className="mt-5 border-t border-slate-200 pt-5">
          <SignalerForm
            pieceId={piece.id}
            onSucces={gererSucces}
            onAnnuler={() => setFormulaireActif(null)}
          />
        </div>
      )}
      {formulaireActif === 'debloquer' && (
        <div className="mt-5 border-t border-slate-200 pt-5">
          <DeblocageForm
            pieceId={piece.id}
            onSucces={gererSucces}
            onAnnuler={() => setFormulaireActif(null)}
          />
        </div>
      )}
    </section>
  );
}

export default FichePieceCard;
