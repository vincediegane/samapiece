import { useState } from 'react';
import AgentsPage from '../features/agents/AgentsPage';
import DashboardPage from '../features/dashboard/DashboardPage';
import EnregistrementPiecePage from '../features/pieces/EnregistrementPiecePage';
import RecherchePubliquePage from '../features/recherche-publique/RecherchePubliquePage';
import HomePage from '../features/home/HomePage';
import LoginPage from '../features/auth/LoginPage';
import { estSessionValide } from '../features/auth/session';
import PublicHeader from '../shared/layout/PublicHeader';
import AgentShell from '../shared/layout/AgentShell';
import type { OngletAgent } from '../shared/layout/AgentShell';

type Onglet = 'accueil' | 'recherche' | 'connexion' | OngletAgent;

const ONGLETS_AGENT: OngletAgent[] = ['pieces', 'dashboard', 'agents'];

function estOngletAgent(onglet: Onglet): onglet is OngletAgent {
  return (ONGLETS_AGENT as Onglet[]).includes(onglet);
}

function App() {
  const [onglet, setOnglet] = useState<Onglet>('accueil');

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
        onNaviguer={setOnglet}
        onRetourPublic={() => setOnglet('accueil')}
        onDeconnexion={() => setOnglet('connexion')}
      >
        {onglet === 'pieces' && <EnregistrementPiecePage />}
        {onglet === 'dashboard' && <DashboardPage />}
        {onglet === 'agents' && <AgentsPage />}
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
