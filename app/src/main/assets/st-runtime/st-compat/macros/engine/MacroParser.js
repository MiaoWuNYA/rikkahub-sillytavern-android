/**
 * rikkaST st-compat: macros/engine/MacroParser.js
 */
export const MacroParser = {
    parse(input) {
        return { cst: { name: 'document', children: {} }, lexErrors: [], parseErrors: [], input: String(input ?? '') };
    },
    parseDocument(input) {
        return { cst: { name: 'document', children: {} }, errors: [], input: String(input ?? '') };
    },
};
export default MacroParser;
