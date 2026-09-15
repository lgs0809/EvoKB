#!/usr/bin/env node

import { execFileSync } from 'node:child_process';
import { readFileSync, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const textSuffixes = new Set([
  '.java', '.kt', '.kts', '.py', '.js', '.jsx', '.mjs', '.cjs', '.ts', '.tsx', '.json',
  '.yml', '.yaml', '.xml', '.properties', '.toml', '.ini', '.conf', '.md', '.txt', '.sql',
  '.sh', '.bash', '.ps1', '.bat', '.cmd', '.html', '.css', '.scss', '.env', '.example',
]);

const bannedTrackedParts = new Set([
  'node_modules', 'target', 'dist', 'coverage', 'reports', '.idea', '.vscode', '.DS_Store',
]);

const rules = [
  ['developer-windows-home', /[A-Za-z]:\\Users\\/i],
  ['developer-macos-home', /\/Users\/[^/\s]+\//],
  ['developer-linux-home', /\/home\/[^/\s]+\//],
  ['developer-desktop-path', /(?:\/|\\)(?:Desktop|Documents)(?:\/|\\)/i],
  ['legacy-product-name', /sage[-_ ]?wiki/i],
  ['known-developer-identifier', /101201912008/],
  ['openai-style-secret', /\bsk-[A-Za-z0-9_-]{20,}\b/],
  ['bearer-token', /\bBearer\s+[A-Za-z0-9._~+/=-]{24,}/i],
  ['private-key', /-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----/],
];

const allowedRulePaths = new Map([
  ['legacy-product-name', new Set(['LICENSE'])],
]);

function trackedFiles() {
  const output = execFileSync('git', ['ls-files', '--cached', '--others', '--exclude-standard', '-z'], { cwd: ROOT });
  return output.toString('utf8').split('\0').filter(Boolean);
}

function isTextCandidate(relative) {
  const basename = path.basename(relative).toLowerCase();
  return textSuffixes.has(path.extname(relative).toLowerCase()) ||
    ['dockerfile', 'makefile', 'license', 'readme', '.gitignore', '.dockerignore'].includes(basename);
}

function main() {
  const violations = [];

  for (const relativeRaw of trackedFiles()) {
    const relative = relativeRaw.replaceAll('\\', '/');
    if (relative === 'scripts/release-hygiene.mjs') continue;

    const parts = relative.split('/');
    const deploymentRunbook = relative.startsWith('deploy/target/') && /\.(?:md|sql)$/.test(relative);
    if (parts.some(part => bannedTrackedParts.has(part)) && !deploymentRunbook) {
      violations.push([relative, 0, 'generated-or-local-artifact']);
      continue;
    }

    const absolute = path.join(ROOT, ...parts);
    try {
      if (!statSync(absolute).isFile() || !isTextCandidate(relative)) continue;
    } catch {
      continue;
    }

    const buffer = readFileSync(absolute);
    if (buffer.includes(0)) continue;
    const lines = buffer.toString('utf8').split(/\r?\n/);

    lines.forEach((line, index) => {
      for (const [ruleName, pattern] of rules) {
        if (allowedRulePaths.get(ruleName)?.has(relative)) continue;
        if (pattern.test(line)) violations.push([relative, index + 1, ruleName]);
      }
    });
  }

  if (violations.length > 0) {
    console.error('Release hygiene check failed:');
    for (const [relative, line, rule] of violations) {
      console.error(`- ${line ? `${relative}:${line}` : relative} [${rule}]`);
    }
    return 1;
  }

  console.log('Release hygiene check passed: no blocked tracked-file residue found.');
  return 0;
}

process.exitCode = main();
