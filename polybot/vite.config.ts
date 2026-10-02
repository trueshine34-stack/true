import { readFileSync } from 'node:fs';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

/**
 * The build's version, read from the one place it is actually set.
 *
 * It used to be typed into a source file beside the gradle file that really
 * decides it, and the two drifted ten builds apart without anything noticing —
 * which made "is this the build with the fix" unanswerable from the screen,
 * exactly when it was being asked. One source, read at build time.
 */
function appVersion(): string {
  try {
    const gradle = readFileSync('android/app/build.gradle', 'utf8');
    return /versionName\s+"([^"]+)"/.exec(gradle)?.[1] ?? 'dev';
  } catch {
    return 'dev';
  }
}

export default defineConfig({
  plugins: [react()],
  define: { __APP_VERSION__: JSON.stringify(appVersion()) },
  build: { outDir: 'dist', target: 'es2022', sourcemap: false },
  server: { host: true, port: 5173 },
});
