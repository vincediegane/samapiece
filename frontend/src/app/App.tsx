import { useState } from 'react';
import AuditPage from '../features/audit/AuditPage';
import ReferentielPage from '../features/referentiel/ReferentielPage';
import AgentsPage from '../features/agents/AgentsPage';
import DashboardPage from '../features/dashboard/DashboardPage';
import VueMultiPostePage from '../features/dashboard/VueMultiPostePage';
import EnregistrementPiecePage from '../features/pieces/EnregistrementPiecePage';
import ConsulterFichePage from '../features/pieces/ConsulterFichePage';
import PiecesEnStockPage from '../features/pieces/PiecesEnStockPage';
import RecherchePubliquePage from '../features/recherche-publique/RecherchePubliquePage';
import HomePage from '../features/home/HomePage';
import LoginPage from '../features/auth/LoginPage';
import { estSessionValide } from '../features/auth/session';
import PublicHeader from '../shared/layout/PublicHeader';
import AgentShell from '../shared/layout/AgentShell';
import type { OngletAgent } from '../shared/layout/AgentShell';

type Onglet = 'accueil' | 'recherche' | 'connexion' | OngletAgent;

const ONGLETS_AGENT: OngletAgent[] = ['pieces', 'fiche', 'dashboard', 'stock', 'agents', 'audit', 'referentiel', 'vue-multi-poste'];

function estOngletAgent(onglet: Onglet): onglet is OngletAgent {
  return (ONGLETS_AGENT as Onglet[]).includes(onglet);
}

function App() {
  const [onglet, setOnglet] = useState<Onglet>('accueil');
  const [pieceAOuvrir, setPieceAOuvrir] = useState<string | null>(null);

  function naviguerAgent(cible: OngletAgent) {
    if (cible === 'fiche') setPieceAOuvrir(null);
    setOnglet(cible);
  }

  function irVersEspaceAgent() {
    setOnglet(estSessionValide() ? 'pieces' : 'connexion');
  }

  if (onglet === 'connexion') {
    return <LoginPage onConnexionReussie={() => setOnglet('pieces')} />;
  }

  if (estOngletAgent(onglet)) {
    return (
      <AgentShell
        actif={onglet}
        onNaviguer={naviguerAgent}
        onRetourPublic={() => setOnglet('accueil')}
        onDeconnexion={() => setOnglet('connexion')}
      >
        {onglet === 'pieces' && <EnregistrementPiecePage />}
        {onglet === 'fiche' && (
          <ConsulterFichePage key={pieceAOuvrir ?? 'manuel'} idInitial={pieceAOuvrir ?? undefined} />
        )}
        {onglet === 'dashboard' && <DashboardPage />}
        {onglet === 'stock' && (
          <PiecesEnStockPage onOuvrirFiche={(id) => { setPieceAOuvrir(id); setOnglet('fiche'); }} />
        )}
        {onglet === 'agents' && <AgentsPage />}
        {onglet === 'audit' && <AuditPage />}
        {onglet === 'referentiel' && <ReferentielPage />}
        {onglet === 'vue-multi-poste' && <VueMultiPostePage />}
      </AgentShell>
    );
  }

  return (
    <div>
      <PublicHeader
        page={onglet}
        onNaviguerAccueil={() => setOnglet('accueil')}
        onNaviguerRecherche={() => setOnglet('recherche')}
        onEspaceAgent={irVersEspaceAgent}
      />
      {onglet === 'accueil' ? (
        <HomePage onRechercher={() => setOnglet('recherche')} onEspaceAgent={irVersEspaceAgent} />
      ) : (
        <RecherchePubliquePage />
      )}
    </div>
  );
}

export default App;
