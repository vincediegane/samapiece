import { openDB } from 'idb';
import type { DBSchema, IDBPDatabase } from 'idb';
import type { FicheEnAttente } from './types';

interface SamaPieceOfflineDB extends DBSchema {
  'fiches-en-attente': {
    key: string;
    value: FicheEnAttente;
  };
}

let dbPromise: Promise<IDBPDatabase<SamaPieceOfflineDB>> | null = null;

export function ouvrirBase(): Promise<IDBPDatabase<SamaPieceOfflineDB>> {
  if (!dbPromise) {
    dbPromise = openDB<SamaPieceOfflineDB>('samapiece-offline', 1, {
      upgrade(db) {
        db.createObjectStore('fiches-en-attente', { keyPath: 'id' });
      },
    });
  }
  return dbPromise;
}
