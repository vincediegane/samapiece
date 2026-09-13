import { useState } from 'react';
import AgentsPage from '../features/agents/AgentsPage';
import EnregistrementPiecePage from '../features/pieces/EnregistrementPiecePage';

type Onglet = 'pieces' | 'agents';

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
      </nav>
      {onglet === 'pieces' ? <EnregistrementPiecePage /> : <AgentsPage />}
    </div>
  );
}

export default App;
