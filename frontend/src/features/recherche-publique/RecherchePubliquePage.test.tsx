import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import RecherchePubliquePage from './RecherchePubliquePage';
import type { RecherchePubliqueResponse } from './types';

const RESULTAT_TROUVE_MOCK: RecherchePubliqueResponse = {
  trouve: true,
  typeDocument: 'CNI',
  poste: {
    nom: 'Poste de Dakar-Plateau',
    adresse: 'Avenue Léopold Sédar Senghor, Dakar',
    horaires: 'Lun-Ven 8h-16h',
    telephone: '338211111',
  },
  referenceDossier: 'PC-ABCDEF01-2026-00001',
};

const RESULTAT_NON_TROUVE_MOCK: RecherchePubliqueResponse = {
  trouve: false,
  typeDocument: null,
  poste: null,
  referenceDossier: null,
};

async function remplirChampsRequis() {
  const utilisateur = userEvent.setup();
  await utilisateur.selectOptions(
    screen.getByLabelText('Type de document'),
    "Carte Nationale d'Identité (CNI)",
  );
  await utilisateur.type(screen.getByLabelText('Nom du titulaire'), 'Diop');
  await utilisateur.type(screen.getByLabelText('Numéro du document'), '1234567890');
  return utilisateur;
}

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn());
});

describe('RecherchePubliquePage', () => {
  it('affiche les champs du formulaire', () => {
    render(<RecherchePubliquePage />);
    expect(screen.getByLabelText('Type de document')).toBeInTheDocument();
    expect(screen.getByLabelText('Nom du titulaire')).toBeInTheDocument();
    expect(screen.getByLabelText('Prénom du titulaire')).toBeInTheDocument();
    expect(screen.getByLabelText('Numéro du document')).toBeInTheDocument();
    expect(screen.getByLabelText('Date de naissance du titulaire')).toBeInTheDocument();
  });

  it('affiche le résultat sans donnée sensible quand la pièce est trouvée', async () => {
    vi.mocked(fetch).mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => RESULTAT_TROUVE_MOCK,
    } as Response);

    render(<RecherchePubliquePage />);
    const utilisateur = await remplirChampsRequis();
    await utilisateur.click(screen.getByRole('button', { name: 'Rechercher' }));

    const section = await screen.findByRole('region', { name: 'Résultat de la recherche' });
    expect(section).toHaveTextContent('Poste de Dakar-Plateau');
    expect(section).toHaveTextContent('Avenue Léopold Sédar Senghor, Dakar');
    expect(section).toHaveTextContent('Lun-Ven 8h-16h');
    expect(section).toHaveTextContent('338211111');
    expect(section).toHaveTextContent('PC-ABCDEF01-2026-00001');
    expect(section).not.toHaveTextContent('1234567890');
  });

  it('propose une alerte quand aucun résultat n\'est trouvé', async () => {
    vi.mocked(fetch).mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => RESULTAT_NON_TROUVE_MOCK,
    } as Response);

    render(<RecherchePubliquePage />);
    const utilisateur = await remplirChampsRequis();
    await utilisateur.click(screen.getByRole('button', { name: 'Rechercher' }));

    const bouton = await screen.findByRole('button', {
      name: 'Recevoir une alerte si cette pièce est déposée',
    });
    await utilisateur.click(bouton);

    expect(await screen.findByText('Cette fonctionnalité arrive bientôt.')).toBeInTheDocument();
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it('bloque la soumission si typeDocument est manquant', async () => {
    const utilisateur = userEvent.setup();
    render(<RecherchePubliquePage />);
    await utilisateur.type(screen.getByLabelText('Nom du titulaire'), 'Diop');
    await utilisateur.type(screen.getByLabelText('Numéro du document'), '1234567890');
    await utilisateur.click(screen.getByRole('button', { name: 'Rechercher' }));

    expect(screen.getByText('Le type de document est requis.')).toBeInTheDocument();
    expect(fetch).not.toHaveBeenCalled();
  });

  it('bloque la soumission sans numéro de document ni date de naissance', async () => {
    const utilisateur = userEvent.setup();
    render(<RecherchePubliquePage />);
    await utilisateur.selectOptions(
      screen.getByLabelText('Type de document'),
      "Carte Nationale d'Identité (CNI)",
    );
    await utilisateur.type(screen.getByLabelText('Nom du titulaire'), 'Diop');
    await utilisateur.click(screen.getByRole('button', { name: 'Rechercher' }));

    expect(
      screen.getByText('Renseignez le numéro du document ou la date de naissance du titulaire.'),
    ).toBeInTheDocument();
    expect(fetch).not.toHaveBeenCalled();
  });

  it('accepte la date de naissance comme seul discriminant', async () => {
    vi.mocked(fetch).mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => RESULTAT_TROUVE_MOCK,
    } as Response);

    const utilisateur = userEvent.setup();
    render(<RecherchePubliquePage />);
    await utilisateur.selectOptions(
      screen.getByLabelText('Type de document'),
      "Carte Nationale d'Identité (CNI)",
    );
    await utilisateur.type(screen.getByLabelText('Nom du titulaire'), 'Diop');
    await utilisateur.type(screen.getByLabelText('Date de naissance du titulaire'), '1990-01-01');
    await utilisateur.click(screen.getByRole('button', { name: 'Rechercher' }));

    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it('affiche un message générique sur 400', async () => {
    vi.mocked(fetch).mockResolvedValueOnce({
      ok: false,
      status: 400,
      json: async () => {
        throw new Error('ne doit pas être appelé');
      },
    } as unknown as Response);

    render(<RecherchePubliquePage />);
    const utilisateur = await remplirChampsRequis();
    await utilisateur.click(screen.getByRole('button', { name: 'Rechercher' }));

    expect(
      await screen.findByText('Vérifiez les critères de recherche saisis.'),
    ).toBeInTheDocument();
  });
});
