// Bundles src/ into the Android app's assets. The output is committed so a
// plain Gradle build never needs Node.
import { build } from 'esbuild';
import { copyFileSync, mkdirSync } from 'fs';

const out = '../app/src/main/assets/terrain3d';
mkdirSync(out, { recursive: true });
copyFileSync('src/index.html', `${out}/index.html`);
copyFileSync('src/terrain.css', `${out}/terrain.css`);
await build({
  entryPoints: ['src/main.js'],
  bundle: true,
  minify: true,
  format: 'iife',
  target: 'chrome100',
  outfile: `${out}/terrain.js`,
  legalComments: 'eof',
  logLevel: 'warning'
});
console.log(`built ${out}`);
