// Правила интерфейса (шаг 16 Ф4). Главное правило проекта: тексты для пользователя лежат в src/i18n/ru.json, в коде и разметке их нет (NFT-7.0).
// Три селектора ниже запрещают: текст между тегами, строковый литерал в текстовых атрибутах и любой литерал с кириллицей вне словаря.
import js from '@eslint/js';
import reactHooks from 'eslint-plugin-react-hooks';
import tseslint from 'typescript-eslint';

const CYRILLIC = '/[А-Яа-яЁё]/';

const noHardcodedText = [
  {
    selector: 'JSXText[value=/\\S/]',
    message: 'Текст в разметке запрещён: добавьте строку в src/i18n/ru.json и выведите её через t() (NFT-7.0).',
  },
  {
    selector: 'JSXAttribute[name.name=/^(title|alt|placeholder|aria-label|aria-description|label)$/] > Literal',
    message: 'Текстовый атрибут со строковым литералом запрещён: используйте t() (NFT-7.0).',
  },
  {
    selector: `Literal[value=${CYRILLIC}]`,
    message: 'Русский текст в коде запрещён: строки для пользователя лежат в src/i18n/ru.json (NFT-7.0).',
  },
  {
    selector: `TemplateElement[value.raw=${CYRILLIC}]`,
    message: 'Русский текст в шаблонной строке запрещён: строки для пользователя лежат в src/i18n/ru.json (NFT-7.0).',
  },
];

export default tseslint.config(
  { ignores: ['dist/**', 'coverage/**', 'node_modules/**', 'src/api/generated/**'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ['src/**/*.{ts,tsx}'],
    plugins: { 'react-hooks': reactHooks },
    rules: {
      'react-hooks/rules-of-hooks': 'error',
      'react-hooks/exhaustive-deps': 'error',
      'no-restricted-syntax': ['error', ...noHardcodedText],
      '@typescript-eslint/consistent-type-imports': 'error',
      '@typescript-eslint/no-explicit-any': 'error',
      eqeqeq: 'error',
      'no-console': ['error', { allow: ['warn', 'error'] }],
    },
  },
  {
    // В тестах ожидаемые тексты берутся из словаря, но литералы для проверки самого словаря и ввода пользователя допустимы
    files: ['src/**/*.test.{ts,tsx}', 'src/test/**'],
    rules: {
      'no-restricted-syntax': 'off',
    },
  },
  {
    files: ['scripts/**/*.mjs', 'vite.config.ts', 'eslint.config.js'],
    languageOptions: { globals: { process: 'readonly', console: 'readonly' } },
  },
);
