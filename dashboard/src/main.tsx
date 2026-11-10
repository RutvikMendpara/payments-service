import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { login } from './auth';
import './styles.css';

login().then((user) => {
  if (user) {
    createRoot(document.getElementById('root')!).render(
      <StrictMode>
        <App username={user.profile.preferred_username ?? 'ops'} />
      </StrictMode>,
    );
  }
});
