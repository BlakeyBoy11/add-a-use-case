/* eslint-env node */
require('@rushstack/eslint-patch/modern-module-resolution')

module.exports = {
  root: true,
  extends: [
    'plugin:vue/vue3-essential',
    'eslint:recommended',
    '@vue/eslint-config-typescript',
    '@vue/eslint-config-prettier/skip-formatting'
  ],
  parserOptions: {
    ecmaVersion: 'latest'
  },
  rules: {
    'vue/multi-word-component-names': 'off',
    // frontend/CLAUDE.md: every HTTP call goes through the shared instance in src/utils/request.ts
    'no-restricted-imports': [
      'error',
      {
        paths: [
          {
            name: 'axios',
            message:
              'Go through an src/apis/<feature>/ module; for a failed request, use httpStatusOf from @/utils/request.'
          }
        ]
      }
    ]
  },
  overrides: [
    {
      files: ['src/utils/request.ts'],
      rules: { 'no-restricted-imports': 'off' }
    }
  ]
}
