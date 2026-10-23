import { resolve } from 'node:path';
import { defineConfig } from 'vite';

export default defineConfig({
  root: import.meta.dirname,
  server: { host: '127.0.0.1', port: 5189, strictPort: true, proxy: { '/api': 'http://127.0.0.1:5190' } },
  build: { outDir: resolve(import.meta.dirname, 'dist'), emptyOutDir: true },
});
