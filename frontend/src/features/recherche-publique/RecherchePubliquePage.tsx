import { useState } from 'react';
import type { FormEvent } from 'react';
import { CaptchaRequisApiError, obtenirDefiCaptcha, rechercher } from './recherchePubliqueApi';
import type { CaptchaDefi, RecherchePubliqueRequest, RecherchePubliqueResponse } from './types';
import { TYPE_DOCUMENT_LABELS } from '../pieces/types';
import type { TypeDocument } from '../pieces/types';
import { IconCheck, IconDocument, IconSearch } from '../../shared/icons';

interface FormState {
  typeDocument: TypeDocument | '';
  nomTitulaire: string;
  prenomTitulaire: string;
  numeroDocument: string;
  dateNaissanceTitulaire: string;
}

const FORMULAIRE_INITIAL: FormState = {
  typeDocument: '',
  nomTitulaire: '',
  prenomTitulaire: '',
  numeroDocument: '',
  dateNaissanceTitulaire: '',
};

interface ErreursValidation {
  typeDocument?: string;
  nomTitulaire?: string;
  discriminant?: string;
}

function validerFormulaire(f: FormState): ErreursValidation {
  const erreurs: ErreursValidation = {};
  if (!f.typeDocument) erreurs.typeDocument = 'Le type de document est requis.';
  if (!f.nomTitulaire.trim()) erreurs.nomTitulaire = 'Le nom du titulaire est requis.';
  if (!f.numeroDocument.trim() && !f.dateNaissanceTitulaire) {
    erreurs.discriminant = 'Renseignez le numéro du document ou la date de naissance du titulaire.';
  }
  return erreurs;
}

