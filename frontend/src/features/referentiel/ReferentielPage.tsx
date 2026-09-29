import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { creerPoste, creerRegion, listerRegions } from './referentielApi';
import type { CreerPostePayload, Region, TypePoste } from './types';

const HORAIRES_EXEMPLE = JSON.stringify(
  { lundi: { ouvert: true, debut: '08:00', fin: '18:00' } },
  null,
  2,
);

function ReferentielPage() {
  const [regions, setRegions] = useState<Region[]>([]);
  const [nomRegion, setNomRegion] = useState('');
  const [regionId, setRegionId] = useState('');
  const [nomPoste, setNomPoste] = useState('');
  const [type, setType] = useState<TypePoste>('POLICE');
  const [adresse, setAdresse] = useState('');
  const [telephone, setTelephone] = useState('');
  const [horaires, setHoraires] = useState(HORAIRES_EXEMPLE);
  const [latitude, setLatitude] = useState('');
  const [longitude, setLongitude] = useState('');
  const [erreur, setErreur] = useState<string | null>(null);
  const [succes, setSucces] = useState<string | null>(null);

  useEffect(() => {
    listerRegions()
      .then(setRegions)
      .catch(() => setErreur('Impossible de charger les régions (jeton absent ou expiré).'));
  }, []);

  async function soumettreRegion(evenement: FormEvent) {
    evenement.preventDefault();
    setErreur(null);
    setSucces(null);
    try {
      const region = await creerRegion({ nom: nomRegion.trim() });
      setRegions(await listerRegions());
      setRegionId(region.id);
      setNomRegion('');
      setSucces(`Région « ${region.nom} » créée.`);
    } catch (e) {
      setErreur(e instanceof Error ? e.message : 'Erreur inconnue lors de la création.');
    }
  }

  async function soumettrePoste(evenement: FormEvent) {
    evenement.preventDefault();
    setErreur(null);
    setSucces(null);

    let horairesObjet: unknown;
    try {
      horairesObjet = JSON.parse(horaires);
    } catch {
      setErreur('Horaires : JSON invalide');
      return;
    }
    if (
      horairesObjet === null ||
      typeof horairesObjet !== 'object' ||
      Array.isArray(horairesObjet)
    ) {
      setErreur('Horaires : JSON invalide');
      return;
    }

    const payload: CreerPostePayload = {
      regionId,
      nom: nomPoste.trim(),
      type,
      adresse: adresse.trim(),
      telephone: telephone.trim(),
      horaires: horairesObjet as Record<string, unknown>,
    };
    if (latitude.trim() !== '') payload.latitude = Number(latitude);
    if (longitude.trim() !== '') payload.longitude = Number(longitude);

    try {
      const poste = await creerPoste(payload);
      setNomPoste('');
      setAdresse('');
      setTelephone('');
      setHoraires(HORAIRES_EXEMPLE);
      setLatitude('');
      setLongitude('');
      setSucces(`Poste « ${poste.nom} » créé.`);
    } catch (e) {
      setErreur(e instanceof Error ? e.message : 'Erreur inconnue lors de la création.');
    }
  }

  return (
    <main className="mx-auto w-full max-w-6xl px-4 py-10 sm:px-6">
      <h1 className="page-title">Référentiel : régions et postes</h1>

      {erreur && (
        <p role="alert" className="alert-error">
          {erreur}
        </p>
      )}
      {succes && (
        <p
          role="status"
          className="mb-6 rounded-xl border border-primary-500/25 bg-primary-50 px-5 py-3.5 text-primary-700"
        >
          {succes}
        </p>
      )}

      <div className="flex flex-col gap-6 lg:flex-row lg:items-start">
        <form
          onSubmit={soumettreRegion}
          className="flex w-full flex-col gap-5 rounded-2xl border border-slate-200 bg-white p-6 lg:w-80 lg:flex-shrink-0"
        >
          <h2 className="text-[15px] font-bold text-primary-700">Créer une région</h2>
          <label className="field">
            <span className="field-label">Nom de la région</span>
            <input
              className="field-input"
              value={nomRegion}
              onChange={(e) => setNomRegion(e.target.value)}
              maxLength={255}
              required
            />
          </label>
          <button type="submit" className="btn-primary">
            Créer la région
          </button>
        </form>

        <form
          onSubmit={soumettrePoste}
          className="flex w-full flex-1 flex-col gap-5 rounded-2xl border border-slate-200 bg-white p-6"
        >
          <h2 className="text-[15px] font-bold text-primary-700">Créer un poste</h2>
          <label className="field">
            <span className="field-label">Région</span>
            <select
              className="field-input"
              value={regionId}
              onChange={(e) => setRegionId(e.target.value)}
              required
            >
              <option value="" disabled>
                Sélectionner une région
              </option>
              {regions.map((region) => (
                <option key={region.id} value={region.id}>
                  {region.nom}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            <span className="field-label">Nom du poste</span>
            <input
              className="field-input"
              value={nomPoste}
              onChange={(e) => setNomPoste(e.target.value)}
              maxLength={255}
              required
            />
          </label>
          <label className="field">
            <span className="field-label">Type</span>
            <select
              className="field-input"
              value={type}
              onChange={(e) => setType(e.target.value as TypePoste)}
            >
              <option value="POLICE">Police</option>
              <option value="GENDARMERIE">Gendarmerie</option>
            </select>
          </label>
          <label className="field">
            <span className="field-label">Adresse</span>
            <input
              className="field-input"
              value={adresse}
              onChange={(e) => setAdresse(e.target.value)}
              maxLength={500}
              required
            />
          </label>
          <label className="field">
            <span className="field-label">Téléphone</span>
            <input
              className="field-input"
              value={telephone}
              onChange={(e) => setTelephone(e.target.value)}
              maxLength={30}
              required
            />
          </label>
          <label className="field">
            <span className="field-label">Horaires (JSON)</span>
            <textarea
              className="field-input font-mono text-xs"
              rows={6}
              value={horaires}
              onChange={(e) => setHoraires(e.target.value)}
              required
            />
          </label>
          <div className="flex gap-4">
            <label className="field flex-1">
              <span className="field-label">Latitude (optionnel)</span>
              <input
                className="field-input"
                type="number"
                step="any"
                min={-90}
                max={90}
                value={latitude}
                onChange={(e) => setLatitude(e.target.value)}
              />
            </label>
            <label className="field flex-1">
              <span className="field-label">Longitude (optionnel)</span>
              <input
                className="field-input"
                type="number"
                step="any"
                min={-180}
                max={180}
                value={longitude}
                onChange={(e) => setLongitude(e.target.value)}
              />
            </label>
          </div>
          <button type="submit" className="btn-primary">
            Créer le poste
          </button>
        </form>
      </div>
    </main>
  );
}

export default ReferentielPage;
