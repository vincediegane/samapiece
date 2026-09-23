import { useEffect, useRef, useState } from 'react';
import { listerFile, reessayerItem, reessayerTout } from '../../shared/offline/fileSynchronisation';
import type { FicheEnAttente, StatutFicheEnAttente } from '../../shared/offline/types';

const LIBELLES_STATUT: Record<StatutFicheEnAttente, string> = {
  en_attente: 'En attente de connexion',
  en_cours: 'Envoi en cours…',
  echec_reseau: 'Échec réseau — nouvelle tentative automatique programmée',
  conflit_doublon: 'Conflit détecté (doublon potentiel) — nécessite une vérification manuelle au poste',
  echec_definitif: '',
  synchronise: 'Synchronisé',
};

const INTERVALLE_RAFRAICHISSEMENT_MS = 2_000;
const DELAI_GRACE_SYNCHRONISE_MS = 5_000;

function libelleStatut(item: FicheEnAttente): string {
  if (item.statut === 'echec_definitif') {
    return item.derniereErreur ?? LIBELLES_STATUT.echec_definitif;
  }
  if (item.statut === 'synchronise') {
    return `Synchronisé — numéro de fiche : ${item.numeroFicheServeur ?? ''}`;
  }
  return LIBELLES_STATUT[item.statut];
}

const BORDURE_STATUT: Record<StatutFicheEnAttente, string> = {
  en_attente: 'border-l-slate-300',
  en_cours: 'border-l-info-500',
  echec_reseau: 'border-l-danger-500',
  conflit_doublon: 'border-l-accent-500',
  echec_definitif: 'border-l-danger-500',
  synchronise: 'border-l-primary-500',
};

function FileAttenteSynchronisation() {
  const [itemsAffiches, setItemsAffiches] = useState<FicheEnAttente[]>([]);
  const idsSynchronisesProgrammes = useRef<Set<string>>(new Set());
  const timeoutsEnAttente = useRef<Map<string, ReturnType<typeof setTimeout>>>(new Map());

  useEffect(() => {
    const timeouts = timeoutsEnAttente.current;

    async function rafraichir() {
      const enBase = await listerFile();
      setItemsAffiches((precedent) => {
        const idsEnBase = new Set(enBase.map((item) => item.id));
        const conserves = precedent.filter(
          (item) => item.statut === 'synchronise' && !idsEnBase.has(item.id),
        );
        return [...enBase, ...conserves];
      });
    }

    void rafraichir();
    const intervalle = setInterval(() => {
      void rafraichir();
    }, INTERVALLE_RAFRAICHISSEMENT_MS);

    return () => {
      clearInterval(intervalle);
      timeouts.forEach((timeout) => clearTimeout(timeout));
      timeouts.clear();
    };
  }, []);

  useEffect(() => {
    const idsProgrammes = idsSynchronisesProgrammes.current;
    const timeouts = timeoutsEnAttente.current;
    itemsAffiches
      .filter((item) => item.statut === 'synchronise')
      .forEach((item) => {
        if (idsProgrammes.has(item.id)) return;
        idsProgrammes.add(item.id);
        const timeout = setTimeout(() => {
          setItemsAffiches((precedent) => precedent.filter((i) => i.id !== item.id));
          idsProgrammes.delete(item.id);
          timeouts.delete(item.id);
        }, DELAI_GRACE_SYNCHRONISE_MS);
        timeouts.set(item.id, timeout);
      });
  }, [itemsAffiches]);

  if (itemsAffiches.length === 0) return null;

  const aDesEchecs = itemsAffiches.some(
    (item) => item.statut === 'echec_reseau' || item.statut === 'echec_definitif',
  );

  return (
    <section aria-label="File d'attente de synchronisation" className="card">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
        <h2 className="section-title mb-0">File d&apos;attente de synchronisation</h2>
        {aDesEchecs && (
          <button type="button" className="btn-outline" onClick={() => reessayerTout()}>
            Réessayer tout maintenant
          </button>
        )}
      </div>
      <ul className="flex flex-col gap-2">
        {itemsAffiches.map((item) => (
          <li
            key={item.id}
            data-statut={item.statut}
            className={`flex flex-wrap items-center justify-between gap-3 rounded-md border-l-4 bg-slate-50 px-3 py-2 ${BORDURE_STATUT[item.statut]}`}
          >
            <p className="text-sm text-slate-700">
              {item.payload.prenomTitulaire} {item.payload.nomTitulaire} — {libelleStatut(item)}
            </p>
            {(item.statut === 'echec_reseau' || item.statut === 'echec_definitif') && (
              <button
                type="button"
                className="btn-danger-outline"
                onClick={() => reessayerItem(item.id)}
              >
                Réessayer maintenant
              </button>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}

export default FileAttenteSynchronisation;
