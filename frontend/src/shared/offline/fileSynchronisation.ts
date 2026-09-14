import { creerPiece, PieceApiError } from '../../features/pieces/piecesApi';
import type { CreerPieceRequest } from '../../features/pieces/types';
import { ouvrirBase } from './db';
import type { FicheEnAttente } from './types';

export const NOMBRE_MAX_TENTATIVES = 4;
export const PALIERS_BACKOFF_MS: readonly number[] = [15_000, 60_000, 300_000];
export const DUREE_RAFRAICHISSEMENT_MS = 30_000;

const idsEnCours = new Set<string>();

export async function mettreEnFile(payload: CreerPieceRequest): Promise<FicheEnAttente> {
  const item: FicheEnAttente = {
    id: crypto.randomUUID(),
    payload,
    statut: 'en_attente',
    tentatives: 0,
    creeLeLocal: new Date().toISOString(),
    derniereErreur: null,
    numeroFicheServeur: null,
    prochaineTentativeAuPlusTotLe: null,
  };
  const db = await ouvrirBase();
  await db.put('fiches-en-attente', item);
  return item;
}

export async function listerFile(): Promise<FicheEnAttente[]> {
  const db = await ouvrirBase();
  const items = await db.getAll('fiches-en-attente');
  return items.sort((a, b) => a.creeLeLocal.localeCompare(b.creeLeLocal));
}

async function persister(item: FicheEnAttente): Promise<void> {
  const db = await ouvrirBase();
  await db.put('fiches-en-attente', item);
}

async function supprimerDeLaFile(id: string): Promise<void> {
  const db = await ouvrirBase();
  await db.delete('fiches-en-attente', id);
}

function estEligible(
  item: FicheEnAttente,
  maintenant: number,
  ignorerDelaiBackoff: boolean,
): boolean {
  if (idsEnCours.has(item.id)) return false;
  if (item.statut === 'en_attente') return true;
  if (item.statut === 'echec_reseau') {
    if (ignorerDelaiBackoff) return true;
    if (item.prochaineTentativeAuPlusTotLe === null) return true;
    return new Date(item.prochaineTentativeAuPlusTotLe).getTime() <= maintenant;
  }
  return false;
}

async function marquerEchecReseau(item: FicheEnAttente): Promise<void> {
  if (item.tentatives >= NOMBRE_MAX_TENTATIVES) {
    item.statut = 'echec_definitif';
    item.derniereErreur =
      'Échec définitif après plusieurs tentatives — vérifiez la connexion puis réessayez manuellement.';
    item.prochaineTentativeAuPlusTotLe = null;
  } else {
    item.statut = 'echec_reseau';
    item.derniereErreur = 'Échec réseau, nouvelle tentative automatique programmée.';
    item.prochaineTentativeAuPlusTotLe = new Date(
      Date.now() + PALIERS_BACKOFF_MS[item.tentatives - 1],
    ).toISOString();
  }
  await persister(item);
}

async function envoyerItem(item: FicheEnAttente): Promise<void> {
  if (idsEnCours.has(item.id)) return;
  idsEnCours.add(item.id);
  try {
    item.tentatives += 1;
    try {
      const reponse = await creerPiece(item.payload);
      item.statut = 'synchronise';
      item.numeroFicheServeur = reponse.numeroFiche;
      item.derniereErreur = null;
      await persister(item);
      await supprimerDeLaFile(item.id);
    } catch (error) {
      if (error instanceof PieceApiError) {
        if (error.status === 409) {
          item.statut = 'conflit_doublon';
          item.derniereErreur =
            'Conflit détecté (doublon potentiel) — nécessite une vérification manuelle au poste.';
          item.prochaineTentativeAuPlusTotLe = null;
          await persister(item);
          return;
        }
        if (error.status === 401) {
          item.statut = 'echec_definitif';
          item.derniereErreur = 'Session expirée : reconnectez-vous puis cliquez sur Réessayer.';
          item.prochaineTentativeAuPlusTotLe = null;
          await persister(item);
          return;
        }
        if (error.status >= 500) {
          await marquerEchecReseau(item);
          return;
        }
        item.statut = 'echec_definitif';
        item.derniereErreur = `Erreur du serveur (code ${error.status}) — vérifiez les données ou contactez le support.`;
        item.prochaineTentativeAuPlusTotLe = null;
        await persister(item);
        return;
      }
      await marquerEchecReseau(item);
    }
  } finally {
    idsEnCours.delete(item.id);
  }
}

export async function synchroniser(options?: { ignorerDelaiBackoff?: boolean }): Promise<void> {
  const ignorerDelaiBackoff = options?.ignorerDelaiBackoff ?? false;
  const maintenant = Date.now();
  const items = await listerFile();
  const eligibles = items.filter((item) => estEligible(item, maintenant, ignorerDelaiBackoff));
  for (const item of eligibles) {
    item.statut = 'en_cours';
    await persister(item);
    await envoyerItem(item);
  }
}

export async function reessayerItem(id: string): Promise<void> {
  const items = await listerFile();
  const item = items.find((i) => i.id === id);
  if (!item) return;
  if (item.statut !== 'echec_reseau' && item.statut !== 'echec_definitif') return;
  item.tentatives = 0;
  item.prochaineTentativeAuPlusTotLe = null;
  item.statut = 'en_attente';
  await persister(item);
  await envoyerItem(item);
}

export async function reessayerTout(): Promise<void> {
  const items = await listerFile();
  const aReessayer = items.filter(
    (item) => item.statut === 'echec_reseau' || item.statut === 'echec_definitif',
  );
  for (const item of aReessayer) {
    item.tentatives = 0;
    item.prochaineTentativeAuPlusTotLe = null;
    item.statut = 'en_attente';
    await persister(item);
  }
  await synchroniser({ ignorerDelaiBackoff: true });
}

export function demarrerDeclencheurs(): () => void {
  void synchroniser();
  const surRetourEnLigne = () => {
    void synchroniser({ ignorerDelaiBackoff: true });
  };
  window.addEventListener('online', surRetourEnLigne);
  const intervalle = window.setInterval(() => {
    void synchroniser();
  }, DUREE_RAFRAICHISSEMENT_MS);
  return () => {
    window.removeEventListener('online', surRetourEnLigne);
    window.clearInterval(intervalle);
  };
}
