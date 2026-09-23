import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { recupererAgentCourant } from '../../features/dashboard/dashboardApi';
import type { AgentCourant } from '../../features/dashboard/types';
import { listerFile } from '../offline/fileSynchronisation';
import { viderSession } from '../../features/auth/session';
import { useRafraichissementSession } from '../../features/auth/useRafraichissementSession';
import { IconChart, IconCloudSync, IconDocument, IconLogout, IconUsers } from '../icons';

export type OngletAgent = 'pieces' | 'dashboard' | 'agents';

interface AgentShellProps {
  actif: OngletAgent;
  onNaviguer: (onglet: OngletAgent) => void;
  onRetourPublic: () => void;
  onDeconnexion: () => void;
  children: ReactNode;
}

function initiales(nom: string): string {
  const parties = nom.trim().split(/\s+/);
  return parties
    .slice(0, 2)
    .map((partie) => partie.charAt(0).toUpperCase())
    .join('');
}

function AgentShell({ actif, onNaviguer, onRetourPublic, onDeconnexion, children }: AgentShellProps) {
  const [agent, setAgent] = useState<AgentCourant | null>(null);
  const [nombreEnAttente, setNombreEnAttente] = useState(0);

  useRafraichissementSession({ onSessionExpiree: onDeconnexion });

  useEffect(() => {
    recupererAgentCourant()
      .then(setAgent)
      .catch(() => setAgent(null));
  }, []);

  useEffect(() => {
    let annule = false;
    async function rafraichir() {
      try {
        const items = await listerFile();
        if (!annule) setNombreEnAttente(items.length);
      } catch {
        // File hors-ligne indisponible : on n'affiche simplement pas le badge.
      }
    }
    void rafraichir();
    const intervalle = window.setInterval(() => void rafraichir(), 5_000);
    return () => {
      annule = true;
      window.clearInterval(intervalle);
    };
  }, []);

  function seDeconnecter() {
    viderSession();
    onDeconnexion();
  }

  return (
    <div className="flex min-h-screen flex-1">
      <aside className="flex w-64 flex-shrink-0 flex-col border-r border-slate-200 bg-white p-4">
        <button
          type="button"
          onClick={onRetourPublic}
          className="flex items-center gap-2 px-2 pb-5"
        >
          <span className="text-lg font-bold text-primary-700">SamaPièce</span>
          <span className="h-1.5 w-1.5 rounded-full bg-accent-500" />
        </button>

        {agent && (
          <div className="mb-5 rounded-lg border border-slate-200 bg-slate-50 px-3 py-2.5">
            <span className="block text-[11px] font-semibold uppercase tracking-wide text-slate-400">
              Poste actif
            </span>
            <span className="text-sm font-semibold text-slate-800">{agent.posteNom}</span>
          </div>
        )}

        <nav className="flex flex-1 flex-col gap-0.5">
          <button
            type="button"
            className={actif === 'pieces' ? 'sidebar-link-active' : 'sidebar-link'}
            onClick={() => onNaviguer('pieces')}
          >
            <IconDocument width={18} height={18} />
            Enregistrement
          </button>
          <button
            type="button"
            className={actif === 'dashboard' ? 'sidebar-link-active' : 'sidebar-link'}
            onClick={() => onNaviguer('dashboard')}
          >
            <IconChart width={18} height={18} />
            Tableau de bord
          </button>
          <button
            type="button"
            className={actif === 'agents' ? 'sidebar-link-active' : 'sidebar-link'}
            onClick={() => onNaviguer('agents')}
          >
            <IconUsers width={18} height={18} />
            Agents
          </button>
        </nav>

        <div className="mt-3 flex flex-col gap-3 border-t border-slate-200 pt-4">
          {nombreEnAttente > 0 && (
            <div className="flex items-center gap-2 rounded-lg bg-info-50 px-2.5 py-2">
              <IconCloudSync width={16} height={16} className="flex-shrink-0 text-info-500" />
              <span className="text-xs font-semibold text-info-500">
                {nombreEnAttente} fiche(s) en attente de sync
              </span>
            </div>
          )}
          {agent && (
            <div className="flex items-center gap-2.5 px-1">
              <div className="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-full bg-primary-500 text-xs font-bold text-white">
                {initiales(agent.nom)}
              </div>
              <div className="min-w-0 flex-1">
                <span className="block truncate text-sm font-semibold text-slate-800">
                  {agent.nom}
                </span>
                <span className="block truncate text-[11px] text-slate-400">{agent.role}</span>
              </div>
              <button
                type="button"
                title="Déconnexion"
                onClick={seDeconnecter}
                className="flex-shrink-0 text-slate-400 hover:text-slate-600"
              >
                <IconLogout width={16} height={16} />
              </button>
            </div>
          )}
        </div>
      </aside>

      <main className="flex-1 bg-slate-50">{children}</main>
    </div>
  );
}

export default AgentShell;
