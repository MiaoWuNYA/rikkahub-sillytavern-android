/**
 * rikkaST st-compat: macros/engine/MacroLexer.js
 */
export const MACRO_IDENTIFIER_PATTERN = /^[a-zA-Z][\w-_]*$/;
export const MACRO_VARIABLE_SHORTHAND_PATTERN = /[a-zA-Z](?:[\w\-_]*[\w])?/;

export const MacroLexer = {
    tokenize(input) {
        return { tokens: [], groups: {}, errors: [], input: String(input ?? '') };
    },
};
export default MacroLexer;
