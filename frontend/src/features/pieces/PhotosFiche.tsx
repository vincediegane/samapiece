import { useEffect, useState } from 'react';
import PhotoUpload from './PhotoUpload';
import { listerPhotos, PhotoApiError, telechargerPhoto, uploaderPhoto } from './photosApi';
import type { PhotoMeta, TypePhoto } from './types';

interface PhotosFicheProps {
  pieceId: string;
  roleAgentCourant: string | null;
}

interface PhotoAffichee {
  meta: PhotoMeta;
  url: string | null;
}

const LIBELLES: Record<TypePhoto, string> = { RECTO: 'Recto', VERSO: 'Verso' };
const ROLES_AJOUT = ['AGENT', 'CHEF_POSTE'];
const MESSAGE_HORS_CONNEXION = 'Photo indisponible hors connexion';

function PhotosFiche({ pieceId, roleAgentCourant }: PhotosFicheProps) {
  const [photos, setPhotos] = useState<PhotoAffichee[]>([]);
  const [masque, setMasque] = useState(false);
  const [indisponible, setIndisponible] = useState(false);
  const [rechargement, setRechargement] = useState(0);
  const [fichier, setFichier] = useState<File | null>(null);
  const [type, setType] = useState<TypePhoto>('RECTO');
  const [erreur, setErreur] = useState<string | null>(null);
  const [enEnvoi, setEnEnvoi] = useState(false);

  useEffect(() => {
    let annule = false;
    const urls: string[] = [];
    (async () => {
      try {
        const metas = await listerPhotos(pieceId);
        const affichees: PhotoAffichee[] = [];
        for (const meta of metas) {
          try {
            const blob = await telechargerPhoto(pieceId, meta.id);
            const url = URL.createObjectURL(blob);
            urls.push(url);
            affichees.push({ meta, url });
          } catch {
            affichees.push({ meta, url: null });
          }
        }
        if (annule) return;
        setPhotos(affichees);
        setMasque(false);
        setIndisponible(affichees.some((p) => p.url === null));
      } catch (e) {
        if (annule) return;
        if (e instanceof PhotoApiError && e.status === 403) {
          setMasque(true);
        } else {
          setIndisponible(true);
        }
      }
    })();
    return () => {
      annule = true;
      urls.forEach((u) => URL.revokeObjectURL(u));
    };
  }, [pieceId, rechargement]);

  async function ajouter() {
    if (!fichier) return;
    setErreur(null);
    setEnEnvoi(true);
    try {
      await uploaderPhoto(pieceId, type, fichier);
      setFichier(null);
      setRechargement((n) => n + 1);
    } catch (e) {
      setErreur(e instanceof PhotoApiError ? e.message : MESSAGE_HORS_CONNEXION);
    } finally {
      setEnEnvoi(false);
    }
  }

  if (masque) return null;

  const typesPresents = photos.map((p) => p.meta.type);
  const typeManquant = (['RECTO', 'VERSO'] as TypePhoto[]).find((t) => !typesPresents.includes(t));
  const peutAjouter =
    roleAgentCourant !== null && ROLES_AJOUT.includes(roleAgentCourant) && !!typeManquant;

  return (
    <div className="mt-5 border-t border-slate-200 pt-5">
      <h3 className="mb-3 text-sm font-bold text-primary-700">Photos du document</h3>
      {indisponible && <p className="alert-info">{MESSAGE_HORS_CONNEXION}</p>}
      <div className="flex flex-wrap gap-4">
        {photos.map(
          (p) =>
            p.url && (
              <figure key={p.meta.id} className="flex flex-col gap-1">
                <img
                  src={p.url}
                  alt={`Photo ${LIBELLES[p.meta.type]} du document`}
                  className="max-h-48 rounded-lg border border-slate-200"
                />
                <figcaption className="text-xs text-slate-500">{LIBELLES[p.meta.type]}</figcaption>
              </figure>
            ),
        )}
      </div>
      {peutAjouter && (
        <div className="mt-4 flex flex-col gap-3">
          <PhotoUpload
            fichier={fichier}
            type={type}
            onChange={setFichier}
            onTypeChange={setType}
            disabled={enEnvoi}
          />
          {erreur && (
            <p role="alert" className="alert-error">
              {erreur}
            </p>
          )}
          <button
            type="button"
            className="btn-outline self-start"
            disabled={!fichier || enEnvoi}
            onClick={ajouter}
          >
            Ajouter la photo
          </button>
        </div>
      )}
    </div>
  );
}

export default PhotosFiche;
