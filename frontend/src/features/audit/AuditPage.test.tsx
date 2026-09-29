import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import AuditPage from './AuditPage';
import { ErreurApiAudit, listerEvenementsAudit } from './auditApi';
import { recupererAgentCourant } from '../dashboard/dashboardApi';
import type { PageEvenementsAudit } from './types';

vi.mock('../dashboard/dashboardApi', () => ({
  recupererAgentCourant: vi.fn(),
}));

vi.mock('./auditApi', async (importOriginal) => {
  const original = await importOriginal<typeof import('./auditApi')>();
  return { ...original, listerEvenementsAudit: vi.fn() };
});

const MESSAGE_REFUS = 'Accès réservé aux auditeurs et administrateurs nationaux';

function agentAvecRole(role: string) {
  return {
    id: 'agent-1',
    matricule: 'PN-1',
    nom: 'Diop Awa',
    role,
    posteId: 'poste-1',
    posteNom: 'Poste',
    actif: true,
    creeLe: '2026-01-15T10:00:00Z',
  };
}

function pageAudit(surcharge: Partial<PageEvenementsAudit> = {}): PageEvenementsAudit {
  return {
    content: [
      {
        id: 'ev-1',
        acteurId: 'acteur-1',
        typeActeur: 'AGENT',
        action: 'PIECE_CREEE',
        entiteCible: 'PIECE',
        entiteCibleId: 'abcdef12-0000-0000-0000-000000000000',
        details: '<b>x</b>',
        adresseIp: '127.0.0.1',
        horodatage: '2026-03-01T10:00:00Z',
      },
    ],
    number: 0,
    totalPages: 2,
    totalElements: 21,
    size: 20,
    ...surcharge,
  };
}

beforeEach(() => {
  vi.mocked(recupererAgentCourant).mockReset();
  vi.mocked(listerEvenementsAudit).mockReset();
  vi.mocked(listerEvenementsAudit).mockResolvedValue(pageAudit());
});

describe('AuditPage', () => {
  it.each(['AUDITEUR', 'ADMIN_NATIONAL'])('affiche les événements pour %s', async (role) => {
    vi.mocked(recupererAgentCourant).mockResolvedValue(agentAvecRole(role));
    render(<AuditPage />);

    expect(await screen.findByText('PIECE_CREEE', { selector: 'td' })).toBeInTheDocument();
    expect(screen.getByText('PIECE abcdef12')).toBeInTheDocument();
    expect(screen.getByText('<b>x</b>')).toBeInTheDocument();
    expect(listerEvenementsAudit).toHaveBeenCalledWith(0, {});
    expect(screen.getByText(/Page 1 sur 2/)).toBeInTheDocument();
  });

  it.each(['AGENT', 'CHEF_POSTE'])('refuse %s sans appeler l’API audit', async (role) => {
    vi.mocked(recupererAgentCourant).mockResolvedValue(agentAvecRole(role));
    render(<AuditPage />);

    expect(await screen.findByText(MESSAGE_REFUS)).toBeInTheDocument();
    expect(listerEvenementsAudit).not.toHaveBeenCalled();
  });

  it('affiche Chargement sans appel audit tant que le rôle est inconnu', () => {
    vi.mocked(recupererAgentCourant).mockReturnValue(new Promise(() => {}));
    render(<AuditPage />);

    expect(screen.getByText('Chargement…')).toBeInTheDocument();
    expect(listerEvenementsAudit).not.toHaveBeenCalled();
  });

  it('refuse quand la récupération de l’agent échoue', async () => {
    vi.mocked(recupererAgentCourant).mockRejectedValue(new Error('Erreur 500'));
    render(<AuditPage />);

    expect(await screen.findByText(MESSAGE_REFUS)).toBeInTheDocument();
    expect(listerEvenementsAudit).not.toHaveBeenCalled();
  });

  it('affiche le message d’accès réservé sur 403', async () => {
    vi.mocked(recupererAgentCourant).mockResolvedValue(agentAvecRole('AUDITEUR'));
    vi.mocked(listerEvenementsAudit).mockRejectedValue(new ErreurApiAudit(403));
    render(<AuditPage />);

    expect(await screen.findByText(MESSAGE_REFUS)).toBeInTheDocument();
  });

  it('affiche une erreur générique sur erreur serveur', async () => {
    vi.mocked(recupererAgentCourant).mockResolvedValue(agentAvecRole('AUDITEUR'));
    vi.mocked(listerEvenementsAudit).mockRejectedValue(new ErreurApiAudit(500));
    render(<AuditPage />);

    expect(await screen.findByText("Impossible de charger le journal d'audit.")).toBeInTheDocument();
  });

  it('affiche l’état vide', async () => {
    vi.mocked(recupererAgentCourant).mockResolvedValue(agentAvecRole('AUDITEUR'));
    vi.mocked(listerEvenementsAudit).mockResolvedValue(
      pageAudit({ content: [], totalPages: 0, totalElements: 0 }),
    );
    render(<AuditPage />);

    expect(await screen.findByText("Aucun événement d'audit")).toBeInTheDocument();
  });

  it('pagine avec Suivant et Précédent', async () => {
    vi.mocked(recupererAgentCourant).mockResolvedValue(agentAvecRole('AUDITEUR'));
    vi.mocked(listerEvenementsAudit).mockImplementation(async (page) =>
      pageAudit({ number: page }),
    );
    const utilisateur = userEvent.setup();
    render(<AuditPage />);

    await screen.findByText(/Page 1 sur 2/);
    expect(screen.getByRole('button', { name: 'Précédent' })).toBeDisabled();
    await utilisateur.click(screen.getByRole('button', { name: 'Suivant' }));

    expect(await screen.findByText(/Page 2 sur 2/)).toBeInTheDocument();
    expect(listerEvenementsAudit).toHaveBeenLastCalledWith(1, {});
    expect(screen.getByRole('button', { name: 'Suivant' })).toBeDisabled();
    await utilisateur.click(screen.getByRole('button', { name: 'Précédent' }));
    expect(await screen.findByText(/Page 1 sur 2/)).toBeInTheDocument();
  });

  it('filtre par action et entité en revenant à la page 0, puis retire le filtre', async () => {
    vi.mocked(recupererAgentCourant).mockResolvedValue(agentAvecRole('ADMIN_NATIONAL'));
    vi.mocked(listerEvenementsAudit).mockImplementation(async (page) =>
      pageAudit({ number: page }),
    );
    const utilisateur = userEvent.setup();
    render(<AuditPage />);

    await screen.findByText(/Page 1 sur 2/);
    await utilisateur.click(screen.getByRole('button', { name: 'Suivant' }));
    await screen.findByText(/Page 2 sur 2/);

    await utilisateur.selectOptions(screen.getByLabelText('Action'), 'PIECE_RETIREE');
    expect(await screen.findByText(/Page 1 sur 2/)).toBeInTheDocument();
    expect(listerEvenementsAudit).toHaveBeenLastCalledWith(0, { action: 'PIECE_RETIREE' });

    await utilisateur.selectOptions(screen.getByLabelText('Entité'), 'PIECE');
    await screen.findByText(/Page 1 sur 2/);
    expect(listerEvenementsAudit).toHaveBeenLastCalledWith(0, {
      action: 'PIECE_RETIREE',
      entiteCible: 'PIECE',
    });

    await utilisateur.selectOptions(screen.getByLabelText('Action'), 'Toutes');
    await screen.findByText(/Page 1 sur 2/);
    expect(listerEvenementsAudit).toHaveBeenLastCalledWith(0, {
      action: undefined,
      entiteCible: 'PIECE',
    });
  });
});
