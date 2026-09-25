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

  it("propose une alerte quand aucun résultat n'est trouvé", async () => {
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

    expect(await screen.findByLabelText('Téléphone')).toBeInTheDocument();
    expect(screen.queryByText('Cette fonctionnalité arrive bientôt.')).not.toBeInTheDocument();
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it('crée une alerte avec succès et affiche la confirmation', async () => {
    vi.mocked(fetch)
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        json: async () => RESULTAT_NON_TROUVE_MOCK,
      } as Response)
      .mockResolvedValueOnce({
        ok: true,
        status: 201,
        json: async () => ({
          message: 'Alerte enregistrée. Un lien de désinscription a été envoyé par SMS.',
        }),
      } as Response);

    render(<RecherchePubliquePage />);
    const utilisateur = await remplirChampsRequis();
    await utilisateur.click(screen.getByRole('button', { name: 'Rechercher' }));

    const bouton = await screen.findByRole('button', {
      name: 'Recevoir une alerte si cette pièce est déposée',
    });
    await utilisateur.click(bouton);

    await utilisateur.type(await screen.findByLabelText('Téléphone'), '771234567');
    await utilisateur.click(screen.getByRole('button', { name: "Confirmer l'alerte" }));

    expect(
      await screen.findByText('Alerte enregistrée. Un lien de désinscription a été envoyé par SMS.'),
    ).toBeInTheDocument();
    expect(screen.queryByLabelText('Téléphone')).not.toBeInTheDocument();

    expect(fetch).toHaveBeenCalledTimes(2);
    const [urlDeuxiemeAppel, optionsDeuxiemeAppel] = vi.mocked(fetch).mock.calls[1];
    expect(urlDeuxiemeAppel).toBe('/api/v1/alertes');
    const corps = JSON.parse((optionsDeuxiemeAppel?.body as string) ?? '{}');
    expect(corps).toMatchObject({
      typeDocument: 'CNI',
      nomTitulaire: 'Diop',
      numeroDocument: '1234567890',
      contact: '771234567',
    });
  });

  it('affiche l\'erreur métier renvoyée par l\'API en cas de critères insuffisants', async () => {
    vi.mocked(fetch)
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        json: async () => RESULTAT_NON_TROUVE_MOCK,
      } as Response)
      .mockResolvedValueOnce({
        ok: false,
        status: 400,
        json: async () => ({
          code: 'CRITERES_INSUFFISANTS',
          message:
            'Critères de recherche insuffisants : type, nom, et numéro ou date de naissance sont requis.',
        }),
      } as Response);

    render(<RecherchePubliquePage />);
    const utilisateur = await remplirChampsRequis();
    await utilisateur.click(screen.getByRole('button', { name: 'Rechercher' }));

    const bouton = await screen.findByRole('button', {
      name: 'Recevoir une alerte si cette pièce est déposée',
    });
    await utilisateur.click(bouton);

    await utilisateur.type(await screen.findByLabelText('Téléphone'), '771234567');
    await utilisateur.click(screen.getByRole('button', { name: "Confirmer l'alerte" }));

    expect(
      await screen.findByText(
        'Critères de recherche insuffisants : type, nom, et numéro ou date de naissance sont requis.',
      ),
    ).toBeInTheDocument();
    expect(screen.getByLabelText('Téléphone')).toBeInTheDocument();
    expect(screen.queryByText('Erreur inconnue lors de la recherche.')).not.toBeInTheDocument();
    expect(
      screen.getByRole('region', { name: 'Aucun résultat' }),
    ).toBeInTheDocument();
  });

  it("affiche l'erreur métier renvoyée par l'API en cas de contact invalide", async () => {
    vi.mocked(fetch)
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        json: async () => RESULTAT_NON_TROUVE_MOCK,
      } as Response)
      .mockResolvedValueOnce({
        ok: false,
        status: 400,
        json: async () => ({
          code: 'CONTACT_INVALIDE',
          message: 'Le contact fourni est invalide.',
        }),
      } as Response);

    render(<RecherchePubliquePage />);
    const utilisateur = await remplirChampsRequis();
    await utilisateur.click(screen.getByRole('button', { name: 'Rechercher' }));

    const bouton = await screen.findByRole('button', {
      name: 'Recevoir une alerte si cette pièce est déposée',
    });
    await utilisateur.click(bouton);

    await utilisateur.type(await screen.findByLabelText('Téléphone'), 'abc');
    await utilisateur.click(screen.getByRole('button', { name: "Confirmer l'alerte" }));

    expect(await screen.findByText('Le contact fourni est invalide.')).toBeInTheDocument();
    expect(screen.getByLabelText('Téléphone')).toBeInTheDocument();
  });

  it("bloque la soumission du formulaire d'alerte si le téléphone est vide", async () => {
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

    await utilisateur.click(screen.getByRole('button', { name: "Confirmer l'alerte" }));

    expect(screen.getByText('Le numéro de téléphone est requis.')).toBeInTheDocument();
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

  it('gère le cycle 428 -> défi captcha -> réponse -> recherche acceptée (parcours complet)', async () => {
    vi.mocked(fetch)
      .mockResolvedValueOnce({
        ok: false,
        status: 428,
        json: async () => ({}),
      } as Response)
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        json: async () => ({ captchaToken: 'token-A', question: 'Combien font 2 + 3 ?' }),
      } as Response)
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        json: async () => RESULTAT_TROUVE_MOCK,
      } as Response);

    render(<RecherchePubliquePage />);
    const utilisateur = await remplirChampsRequis();
    await utilisateur.click(screen.getByRole('button', { name: 'Rechercher' }));

    expect(
      await screen.findByText(
        'Vérification supplémentaire requise avant de poursuivre la recherche.',
      ),
    ).toBeInTheDocument();
    expect(await screen.findByText('Combien font 2 + 3 ?')).toBeInTheDocument();

    await utilisateur.type(screen.getByLabelText('Combien font 2 + 3 ?'), '5');
    await utilisateur.click(screen.getByRole('button', { name: 'Valider' }));

    const section = await screen.findByRole('region', { name: 'Résultat de la recherche' });
    expect(section).toHaveTextContent('Poste de Dakar-Plateau');

    expect(fetch).toHaveBeenCalledTimes(3);
    const [, optionsDeuxiemeAppelPost] = vi.mocked(fetch).mock.calls[2];
    const enTetes = optionsDeuxiemeAppelPost?.headers as Record<string, string>;
    expect(enTetes['X-Captcha-Token']).toBe('token-A');
    expect(enTetes['X-Captcha-Reponse']).toBe('5');

    expect(screen.queryByText(/Erreur 428/)).not.toBeInTheDocument();
  });

  it('affiche un nouveau défi après une réponse incorrecte au captcha (jamais réutilisation du token)', async () => {
    vi.mocked(fetch)
      .mockResolvedValueOnce({
        ok: false,
        status: 428,
        json: async () => ({}),
      } as Response)
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        json: async () => ({ captchaToken: 'token-A', question: 'Combien font 2 + 3 ?' }),
      } as Response)
      .mockResolvedValueOnce({
        ok: false,
        status: 428,
        json: async () => ({}),
      } as Response)
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        json: async () => ({ captchaToken: 'token-B', question: 'Combien font 4 + 1 ?' }),
      } as Response);

    render(<RecherchePubliquePage />);
    const utilisateur = await remplirChampsRequis();
    await utilisateur.click(screen.getByRole('button', { name: 'Rechercher' }));

    expect(await screen.findByText('Combien font 2 + 3 ?')).toBeInTheDocument();

    await utilisateur.type(screen.getByLabelText('Combien font 2 + 3 ?'), '0');
    await utilisateur.click(screen.getByRole('button', { name: 'Valider' }));

    expect(
      await screen.findByText(
        'Réponse incorrecte ou expirée. Une nouvelle question a été générée.',
      ),
    ).toBeInTheDocument();
    expect(await screen.findByText('Combien font 4 + 1 ?')).toBeInTheDocument();
    expect(screen.queryByText('Combien font 2 + 3 ?')).not.toBeInTheDocument();

    expect(screen.queryByText(/Erreur 428/)).not.toBeInTheDocument();
  });
});
