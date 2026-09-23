import { useState } from 'react';
import type { FormEvent } from 'react';
import { login, AuthApiError } from './authApi';
import { enregistrerSession } from './session';

interface LoginPageProps {
  onConnexionReussie: () => void;
}

type ChampRequis = 'matricule' | 'motDePasse';

interface FormState {
  matricule: string;
  motDePasse: string;
}

const FORMULAIRE_INITIAL: FormState = {
  matricule: '',
  motDePasse: '',
};

function validerFormulaire(f: FormState): Partial<Record<ChampRequis, string>> {
  const erreurs: Partial<Record<ChampRequis, string>> = {};
  if (!f.matricule.trim()) erreurs.matricule = 'Le matricule est requis.';
  if (!f.motDePasse) erreurs.motDePasse = 'Le mot de passe est requis.';
  return erreurs;
}

function LoginPage({ onConnexionReussie }: LoginPageProps) {
  const [formulaire, setFormulaire] = useState<FormState>(FORMULAIRE_INITIAL);
  const [erreursValidation, setErreursValidation] = useState<Partial<Record<ChampRequis, string>>>(
    {},
  );
  const [erreurServeur, setErreurServeur] = useState<string | null>(null);
  const [enEnvoi, setEnEnvoi] = useState(false);

  async function soumettreFormulaire(evenement: FormEvent) {
    evenement.preventDefault();
    setErreurServeur(null);
    const erreurs = validerFormulaire(formulaire);
    setErreursValidation(erreurs);
    if (Object.keys(erreurs).length > 0) return;

    setEnEnvoi(true);
    try {
      const resultat = await login(formulaire.matricule.trim(), formulaire.motDePasse);
      enregistrerSession(resultat);
      onConnexionReussie();
    } catch (e) {
      setErreurServeur(
        e instanceof AuthApiError ? e.message : 'Une erreur est survenue, réessayez.',
      );
    } finally {
      setEnEnvoi(false);
    }
  }

  return (
    <main className="flex min-h-screen items-center justify-center bg-slate-50 px-4">
      <div className="w-full max-w-md rounded-2xl border border-slate-200 bg-white p-7 shadow-sm">
        <h1 className="page-title">Connexion agent</h1>

        {erreurServeur && (
          <p role="alert" className="alert-error">
            {erreurServeur}
          </p>
        )}

        <form onSubmit={soumettreFormulaire} className="flex flex-col gap-5">
          <label className="field">
            <span className="field-label">Matricule</span>
            <input
              type="text"
              className="field-input"
              value={formulaire.matricule}
              onChange={(e) => setFormulaire({ ...formulaire, matricule: e.target.value })}
            />
          </label>
          {erreursValidation.matricule && (
            <span role="alert" className="field-error -mt-3">
              {erreursValidation.matricule}
            </span>
          )}

          <label className="field">
            <span className="field-label">Mot de passe</span>
            <input
              type="password"
              className="field-input"
              value={formulaire.motDePasse}
              onChange={(e) => setFormulaire({ ...formulaire, motDePasse: e.target.value })}
            />
          </label>
          {erreursValidation.motDePasse && (
            <span role="alert" className="field-error -mt-3">
              {erreursValidation.motDePasse}
            </span>
          )}

          <button type="submit" className="btn-primary self-start" disabled={enEnvoi}>
            {enEnvoi ? 'Connexion…' : 'Se connecter'}
          </button>
        </form>
      </div>
    </main>
  );
}

export default LoginPage;
