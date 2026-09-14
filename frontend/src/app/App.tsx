import { useState } from 'react';
import AgentsPage from '../features/agents/AgentsPage';
import DashboardPage from '../features/dashboard/DashboardPage';
import EnregistrementPiecePage from '../features/pieces/EnregistrementPiecePage';
import RecherchePubliquePage from '../features/recherche-publique/RecherchePubliquePage';

type Onglet = 'pieces' | 'agents' | 'recherche' | 'dashboard';

function App() {
  const [onglet, setOnglet] = useState<Onglet>('pieces');

  return (
    <div>
      <nav>
        <button type="button" onClick={() => setOnglet('pieces')} disabled={onglet === 'pieces'}>
          Enregistrement pièces
        </button>
        <button type="button" onClick={() => setOnglet('agents')} disabled={onglet === 'agents'}>
          Gestion agents
        </button>
        <button
          type="button"
          onClick={() => setOnglet('recherche')}
          disabled={onglet === 'recherche'}
        >
          Recherche publique
        </button>
        <button type="button" onClick={() => setOnglet('dashboard')} disabled={onglet === 'dashboard'}>
          Tableau de bord
        </button>
      </nav>
      {onglet === 'pieces' ? (
        <EnregistrementPiecePage />
      ) : onglet === 'agents' ? (
        <AgentsPage />
      ) : onglet === 'recherche' ? (
        <RecherchePubliquePage />
      ) : (
        <DashboardPage />
      )}
    </div>
  );
}

export default App;
