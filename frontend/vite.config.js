import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  // Port 3000 is the frontend port in service-config.md and in the backends' CORS defaults
  server: { port: 3000, strictPort: true },
  preview: { port: 3000, strictPort: true },
  test: { environment: 'node', include: ['src/**/*.test.js', 'sync-dev-server/**/*.test.js'] },
})
