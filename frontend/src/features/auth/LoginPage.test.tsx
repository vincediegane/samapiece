import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import LoginPage from './LoginPage';

beforeEach(() => {
  window.localStorage.clear();
  vi.stubGlobal('fetch', vi.fn());
});

async function remplirEtSoumettre(matricule: string, motDePasse: string) {
  const utilisateur = userEvent.setup();
  render(<LoginPage onConnexionReussie={vi.fn()} />);
  if (matricule) await utilisateur.type(screen.getByLabelText('Matricule'), matricule);
  if (motDePasse) await utilisateur.type(screen.getByLabelText('Mot de passe'), motDePasse);
  await utilisateur.click(screen.getByRole('button', { name: 'Se connecter' }));
  return utilisateur;
}

describe('LoginPage', () => {
  it('affiche les champs matricule/mot de passe et le bouton de connexion', () => {
    render(<LoginPage onConnexionReussie={vi.fn()} />);
    expect(screen.getByLabelText('Matricule')).toBeInTheDocument();
    expect(screen.getByLabelText('Mot de passe')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Se connecter' })).toBeInTheDocument();
  });

  it('bloque la soumission si le matricule est manquant', async () => {
    await remplirEtSoumettre('', 'secret123');
    expect(screen.getByText('Le matricule est requis.')).toBeInTheDocument();
    expect(fetch).not.toHaveBeenCalled();
  });

  it('bloque la soumission si le mot de passe est manquant', async () => {
    await remplirEtSoumettre('PN-2024-00001', '');
    expect(screen.getByText('Le mot de passe est requis.')).toBeInTheDocument();
    expect(fetch).not.toHaveBeenCalled();
  });

  it('appelle POST /api/v1/auth/login et stocke la session en cas de succès', async () => {
    vi.mocked(fetch).mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => ({
        accessToken: 'access-1',
        refreshToken: 'refresh-1',
        expiresIn: 900,
        role: 'AGENT',
        nom: 'Diop Awa',
      }),
    } as Response);
    const onConnexionReussie = vi.fn();

    const utilisateur = userEvent.setup();
    render(<LoginPage onConnexionReussie={onConnexionReussie} />);
    await utilisateur.type(screen.getByLabelText('Matricule'), 'PN-2024-00001');
    await utilisateur.type(screen.getByLabelText('Mot de passe'), 'secret123');
    await utilisateur.click(screen.getByRole('button', { name: 'Se connecter' }));

    await vi.waitFor(() => expect(onConnexionReussie).toHaveBeenCalled());

    expect(fetch).toHaveBeenCalledWith(
      '/api/v1/auth/login',
      expect.objectContaining({
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ matricule: 'PN-2024-00001', motDePasse: 'secret123' }),
      }),
    );
    expect(window.localStorage.getItem('samapiece.accessToken')).toBe('access-1');
    expect(window.localStorage.getItem('samapiece.refreshToken')).toBe('refresh-1');
    expect(window.localStorage.getItem('samapiece.accessTokenExpiresAt')).not.toBeNull();
  });

  it('affiche le message d’erreur sur identifiants invalides (401)', async () => {
    vi.mocked(fetch).mockResolvedValueOnce({
      ok: false,
      status: 401,
      json: async () => ({
        code: 'IDENTIFIANTS_INVALIDES',
        message: 'Matricule ou mot de passe invalide.',
      }),
    } as Response);

    await remplirEtSoumettre('PN-2024-00001', 'mauvais-mdp');

    expect(await screen.findByText('Matricule ou mot de passe invalide.')).toBeInTheDocument();
  });

  it('affiche le message d’erreur sur compte verrouillé (423)', async () => {
    vi.mocked(fetch).mockResolvedValueOnce({
      ok: false,
      status: 423,
      json: async () => ({
        code: 'COMPTE_VERROUILLE',
        message: 'Compte temporairement verrouillé.',
      }),
    } as Response);

    await remplirEtSoumettre('PN-2024-00001', 'secret123');

    const messageVerrouille = await screen.findByText('Compte temporairement verrouillé.');
    expect(messageVerrouille).toBeInTheDocument();
    expect(messageVerrouille.textContent).not.toBe('Matricule ou mot de passe invalide.');
  });

  it('affiche un message générique sur échec réseau', async () => {
    vi.mocked(fetch).mockRejectedValueOnce(new TypeError('Failed to fetch'));

    await remplirEtSoumettre('PN-2024-00001', 'secret123');

    expect(await screen.findByText('Une erreur est survenue, réessayez.')).toBeInTheDocument();
  });
});
