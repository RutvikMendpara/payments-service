import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Same paths nginx proxies in the container, so the app never needs CORS.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    proxy: {
      '/api/ledger': { target: 'http://localhost:8081', rewrite: (p) => p.replace(/^\/api\/ledger/, '') },
      '/api/settlement': { target: 'http://localhost:8082', rewrite: (p) => p.replace(/^\/api\/settlement/, '') },
    },
  },
});
