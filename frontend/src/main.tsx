import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { registerSW } from 'virtual:pwa-register';
import { demarrerDeclencheurs } from './shared/offline/fileSynchronisation';
import './index.css';
import App from './app/App';

registerSW({ immediate: true });
demarrerDeclencheurs();

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
