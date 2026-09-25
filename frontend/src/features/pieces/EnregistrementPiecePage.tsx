import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { creerPiece, PieceApiError } from './piecesApi';
import type { CreerPieceRequest, EtatDocumentOption, PieceResponse, TypeDocument } from './types';
import { ETAT_DOCUMENT_OPTIONS, TYPE_DOCUMENT_LABELS } from './types';
import { mettreEnFile } from '../../shared/offline/fileSynchronisation';
import FileAttenteSynchronisation from './FileAttenteSynchronisation';
import FichePieceCard from './FichePieceCard';
import { recupererAgentCourant } from '../dashboard/dashboardApi';
import { IconCheck, IconDocument } from '../../shared/icons';

type ChampRequis =
  'typeDocument' | 'nomTitulaire' | 'prenomTitulaire' | 'numeroDocument' | 'dateDepot';

interface FormState {
  typeDocument: TypeDocument | '';
  nomTitulaire: string;
  prenomTitulaire: string;
  numeroDocument: string;
  dateNaissanceTitulaire: string;
  dateDepot: string;
  etatDocument: EtatDocumentOption | '';
  remarques: string;
}

const FORMULAIRE_INITIAL: FormState = {
  typeDocument: '',
  nomTitulaire: '',
  prenomTitulaire: '',
  numeroDocument: '',
  dateNaissanceTitulaire: '',
  dateDepot: '',
  etatDocument: '',
  remarques: '',
};

function validerFormulaire(f: FormState): Partial<Record<ChampRequis, string>> {
  const erreurs: Partial<Record<ChampRequis, string>> = {};
  if (!f.typeDocument) erreurs.typeDocument = 'Le type de document est requis.';
  if (!f.nomTitulaire.trim()) erreurs.nomTitulaire = 'Le nom du titulaire est requis.';
  if (!f.prenomTitulaire.trim()) erreurs.prenomTitulaire = 'Le prénom du titulaire est requis.';
  if (!f.numeroDocument.trim()) erreurs.numeroDocument = 'Le numéro du document est requis.';
  if (!f.dateDepot) erreurs.dateDepot = 'La date de dépôt est requise.';
  return erreurs;
}

