import js from '@eslint/js';
import globals from 'globals';
import tseslint from 'typescript-eslint';

const applicationFiles = ['src/**/*.{ts,tsx}'];
const toolingFiles = ['e2e/**/*.ts', 'playwright.config.ts'];
const typescriptFiles = [...applicationFiles, ...toolingFiles];

export default tseslint.config(
  {
    ignores: ['dist/**', 'node_modules/**', 'test-results/**', 'playwright-report/**'],
  },
  {
    ...js.configs.recommended,
    files: typescriptFiles,
    languageOptions: {
      ...js.configs.recommended.languageOptions,
      globals: { ...globals.browser, ...globals.node },
    },
  },
  ...tseslint.configs.recommended.map(config => ({
    ...config,
    files: typescriptFiles,
  })),
  {
    files: applicationFiles,
    rules: {
      'no-unused-vars': 'off',
      '@typescript-eslint/no-unused-vars': 'off',
      '@typescript-eslint/no-explicit-any': 'off',
    },
  },
  {
    files: toolingFiles,
    rules: {
      'no-unused-vars': 'off',
      '@typescript-eslint/no-explicit-any': 'off',
    },
  },
);
