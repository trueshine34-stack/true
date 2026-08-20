import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  build: { outDir: 'dist', target: 'es2022', sourcemap: false },
  server: { host: true, port: 5173 },
});
