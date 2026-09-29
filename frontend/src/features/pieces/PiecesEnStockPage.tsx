import { useEffect, useState } from 'react';
import { listerPieces, PieceApiError } from './piecesApi';
import { STATUT_PIECE_LABELS, TYPE_DOCUMENT_LABELS } from './types';
import type { PageResponse, PieceListeItem, StatutPiece } from './types';

const STATUTS = Object.keys(STATUT_PIECE_LABELS) as StatutPiece[];

function PiecesEnStockPage({ onOuvrirFiche }: { onOuvrirFiche: (id: string) => void }) {
  const [statut, setStatut] = useState<StatutPiece | ''>('');
  const [page, setPage] = useState(0);
  const [resultat, setResultat] = useState<PageResponse<PieceListeItem> | null>(null);
  const [erreur, setErreur] = useState<string | null>(null);
  const [enChargement, setEnChargement] = useState(true);

  useEffect(() => {
    let annule = false;
    setEnChargement(true);
    setErreur(null);
    listerPieces({ statut: statut === '' ? undefined : statut, page })
      .then((donnees) => {
        if (!annule) setResultat(donnees);
      })
      .catch((e: unknown) => {
        if (annule) return;
        setResultat(null);
        if (!(e instanceof PieceApiError) || !navigator.onLine) {
          setErreur('Action impossible hors connexion. Réessayez une fois la connexion rétablie.');
        } else {
          setErreur(e.message);
        }
      })
      .finally(() => {
        if (!annule) setEnChargement(false);
      });
    return () => {
      annule = true;
    };
  }, [statut, page]);

  function changerStatut(valeur: string) {
    setStatut(valeur as StatutPiece | '');
    setPage(0);
  }

  return (
    <main className="mx-auto w-full max-w-5xl px-4 py-10 sm:px-6">
      <h1 className="page-title">Pièces en stock</h1>

      <label className="field mb-6 max-w-sm">
        <span className="field-label">Statut</span>
        <select
          className="field-input"
          value={statut}
          onChange={(e) => changerStatut(e.target.value)}
        >
          <option value="">En stock (disponibles et réclamées)</option>
          {STATUTS.map((s) => (
            <option key={s} value={s}>
              {STATUT_PIECE_LABELS[s]}
            </option>
          ))}
        </select>
      </label>

      {erreur && (
        <p role="alert" className="alert-error">
          {erreur}
        </p>
      )}

      {enChargement && <p className="text-slate-500">Chargement…</p>}

      {!enChargement && resultat && resultat.content.length === 0 && (
        <p className="text-slate-500">Aucune pièce en stock.</p>
      )}

      {!enChargement && resultat && resultat.content.length > 0 && (
        <table className="table">
          <thead>
            <tr>
              <th>N° fiche</th>
              <th>Type</th>
              <th>Statut</th>
              <th>Date de dépôt</th>
              <th>Ancienneté</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {resultat.content.map((piece) => (
              <tr key={piece.id}>
                <td>{piece.numeroFiche}</td>
                <td>{TYPE_DOCUMENT_LABELS[piece.typeDocument]}</td>
                <td>{STATUT_PIECE_LABELS[piece.statut]}</td>
                <td>{piece.dateDepot}</td>
                <td>
                  {piece.ancienneteJours} j
                  {piece.depasseSeuil && (
                    <span className="ml-2 text-xs font-semibold text-danger-500">
                      Dépasse le seuil
                    </span>
                  )}
                </td>
                <td>
                  <button
                    type="button"
                    className="btn-outline px-3.5 py-1.5 text-xs"
                    onClick={() => onOuvrirFiche(piece.id)}
                  >
                    Ouvrir la fiche
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {resultat && (
        <div className="mt-4 flex items-center gap-4 text-sm text-slate-600">
          <button
            type="button"
            className="btn-outline px-3.5 py-1.5 text-xs"
            disabled={resultat.number === 0}
            onClick={() => setPage(resultat.number - 1)}
          >
            Précédent
          </button>
          <span>
            Page {resultat.number + 1} / {Math.max(resultat.totalPages, 1)}
          </span>
          <button
            type="button"
            className="btn-outline px-3.5 py-1.5 text-xs"
            disabled={resultat.number + 1 >= resultat.totalPages}
            onClick={() => setPage(resultat.number + 1)}
          >
            Suivant
          </button>
          <span>{resultat.totalElements} pièce(s)</span>
        </div>
      )}
    </main>
  );
}

export default PiecesEnStockPage;
