import { IconCheck, IconDocument, IconMapPin, IconSearch } from '../../shared/icons';

const DOCUMENTS_PRIS_EN_CHARGE = [
  "Carte Nationale d'Identité",
  'Passeport',
  'Permis de conduire',
  "Carte d'électeur",
  'Extrait de naissance',
  'Carte grise',
  'Carte consulaire',
  'Autre document officiel',
];

interface HomePageProps {
  onRechercher: () => void;
  onEspaceAgent: () => void;
}

function HomePage({ onRechercher, onEspaceAgent }: HomePageProps) {
  return (
    <main>
      <section className="mx-auto flex max-w-6xl flex-col items-center gap-12 px-4 py-16 sm:px-10 lg:flex-row lg:py-20">
        <div className="flex-1">
          <span className="mb-5 inline-block rounded-full bg-primary-50 px-3.5 py-1.5 text-xs font-bold uppercase tracking-wide text-primary-700">
            Service de restitution de pièces
          </span>
          <h1 className="mb-5 text-4xl font-extrabold leading-tight tracking-tight text-slate-900 sm:text-5xl">
            Votre pièce d&apos;identité perdue vous attend peut-être déjà.
          </h1>
          <p className="mb-8 max-w-xl text-lg leading-relaxed text-slate-600">
            SamaPièce recense les cartes d&apos;identité, passeports et autres documents retrouvés
            dans les postes partenaires du Sénégal. Une recherche suffit pour savoir où récupérer
            le vôtre.
          </p>
          <div className="mb-7 flex flex-wrap items-center gap-6">
            <button
              type="button"
              onClick={onRechercher}
              className="public-cta px-8 py-4 text-base"
            >
              <IconSearch width={18} height={18} />
              Rechercher ma pièce
            </button>
            <button
              type="button"
              onClick={onEspaceAgent}
              className="text-[15px] font-semibold text-primary-500 hover:underline"
            >
              Je suis agent, me connecter →
            </button>
          </div>
          <div className="flex flex-wrap gap-5 text-sm text-slate-500">
            <span className="flex items-center gap-1.5">
              <IconCheck width={14} height={14} className="text-primary-500" />
              Gratuit
            </span>
            <span className="flex items-center gap-1.5">
              <IconCheck width={14} height={14} className="text-primary-500" />
              Sans création de compte
            </span>
            <span className="flex items-center gap-1.5">
              <IconCheck width={14} height={14} className="text-primary-500" />
              Résultat immédiat
            </span>
          </div>
        </div>
        <div className="flex h-72 w-full flex-shrink-0 items-center justify-center rounded-3xl bg-primary-50 lg:w-96">
          <svg width="220" height="220" viewBox="0 0 260 260" fill="none" aria-hidden="true">
            <rect
              x="40"
              y="70"
              width="150"
              height="95"
              rx="10"
              fill="#ffffff"
              stroke="#cbd5e1"
              strokeWidth="1.5"
              transform="rotate(-8 115 117)"
            />
            <rect
              x="60"
              y="55"
              width="150"
              height="95"
              rx="10"
              fill="#ffffff"
              stroke="#94a3b8"
              strokeWidth="1.5"
              transform="rotate(4 135 102)"
            />
            <rect x="55" y="80" width="150" height="95" rx="10" fill="#00663d" />
            <circle cx="85" cy="105" r="14" fill="#ffffff" opacity="0.9" />
            <rect x="108" y="98" width="70" height="7" rx="3" fill="#ffffff" opacity="0.85" />
            <rect x="108" y="112" width="50" height="6" rx="3" fill="#ffffff" opacity="0.6" />
            <rect x="70" y="140" width="120" height="6" rx="3" fill="#ffffff" opacity="0.5" />
            <circle cx="185" cy="185" r="32" fill="#f7b500" />
            <circle cx="182" cy="182" r="20" fill="none" stroke="#004d2e" strokeWidth="4" />
            <line x1="196" y1="196" x2="212" y2="212" stroke="#004d2e" strokeWidth="5" strokeLinecap="round" />
          </svg>
        </div>
      </section>

      <section className="border-t border-slate-200 bg-white py-16">
        <div className="mx-auto max-w-6xl px-4 sm:px-10">
          <span className="mb-3 block text-xs font-bold uppercase tracking-wide text-primary-500">
            Comment ça marche
          </span>
          <h2 className="mb-10 max-w-xl text-3xl font-extrabold text-slate-900">
            Trois étapes, et vos papiers reviennent chez vous
          </h2>
          <div className="grid gap-10 sm:grid-cols-3">
            <div className="flex flex-col gap-3">
              <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-primary-50 text-primary-500">
                <IconDocument width={26} height={26} />
              </div>
              <span className="text-xs font-bold text-slate-400">ÉTAPE 1</span>
              <h3 className="text-lg font-bold text-slate-900">Un agent enregistre le dépôt</h3>
              <p className="text-sm leading-relaxed text-slate-600">
                Dès qu&apos;une pièce est retrouvée, l&apos;agent du poste l&apos;enregistre avec
                les informations essentielles du titulaire.
              </p>
            </div>
            <div className="flex flex-col gap-3">
              <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-primary-50 text-primary-500">
                <IconSearch width={26} height={26} />
              </div>
              <span className="text-xs font-bold text-slate-400">ÉTAPE 2</span>
              <h3 className="text-lg font-bold text-slate-900">Vous lancez une recherche</h3>
              <p className="text-sm leading-relaxed text-slate-600">
                Indiquez votre nom et le numéro du document, ou votre date de naissance, depuis
                n&apos;importe quel appareil.
              </p>
            </div>
            <div className="flex flex-col gap-3">
              <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-primary-50 text-primary-500">
                <IconMapPin width={26} height={26} />
              </div>
              <span className="text-xs font-bold text-slate-400">ÉTAPE 3</span>
              <h3 className="text-lg font-bold text-slate-900">Vous récupérez votre pièce</h3>
              <p className="text-sm leading-relaxed text-slate-600">
                Rendez-vous au poste indiqué, muni d&apos;une pièce justificative, pour la
                récupérer.
              </p>
            </div>
          </div>
        </div>
      </section>

      <section className="border-t border-slate-200 bg-slate-50 py-12">
        <div className="mx-auto max-w-6xl px-4 text-center sm:px-10">
          <h3 className="mb-5 text-sm font-bold text-slate-700">Documents pris en charge</h3>
          <div className="flex flex-wrap justify-center gap-2.5">
            {DOCUMENTS_PRIS_EN_CHARGE.map((document) => (
              <span
                key={document}
                className="rounded-full border border-slate-200 bg-white px-4 py-2 text-sm font-medium text-slate-700"
              >
                {document}
              </span>
            ))}
          </div>
        </div>
      </section>

      <footer className="bg-primary-700 px-4 py-10 text-white sm:px-10">
        <div className="mx-auto flex max-w-6xl flex-col gap-7 border-b border-white/15 pb-7 sm:flex-row sm:justify-between">
          <div className="max-w-xs">
            <span className="text-lg font-bold">SamaPièce</span>
            <p className="mt-2.5 text-sm leading-relaxed text-white/70">
              Le service de restitution des pièces d&apos;identité retrouvées dans les postes
              partenaires du Sénégal.
            </p>
          </div>
          <div className="flex gap-14">
            <div className="flex flex-col gap-2.5">
              <span className="mb-1 text-xs font-bold uppercase tracking-wide text-white/50">
                Citoyens
              </span>
              <button type="button" onClick={onRechercher} className="text-left text-sm text-white/85 hover:text-white">
                Rechercher ma pièce
              </button>
            </div>
            <div className="flex flex-col gap-2.5">
              <span className="mb-1 text-xs font-bold uppercase tracking-wide text-white/50">
                Professionnels
              </span>
              <button type="button" onClick={onEspaceAgent} className="text-left text-sm text-white/85 hover:text-white">
                Espace agent
              </button>
            </div>
          </div>
        </div>
        <p className="mx-auto max-w-6xl pt-5 text-xs text-white/50">© 2026 SamaPièce — Sénégal</p>
      </footer>
    </main>
  );
}

export default HomePage;