function EnregistrementPiecePage() {
  const [formulaire, setFormulaire] = useState<FormState>(FORMULAIRE_INITIAL);
  const [erreursValidation, setErreursValidation] = useState<Partial<Record<ChampRequis, string>>>(
    {},
  );
  const [erreurServeur, setErreurServeur] = useState<string | null>(null);
  const [messageMiseEnFile, setMessageMiseEnFile] = useState<string | null>(null);
  const [enEnvoi, setEnEnvoi] = useState(false);
  const [recu, setRecu] = useState<PieceResponse | null>(null);
  const [roleAgentCourant, setRoleAgentCourant] = useState<string | null>(null);

  useEffect(() => {
    recupererAgentCourant()
      .then((a) => setRoleAgentCourant(a.role))
      .catch(() => setRoleAgentCourant(null));
  }, []);

  async function soumettreFormulaire(evenement: FormEvent) {
    evenement.preventDefault();
    setErreurServeur(null);
    setMessageMiseEnFile(null);
    const erreurs = validerFormulaire(formulaire);
    setErreursValidation(erreurs);
    if (Object.keys(erreurs).length > 0) return;

    setEnEnvoi(true);
    const payload: CreerPieceRequest = {
      typeDocument: formulaire.typeDocument as TypeDocument,
      nomTitulaire: formulaire.nomTitulaire.trim(),
      prenomTitulaire: formulaire.prenomTitulaire.trim(),
      numeroDocument: formulaire.numeroDocument.trim(),
      dateNaissanceTitulaire: formulaire.dateNaissanceTitulaire || null,
      dateDepot: formulaire.dateDepot,
      etatDocument: formulaire.etatDocument || null,
      remarques: formulaire.remarques.trim() || null,
    };
    try {
      const resultat = await creerPiece(payload);
      setRecu(resultat);
      setFormulaire(FORMULAIRE_INITIAL);
      setErreursValidation({});
    } catch (e) {
      const estEchecReseau = !(e instanceof PieceApiError) || !navigator.onLine;
      if (estEchecReseau) {
        await mettreEnFile(payload);
        setFormulaire(FORMULAIRE_INITIAL);
        setErreursValidation({});
        setMessageMiseEnFile(
          'Pas de connexion : la fiche a été enregistrée localement, elle sera synchronisée automatiquement.',
        );
      } else {
        setErreurServeur(
          e instanceof Error ? e.message : 'Erreur inconnue lors de l’enregistrement.',
        );
      }
    } finally {
      setEnEnvoi(false);
    }
  }

  return (
    <main className="mx-auto w-full max-w-6xl px-4 py-10 sm:px-6">
      <h1 className="page-title">Enregistrement d&apos;une pièce retrouvée</h1>

      {erreurServeur && (
        <p role="alert" className="alert-error">
          {erreurServeur}
        </p>
      )}

      {messageMiseEnFile && <p className="alert-info">{messageMiseEnFile}</p>}

      <div className="flex flex-col gap-6 lg:flex-row lg:items-start">
        <form
          onSubmit={soumettreFormulaire}
          className="flex flex-1 flex-col gap-5 rounded-2xl border border-slate-200 bg-white p-7 shadow-sm lg:max-w-2xl"
        >
        <label className="field">
          <span className="field-label">Type de document</span>
          <div className="relative">
            <IconDocument
              width={16}
              height={16}
              className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-slate-400"
            />
            <select
              className="field-input pl-9"
              value={formulaire.typeDocument}
              onChange={(e) =>
                setFormulaire({ ...formulaire, typeDocument: e.target.value as TypeDocument })
              }
            >
              <option value="" disabled>
                Sélectionner un type
              </option>
              {(Object.keys(TYPE_DOCUMENT_LABELS) as TypeDocument[]).map((type) => (
                <option key={type} value={type}>
                  {TYPE_DOCUMENT_LABELS[type]}
                </option>
              ))}
            </select>
          </div>
        </label>
        {erreursValidation.typeDocument && (
          <span role="alert" className="field-error -mt-3">
            {erreursValidation.typeDocument}
          </span>
        )}

        <label className="field">
          <span className="field-label">Nom du titulaire</span>
          <input
            type="text"
            className="field-input"
            value={formulaire.nomTitulaire}
            onChange={(e) => setFormulaire({ ...formulaire, nomTitulaire: e.target.value })}
          />
        </label>
        {erreursValidation.nomTitulaire && (
          <span role="alert" className="field-error -mt-3">
            {erreursValidation.nomTitulaire}
          </span>
        )}

        <label className="field">
          <span className="field-label">Prénom du titulaire</span>
          <input
            type="text"
            className="field-input"
            value={formulaire.prenomTitulaire}
            onChange={(e) => setFormulaire({ ...formulaire, prenomTitulaire: e.target.value })}
          />
        </label>
        {erreursValidation.prenomTitulaire && (
          <span role="alert" className="field-error -mt-3">
            {erreursValidation.prenomTitulaire}
          </span>
        )}

        <label className="field">
          <span className="field-label">Numéro du document</span>
          <input
            type="text"
            className="field-input"
            value={formulaire.numeroDocument}
            onChange={(e) => setFormulaire({ ...formulaire, numeroDocument: e.target.value })}
          />
        </label>
        {erreursValidation.numeroDocument && (
          <span role="alert" className="field-error -mt-3">
            {erreursValidation.numeroDocument}
          </span>
        )}

        <div className="grid gap-5 sm:grid-cols-2">
          <label className="field">
            <span className="field-label">Date de naissance du titulaire</span>
            <input
              type="date"
              className="field-input"
              value={formulaire.dateNaissanceTitulaire}
              onChange={(e) =>
                setFormulaire({ ...formulaire, dateNaissanceTitulaire: e.target.value })
              }
            />
          </label>

          <label className="field">
            <span className="field-label">Date de dépôt</span>
            <input
              type="date"
              className="field-input"
              value={formulaire.dateDepot}
              onChange={(e) => setFormulaire({ ...formulaire, dateDepot: e.target.value })}
            />
          </label>
        </div>
        {erreursValidation.dateDepot && (
          <span role="alert" className="field-error -mt-3">
            {erreursValidation.dateDepot}
          </span>
        )}

        <label className="field">
          <span className="field-label">État du document</span>
          <select
            className="field-input"
            value={formulaire.etatDocument}
            onChange={(e) =>
              setFormulaire({ ...formulaire, etatDocument: e.target.value as EtatDocumentOption })
            }
          >
            <option value=""></option>
            {ETAT_DOCUMENT_OPTIONS.map((etat) => (
              <option key={etat} value={etat}>
                {etat}
              </option>
            ))}
          </select>
        </label>

        <label className="field">
          <span className="field-label">Remarques</span>
          <textarea
            className="field-input min-h-20 resize-y"
            value={formulaire.remarques}
            onChange={(e) => setFormulaire({ ...formulaire, remarques: e.target.value })}
          />
        </label>

        <button type="submit" className="btn-primary self-start" disabled={enEnvoi}>
          Enregistrer la pièce
        </button>
        </form>

        <div className="flex w-full flex-col gap-5 lg:w-80 lg:flex-shrink-0">
          {recu ? (
            <section
              aria-label="Reçu d'enregistrement"
              className="rounded-2xl border border-primary-500/25 bg-white p-6 shadow-sm"
            >
              <div className="mb-4 flex items-center gap-2.5">
                <span className="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-full bg-primary-50 text-primary-500">
                  <IconCheck width={15} height={15} />
                </span>
                <h2 className="text-[15px] font-bold text-primary-700">Fiche enregistrée</h2>
              </div>
              <FichePieceCard
                piece={recu}
                roleAgentCourant={roleAgentCourant}
                onMisAJour={setRecu}
              />
            </section>
          ) : (
            <section className="rounded-2xl border border-slate-200 bg-white p-6">
              <h2 className="mb-3 text-sm font-bold text-primary-700">Avant d&apos;enregistrer</h2>
              <ul className="flex flex-col gap-2.5 text-sm text-slate-700">
                <li className="flex gap-2">
                  <span className="text-primary-500">•</span>
                  Vérifiez l&apos;orthographe du nom exactement comme sur le document.
                </li>
                <li className="flex gap-2">
                  <span className="text-primary-500">•</span>
                  Notez précisément l&apos;état du document.
                </li>
                <li className="flex gap-2">
                  <span className="text-primary-500">•</span>
                  En cas de doute sur le type, choisissez « Autre ».
                </li>
              </ul>
            </section>
          )}

          <FileAttenteSynchronisation />
        </div>
      </div>
    </main>
  );
}

export default EnregistrementPiecePage;
