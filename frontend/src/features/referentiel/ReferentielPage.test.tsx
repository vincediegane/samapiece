import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ReferentielPage from './ReferentielPage';
import { creerPoste, creerRegion, listerRegions } from './referentielApi';

vi.mock('./referentielApi', () => ({
  listerRegions: vi.fn(),
  creerRegion: vi.fn(),
  creerPoste: vi.fn(),
}));

const DAKAR = { id: 'r-1', nom: 'Dakar' };

beforeEach(() => {
  vi.mocked(listerRegions).mockReset().mockResolvedValue([DAKAR]);
  vi.mocked(creerRegion).mockReset();
  vi.mocked(creerPoste).mockReset();
});

async function remplirPoste(utilisateur: ReturnType<typeof userEvent.setup>) {
  await screen.findByRole('option', { name: 'Dakar' });
  await utilisateur.selectOptions(screen.getByLabelText('Région'), 'r-1');
  await utilisateur.type(screen.getByLabelText('Nom du poste'), 'Commissariat de Rufisque');
  await utilisateur.type(screen.getByLabelText('Adresse'), 'Route de Bargny');
  await utilisateur.type(screen.getByLabelText('Téléphone'), '+221338360000');
}

describe('ReferentielPage', () => {
  it('charge et affiche les régions dans le select', async () => {
    render(<ReferentielPage />);

    expect(await screen.findByRole('option', { name: 'Dakar' })).toBeInTheDocument();
  });

  it('crée une région puis rafraîchit la liste et présélectionne la nouvelle région', async () => {
    const thies = { id: 'r-2', nom: 'Thiès' };
    vi.mocked(creerRegion).mockResolvedValue(thies);
    vi.mocked(listerRegions).mockResolvedValueOnce([DAKAR]).mockResolvedValueOnce([DAKAR, thies]);
    const utilisateur = userEvent.setup();
    render(<ReferentielPage />);
    await screen.findByRole('option', { name: 'Dakar' });

    await utilisateur.type(screen.getByLabelText('Nom de la région'), 'Thiès');
    await utilisateur.click(screen.getByRole('button', { name: 'Créer la région' }));

    await waitFor(() => expect(creerRegion).toHaveBeenCalledWith({ nom: 'Thiès' }));
    expect(await screen.findByRole('option', { name: 'Thiès' })).toBeInTheDocument();
    expect(screen.getByLabelText('Région')).toHaveValue('r-2');
    expect(listerRegions).toHaveBeenCalledTimes(2);
  });

  it('crée un poste avec les horaires parsés en objet', async () => {
    vi.mocked(creerPoste).mockResolvedValue({
      id: 'p-1',
      nom: 'Commissariat de Rufisque',
      type: 'POLICE',
      adresse: 'Route de Bargny',
      telephone: '+221338360000',
      horaires: '{}',
      latitude: null,
      longitude: null,
      region: DAKAR,
    });
    const utilisateur = userEvent.setup();
    render(<ReferentielPage />);
    await remplirPoste(utilisateur);

    await utilisateur.click(screen.getByRole('button', { name: 'Créer le poste' }));

    await waitFor(() =>
      expect(creerPoste).toHaveBeenCalledWith({
        regionId: 'r-1',
        nom: 'Commissariat de Rufisque',
        type: 'POLICE',
        adresse: 'Route de Bargny',
        telephone: '+221338360000',
        horaires: { lundi: { ouvert: true, debut: '08:00', fin: '18:00' } },
      }),
    );
    expect(await screen.findByRole('status')).toHaveTextContent('Commissariat de Rufisque');
  });

  it.each(['pas du json', '[1,2]', '42'])(
    'refuse des horaires invalides (%s) sans appel réseau',
    async (saisie) => {
      const utilisateur = userEvent.setup();
      render(<ReferentielPage />);
      await remplirPoste(utilisateur);
      const zone = screen.getByLabelText('Horaires (JSON)');
      await utilisateur.clear(zone);
      await utilisateur.click(zone);
      await utilisateur.paste(saisie);

      await utilisateur.click(screen.getByRole('button', { name: 'Créer le poste' }));

      expect(await screen.findByRole('alert')).toHaveTextContent('Horaires : JSON invalide');
      expect(creerPoste).not.toHaveBeenCalled();
    },
  );

  it('affiche le message d’erreur de l’API (409)', async () => {
    vi.mocked(creerRegion).mockRejectedValue(new Error('Cette region existe deja.'));
    const utilisateur = userEvent.setup();
    render(<ReferentielPage />);
    await screen.findByRole('option', { name: 'Dakar' });

    await utilisateur.type(screen.getByLabelText('Nom de la région'), 'Dakar');
    await utilisateur.click(screen.getByRole('button', { name: 'Créer la région' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Cette region existe deja.');
  });

  it('n’appelle pas l’API quand les champs requis sont vides', async () => {
    const utilisateur = userEvent.setup();
    render(<ReferentielPage />);
    await screen.findByRole('option', { name: 'Dakar' });

    await utilisateur.click(screen.getByRole('button', { name: 'Créer la région' }));
    await utilisateur.click(screen.getByRole('button', { name: 'Créer le poste' }));

    expect(creerRegion).not.toHaveBeenCalled();
    expect(creerPoste).not.toHaveBeenCalled();
  });
});
