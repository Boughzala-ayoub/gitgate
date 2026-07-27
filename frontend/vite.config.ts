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
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