function RecherchePubliquePage() {
  const [formulaire, setFormulaire] = useState<FormState>(FORMULAIRE_INITIAL);
  const [erreursValidation, setErreursValidation] = useState<ErreursValidation>({});
  const [erreurServeur, setErreurServeur] = useState<string | null>(null);
  const [enEnvoi, setEnEnvoi] = useState(false);
  const [resultat, setResultat] = useState<RecherchePubliqueResponse | null>(null);
  const [alerteProposee, setAlerteProposee] = useState(false);
  const [defiCaptcha, setDefiCaptcha] = useState<CaptchaDefi | null>(null);
  const [reponseCaptcha, setReponseCaptcha] = useState('');
  const [payloadEnAttente, setPayloadEnAttente] = useState<RecherchePubliqueRequest | null>(null);

  async function chargerNouveauDefi() {
    try {
      const defi = await obtenirDefiCaptcha();
      setDefiCaptcha(defi);
      setReponseCaptcha('');
    } catch {
      setErreurServeur('Impossible de charger la vérification de sécurité. Réessayez plus tard.');
      setDefiCaptcha(null);
      setPayloadEnAttente(null);
    }
  }

  async function soumettreFormulaire(evenement: FormEvent) {
    evenement.preventDefault();
    const erreurs = validerFormulaire(formulaire);
    setErreursValidation(erreurs);
    if (Object.keys(erreurs).length > 0) return;

    setErreurServeur(null);
    setEnEnvoi(true);
    const payload: RecherchePubliqueRequest = {
      typeDocument: formulaire.typeDocument as TypeDocument,
      nomTitulaire: formulaire.nomTitulaire.trim(),
      prenomTitulaire: formulaire.prenomTitulaire.trim() || null,
      numeroDocument: formulaire.numeroDocument.trim() || null,
      dateNaissanceTitulaire: formulaire.dateNaissanceTitulaire || null,
    };
    try {
      const reponse = await rechercher(payload);
      setResultat(reponse);
      setAlerteProposee(false);
    } catch (e) {
      if (e instanceof CaptchaRequisApiError) {
        setPayloadEnAttente(payload);
        setErreurServeur('Vérification supplémentaire requise avant de poursuivre la recherche.');
        await chargerNouveauDefi();
      } else {
        setErreurServeur(e instanceof Error ? e.message : 'Erreur inconnue lors de la recherche.');
      }
    } finally {
      setEnEnvoi(false);
    }
  }

  async function soumettreReponseCaptcha(evenement: FormEvent) {
    evenement.preventDefault();
    if (!defiCaptcha || !payloadEnAttente) return;

    setErreurServeur(null);
    setEnEnvoi(true);
    try {
      const reponse = await rechercher(payloadEnAttente, {
        captchaToken: defiCaptcha.captchaToken,
        captchaReponse: reponseCaptcha.trim(),
      });
      setResultat(reponse);
      setAlerteProposee(false);
      setDefiCaptcha(null);
      setReponseCaptcha('');
      setPayloadEnAttente(null);
    } catch (e) {
      if (e instanceof CaptchaRequisApiError) {
        setErreurServeur('Réponse incorrecte ou expirée. Une nouvelle question a été générée.');
        await chargerNouveauDefi();
      } else {
        setErreurServeur(e instanceof Error ? e.message : 'Erreur inconnue lors de la recherche.');
      }
    } finally {
      setEnEnvoi(false);
    }
  }

  return (
    <main className="mx-auto w-full max-w-2xl px-4 py-10 sm:px-6">
      <h1 className="page-title">Rechercher ma pièce</h1>
      <p className="mb-7 text-[15px] leading-relaxed text-slate-600">
        Renseignez votre nom et au moins le numéro du document ou votre date de naissance. Aucune
        inscription n&apos;est nécessaire.
      </p>

      {erreurServeur && (
        <p role="alert" className="alert-error">
          {erreurServeur}
        </p>
      )}

      {defiCaptcha && (
        <section
          aria-label="Vérification de sécurité"
          className="mb-6 rounded-2xl border border-slate-200 bg-white p-7 shadow-sm"
        >
          <form onSubmit={soumettreReponseCaptcha} className="flex flex-col gap-5">
            <label className="field">
              <span className="field-label">{defiCaptcha.question}</span>
              <input
                type="text"
                className="field-input"
                value={reponseCaptcha}
                onChange={(e) => setReponseCaptcha(e.target.value)}
              />
            </label>
            <button type="submit" className="btn-primary self-start" disabled={enEnvoi}>
              Valider
            </button>
          </form>
        </section>
      )}

      {resultat &&
        (resultat.trouve ? (
          <section
            aria-label="Résultat de la recherche"
            className="mb-6 rounded-2xl border border-primary-500/25 bg-white p-7 shadow-sm"
          >
            <div className="mb-4 flex items-center gap-3">
              <span className="flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-full bg-primary-50 text-primary-500">
                <IconCheck width={20} height={20} />
              </span>
              <h2 className="text-lg font-extrabold text-primary-700">
                Bonne nouvelle, votre pièce a été retrouvée !
              </h2>
            </div>
            <div className="mb-4 grid gap-4 rounded-xl bg-slate-50 p-5 sm:grid-cols-2">
              <p>
                <span className="mb-0.5 block text-xs font-semibold uppercase tracking-wide text-slate-400">
                  Type de document
                </span>
                {resultat.typeDocument ? TYPE_DOCUMENT_LABELS[resultat.typeDocument] : ''}
              </p>
              <p>
                <span className="mb-0.5 block text-xs font-semibold uppercase tracking-wide text-slate-400">
                  Poste
                </span>
                {resultat.poste?.nom}
              </p>
              <p>
                <span className="mb-0.5 block text-xs font-semibold uppercase tracking-wide text-slate-400">
                  Adresse
                </span>
                {resultat.poste?.adresse}
              </p>
              <p>
                <span className="mb-0.5 block text-xs font-semibold uppercase tracking-wide text-slate-400">
                  Horaires
                </span>
                {resultat.poste?.horaires}
              </p>
              <p>
                <span className="mb-0.5 block text-xs font-semibold uppercase tracking-wide text-slate-400">
                  Téléphone
                </span>
                {resultat.poste?.telephone}
              </p>
              <p>
                <span className="mb-0.5 block text-xs font-semibold uppercase tracking-wide text-slate-400">
                  Référence de dossier
                </span>
                {resultat.referenceDossier}
              </p>
            </div>
            <p className="text-sm text-slate-500">
              Présentez-vous au poste avec une pièce justificative de votre identité pour récupérer
              votre document.
            </p>
          </section>
        ) : (
          <section
            aria-label="Aucun résultat"
            className="mb-6 rounded-2xl border border-slate-200 bg-white p-7"
          >
            <p className="mb-3">
              Aucune pièce correspondant à ces critères n&apos;a été retrouvée.
            </p>
            {alerteProposee ? (
              <p>Cette fonctionnalité arrive bientôt.</p>
            ) : (
              <button type="button" className="btn-outline" onClick={() => setAlerteProposee(true)}>
                Recevoir une alerte si cette pièce est déposée
              </button>
            )}
          </section>
        ))}

      <form
        onSubmit={soumettreFormulaire}
        className="rounded-2xl border border-slate-200 bg-white p-7 shadow-sm flex flex-col gap-5"
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
              disabled={enEnvoi || defiCaptcha !== null}
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
            disabled={enEnvoi || defiCaptcha !== null}
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
            disabled={enEnvoi || defiCaptcha !== null}
          />
        </label>

        <label className="field">
          <span className="field-label">Numéro du document</span>
          <input
            type="text"
            className="field-input"
            value={formulaire.numeroDocument}
            onChange={(e) => setFormulaire({ ...formulaire, numeroDocument: e.target.value })}
            disabled={enEnvoi || defiCaptcha !== null}
          />
        </label>

        <label className="field">
          <span className="field-label">Date de naissance du titulaire</span>
          <input
            type="date"
            className="field-input"
            value={formulaire.dateNaissanceTitulaire}
            onChange={(e) =>
              setFormulaire({ ...formulaire, dateNaissanceTitulaire: e.target.value })
            }
            disabled={enEnvoi || defiCaptcha !== null}
          />
        </label>
        {erreursValidation.discriminant && (
          <span role="alert" className="field-error">
            {erreursValidation.discriminant}
          </span>
        )}

        <button
          type="submit"
          className="btn-primary flex items-center gap-2 self-start"
          disabled={enEnvoi || defiCaptcha !== null}
        >
          <IconSearch width={16} height={16} />
          Rechercher
        </button>
      </form>
    </main>
  );
}

export default RecherchePubliquePage;
