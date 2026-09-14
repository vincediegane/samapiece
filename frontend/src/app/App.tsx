import { useState } from 'react';
import AgentsPage from '../features/agents/AgentsPage';
import EnregistrementPiecePage from '../features/pieces/EnregistrementPiecePage';
import RecherchePubliquePage from '../features/recherche-publique/RecherchePubliquePage';

type Onglet = 'pieces' | 'agents' | 'recherche';

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
      </nav>
      {onglet === 'pieces' ? (
        <EnregistrementPiecePage />
      ) : onglet === 'agents' ? (
        <AgentsPage />
      ) : (
        <RecherchePubliquePage />
      )}
    </div>
  );
}

export default App;
