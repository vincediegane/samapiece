import { useState } from 'react';
import AgentsPage from '../features/agents/AgentsPage';
import DashboardPage from '../features/dashboard/DashboardPage';
import EnregistrementPiecePage from '../features/pieces/EnregistrementPiecePage';
import RecherchePubliquePage from '../features/recherche-publique/RecherchePubliquePage';
import HomePage from '../features/home/HomePage';
import PublicHeader from '../shared/layout/PublicHeader';
import AgentShell from '../shared/layout/AgentShell';
import type { OngletAgent } from '../shared/layout/AgentShell';

type Onglet = 'accueil' | 'recherche' | OngletAgent;

const ONGLETS_AGENT: OngletAgent[] = ['pieces', 'dashboard', 'agents'];

function estOngletAgent(onglet: Onglet): onglet is OngletAgent {
  return (ONGLETS_AGENT as Onglet[]).includes(onglet);
}

function App() {
  const [onglet, setOnglet] = useState<Onglet>('accueil');

  if (estOngletAgent(onglet)) {
    return (
      <AgentShell
        actif={onglet}
        onNaviguer={setOnglet}
        onRetourPublic={() => setOnglet('accueil')}
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
        onEspaceAgent={() => setOnglet('pieces')}
      />
      {onglet === 'accueil' ? (
        <HomePage
          onRechercher={() => setOnglet('recherche')}
          onEspaceAgent={() => setOnglet('pieces')}
        />
      ) : (
        <RecherchePubliquePage />
      )}
    </div>
  );
}

export default App;
