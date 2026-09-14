import type { AgentCourant, StatistiquesPoste } from './types';

function enTeteAutorisation(): HeadersInit {
  const jeton = window.localStorage.getItem('samapiece.accessToken') ?? '';
  return { Authorization: `Bearer ${jeton}`, 'Content-Type': 'application/json' };
}

export async function recupererAgentCourant(): Promise<AgentCourant> {
  const reponse = await fetch('/api/v1/agents/moi', { headers: enTeteAutorisation() });
  if (!reponse.ok) throw new Error(`Erreur ${reponse.status}`);
  return reponse.json();
}

export async function getStatistiquesPoste(posteId: string): Promise<StatistiquesPoste> {
  const reponse = await fetch(`/api/v1/statistiques/poste/${posteId}`, {
    headers: enTeteAutorisation(),
  });
  if (!reponse.ok) throw new Error(`Erreur ${reponse.status}`);
  return reponse.json();
}
