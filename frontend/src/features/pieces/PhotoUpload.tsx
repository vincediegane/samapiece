import { useEffect, useRef, useState } from 'react';
import type { ChangeEvent } from 'react';
import { validerPhoto } from './photosApi';
import type { TypePhoto } from './types';

interface PhotoUploadProps {
  fichier: File | null;
  type: TypePhoto;
  onChange: (fichier: File | null) => void;
  onTypeChange: (type: TypePhoto) => void;
  erreur?: string | null;
  disabled?: boolean;
}

function PhotoUpload({ fichier, type, onChange, onTypeChange, erreur, disabled }: PhotoUploadProps) {
  const [apercu, setApercu] = useState<string | null>(null);
  const [erreurLocale, setErreurLocale] = useState<string | null>(null);
  const champ = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!fichier) {
      setApercu(null);
      if (champ.current) champ.current.value = '';
      return;
    }
    const url = URL.createObjectURL(fichier);
    setApercu(url);
    return () => URL.revokeObjectURL(url);
  }, [fichier]);

  function selectionner(evenement: ChangeEvent<HTMLInputElement>) {
    const choisi = evenement.target.files?.[0] ?? null;
    if (!choisi) {
      setErreurLocale(null);
      onChange(null);
      return;
    }
    const message = validerPhoto(choisi);
    if (message) {
      setErreurLocale(message);
      evenement.target.value = '';
      onChange(null);
      return;
    }
    setErreurLocale(null);
    onChange(choisi);
  }

  function retirer() {
    setErreurLocale(null);
    onChange(null);
  }

  const messageErreur = erreurLocale ?? erreur ?? null;

  return (
    <div className="flex flex-col gap-3">
      <label className="field">
        <span className="field-label">Photo du document (optionnel)</span>
        <input
          ref={champ}
          type="file"
          accept="image/jpeg,image/png"
          className="field-input"
          disabled={disabled}
          onChange={selectionner}
        />
      </label>
      <label className="field">
        <span className="field-label">Côté du document</span>
        <select
          className="field-input"
          value={type}
          disabled={disabled}
          onChange={(e) => onTypeChange(e.target.value as TypePhoto)}
        >
          <option value="RECTO">Recto</option>
          <option value="VERSO">Verso</option>
        </select>
      </label>
      {messageErreur && (
        <span role="alert" className="field-error">
          {messageErreur}
        </span>
      )}
      {fichier && apercu && (
        <div className="flex flex-col items-start gap-2">
          <img
            src={apercu}
            alt="Aperçu de la photo sélectionnée"
            className="max-h-48 rounded-lg border border-slate-200"
          />
          <button type="button" className="btn-outline" disabled={disabled} onClick={retirer}>
            Retirer la photo
          </button>
        </div>
      )}
    </div>
  );
}

export default PhotoUpload;
