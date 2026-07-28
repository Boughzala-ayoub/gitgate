import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Build output goes into Spring Boot's static resources so the jar serves the dashboard at
// http://localhost:8080/. In dev, `npm run dev` proxies the API to the backend.
export default defineConfig({
  plugins: [react()],
  base: './',
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
  },
  server: {
    // Point the dev server at whichever backend you're running:
    //   npm run dev                                    → localhost:8080 (a local jar)
    //   VITE_API_TARGET=http://localhost:8100 npm run dev → a container on another port
    proxy: {
      '/api': process.env.VITE_API_TARGET || 'http://localhost:8080',
    },
  },
})
