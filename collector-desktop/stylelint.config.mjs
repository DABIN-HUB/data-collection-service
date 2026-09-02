export default {
  extends: ["stylelint-config-standard", "stylelint-config-recommended-vue"],
  ignoreFiles: [
    "node_modules/**",
    "dist/**",
    "release/**",
    "coverage/**",
    "../collector-boot/src/main/resources/static/desktop/**"
  ],
  rules: {
    "alpha-value-notation": null,
    "color-function-alias-notation": null,
    "color-function-notation": null,
    "color-hex-length": null,
    "custom-property-pattern": null,
    "declaration-empty-line-before": null,
    "declaration-block-no-redundant-longhand-properties": null,
    "font-family-name-quotes": null,
    "function-url-quotes": null,
    "keyframes-name-pattern": null,
    "media-feature-range-notation": null,
    "no-descending-specificity": null,
    "no-duplicate-selectors": true,
    "rule-empty-line-before": null,
    "selector-class-pattern": null,
    "selector-pseudo-class-no-unknown": [
      true,
      {
        ignorePseudoClasses: ["deep", "global", "slotted"]
      }
    ],
    "selector-pseudo-element-no-unknown": [
      true,
      {
        ignorePseudoElements: ["v-deep", "v-global", "v-slotted"]
      }
    ],
    "value-keyword-case": null
  }
};
