/// <reference types="vitest/config" />
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    // Demo mode only (docs/DEMO_LOCAL.md): same-origin path the browser can call so the
    // mock-jwks test double never needs CORS headers of its own. Dev server only - it has
    // no effect on `vite build`, and no effect at all when VITE_AUTH_MODE is unset.
    proxy: {
      '/mock-auth': {
        target: 'http://localhost:1080',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/mock-auth/, '/mock-tenant/v2.0'),
      },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    globals: true,
    // Vitest loads frontend-campuslab/.env like any Vite build, so a local
    // VITE_AUTH_MODE=demo would otherwise leak into the suite and make every test
    // exercise the demo login instead of the real Entra path. Pinned empty here so the
    // tests always assert production behaviour regardless of the developer's .env.
    env: {
      VITE_AUTH_MODE: '',
    },
  },
})
