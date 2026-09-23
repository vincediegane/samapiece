import { IconSearch } from '../icons';

interface PublicHeaderProps {
  page: 'accueil' | 'recherche';
  onNaviguerAccueil: () => void;
  onNaviguerRecherche: () => void;
  onEspaceAgent: () => void;
}

function PublicHeader({
  page,
  onNaviguerAccueil,
  onNaviguerRecherche,
  onEspaceAgent,
}: PublicHeaderProps) {
  return (
    <header className="public-header">
      <button
        type="button"
        onClick={onNaviguerAccueil}
        className="flex items-baseline gap-2.5 whitespace-nowrap"
      >
        <span className="text-xl font-bold tracking-tight text-white">SamaPièce</span>
        <span className="h-1.5 w-1.5 rounded-full bg-accent-500" />
        <span className="hidden text-sm italic text-white/75 sm:inline">
          Retrouver ses papiers, sans détour
        </span>
      </button>
      <nav className="flex flex-wrap items-center gap-5">
        {page !== 'accueil' && (
          <button type="button" onClick={onNaviguerAccueil} className="public-nav-link">
            Accueil
          </button>
        )}
        {page !== 'recherche' && (
          <button
            type="button"
            onClick={onNaviguerRecherche}
            className="public-cta flex items-center gap-2"
          >
            <IconSearch width={16} height={16} />
            Rechercher ma pièce
          </button>
        )}
        <button type="button" onClick={onEspaceAgent} className="public-ghost-btn">
          Espace agent
        </button>
      </nav>
    </header>
  );
}

export default PublicHeader;
