import type { Agent, CreerAgentPayload, CreerAgentResultat } from './types';

const BASE_URL = '/api/v1/agents';

function enTeteAutorisation(): HeadersInit {
  const jeton = window.localStorage.getItem('samapiece.accessToken') ?? '';
  return { Authorization: `Bearer ${jeton}`, 'Content-Type': 'application/json' };
}

export async function listerAgents(): Promise<Agent[]> {
  const reponse = await fetch(BASE_URL, { headers: enTeteAutorisation() });
  if (!reponse.ok) throw new Error(`Erreur ${reponse.status}`);
  return reponse.json();
}

export async function creerAgent(payload: CreerAgentPayload): Promise<CreerAgentResultat> {
  const reponse = await fetch(BASE_URL, {
    method: 'POST',
    headers: enTeteAutorisation(),
    body: JSON.stringify(payload),
  });
  if (!reponse.ok) {
    const erreur = await reponse.json().catch(() => null);
    throw new Error(erreur?.message ?? `Erreur ${reponse.status}`);
  }
  return reponse.json();
}

export async function desactiverAgent(id: string): Promise<void> {
  const reponse = await fetch(`${BASE_URL}/${id}`, {
    method: 'DELETE',
    headers: enTeteAutorisation(),
  });
  if (!reponse.ok) throw new Error(`Erreur ${reponse.status}`);
}
