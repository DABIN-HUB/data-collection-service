import js from "@eslint/js";
import globals from "globals";
import { builtinModules } from "node:module";
import tseslint from "typescript-eslint";
import vue from "eslint-plugin-vue";

const rendererFiles = ["src/**/*.{ts,vue}"];
const nodeFiles = ["electron/**/*.{ts,cts}", "scripts/**/*.mjs", "vite.config.ts", "eslint.config.mjs", "stylelint.config.mjs"];
const normalizedNodeBuiltins = builtinModules.map((name) => name.replace(/^node:/u, ""));
const nodeBuiltinImports = [
  ...new Set([
    ...normalizedNodeBuiltins,
    ...normalizedNodeBuiltins.map((name) => `node:${name}`),
    "electron"
  ])
];

export default tseslint.config(
  {
    ignores: [
      "node_modules/**",
      "dist/**",
      "release/**",
      "coverage/**",
      "../collector-boot/src/main/resources/static/desktop/**"
    ]
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...vue.configs["flat/essential"],
  {
    files: rendererFiles,
    languageOptions: {
      ecmaVersion: "latest",
      sourceType: "module",
      globals: globals.browser
    },
    rules: {
      "no-console": ["error", { allow: ["warn", "error"] }],
      "no-debugger": "error",
      "no-restricted-imports": [
        "error",
        {
          paths: nodeBuiltinImports.map((name) => ({
            name,
            message: "渲染进程源码不得直接访问 Node/Electron API，请通过 preload 白名单能力。"
          })),
          patterns: [
            {
              group: ["node:*", "electron/*"],
              message: "渲染进程源码不得直接访问 Node/Electron API，请通过 preload 白名单能力。"
            }
          ]
        }
      ]
    }
  },
  {
    files: ["src/**/*.{ts,vue}", "electron/**/*.{ts,cts}", "vite.config.ts"],
    rules: {
      "no-undef": "off",
      "no-unused-vars": "off",
      "@typescript-eslint/no-unused-vars": [
        "error",
        {
          argsIgnorePattern: "^_",
          varsIgnorePattern: "^_",
          caughtErrorsIgnorePattern: "^_",
          ignoreRestSiblings: true
        }
      ],
      "@typescript-eslint/no-explicit-any": "off"
    }
  },
  {
    files: ["src/**/*.vue"],
    languageOptions: {
      parserOptions: {
        parser: tseslint.parser,
        ecmaVersion: "latest",
        sourceType: "module"
      }
    },
    rules: {
      "vue/no-v-html": "error",
      "vue/multi-word-component-names": "off"
    }
  },
  {
    files: nodeFiles,
    languageOptions: {
      ecmaVersion: "latest",
      sourceType: "module",
      globals: globals.node
    },
    rules: {
      "no-console": "off",
      "no-debugger": "error"
    }
  },
  {
    files: ["scripts/**/*.mjs", "eslint.config.mjs", "stylelint.config.mjs"],
    rules: {
      "no-unused-vars": [
        "error",
        {
          argsIgnorePattern: "^_",
          varsIgnorePattern: "^_",
          caughtErrorsIgnorePattern: "^_",
          ignoreRestSiblings: true
        }
      ]
    }
  }
);
