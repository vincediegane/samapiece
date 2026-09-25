import { useState } from 'react';
import type { FormEvent } from 'react';
import { consulterPiece, PieceApiError } from './piecesApi';
import type { PieceResponse } from './types';
import { recupererAgentCourant } from '../dashboard/dashboardApi';
import FichePieceCard from './FichePieceCard';

const REGEX_UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function ConsulterFichePage() {
  const [identifiant, setIdentifiant] = useState('');
  const [erreur, setErreur] = useState<string | null>(null);
  const [enChargement, setEnChargement] = useState(false);
  const [piece, setPiece] = useState<PieceResponse | null>(null);
  const [roleAgentCourant, setRoleAgentCourant] = useState<string | null>(null);

  async function ouvrirFiche(evenement: FormEvent) {
    evenement.preventDefault();
    setErreur(null);
    setPiece(null);
    if (!REGEX_UUID.test(identifiant.trim())) {
      setErreur("Format d'identifiant invalide (UUID attendu).");
      return;
    }
    setEnChargement(true);
    try {
      const resultat = await consulterPiece(identifiant.trim());
      setPiece(resultat);
      try {
        const agent = await recupererAgentCourant();
        setRoleAgentCourant(agent.role);
      } catch {
        setRoleAgentCourant(null);
      }
    } catch (e) {
      if (!(e instanceof PieceApiError) || !navigator.onLine) {
        setErreur('Action impossible hors connexion. Réessayez une fois la connexion rétablie.');
      } else {
        setErreur(e.message);
      }
    } finally {
      setEnChargement(false);
    }
  }

  return (
    <main className="mx-auto w-full max-w-3xl px-4 py-10 sm:px-6">
      <h1 className="page-title">Ouvrir une fiche</h1>

      {erreur && (
        <p role="alert" className="alert-error">
          {erreur}
        </p>
      )}

      <form
        onSubmit={ouvrirFiche}
        className="mb-6 flex flex-col gap-4 rounded-2xl border border-slate-200 bg-white p-6"
      >
        <label className="field">
          <span className="field-label">Identifiant de la fiche (UUID)</span>
          <input
            type="text"
            className="field-input"
            value={identifiant}
            onChange={(e) => setIdentifiant(e.target.value)}
          />
          <span className="text-xs text-slate-500">
            Cet identifiant technique est visible sur la fiche affichée juste après
            l&apos;enregistrement d&apos;une pièce.
          </span>
        </label>
        <button type="submit" className="btn-primary self-start" disabled={enChargement}>
          Ouvrir la fiche
        </button>
      </form>

      {piece && (
        <FichePieceCard piece={piece} roleAgentCourant={roleAgentCourant} onMisAJour={setPiece} />
      )}
    </main>
  );
}

export default ConsulterFichePage;
