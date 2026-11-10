/**
 * rikkaST st-compat: macros/engine/MacroFlags.js
 */
export const MacroFlagType = Object.freeze({
    IMMEDIATE: '!',
    DELAYED: '?',
    REEVALUATE: '~',
    FILTER: '>',
    CLOSING_BLOCK: '/',
    PRESERVE_WHITESPACE: '#',
});

export const MacroFlagDefinitions = new Map([
    ['!', { type: MacroFlagType.IMMEDIATE, name: 'immediate' }],
    ['?', { type: MacroFlagType.DELAYED, name: 'delayed' }],
    ['~', { type: MacroFlagType.REEVALUATE, name: 'reevaluate' }],
    ['>', { type: MacroFlagType.FILTER, name: 'filter' }],
    ['/', { type: MacroFlagType.CLOSING_BLOCK, name: 'closingBlock' }],
    ['#', { type: MacroFlagType.PRESERVE_WHITESPACE, name: 'preserveWhitespace' }],
]);

export const ValidFlagSymbols = new Set(Object.values(MacroFlagType));

export function createEmptyFlags() {
    return {
        immediate: false,
        delayed: false,
        reevaluate: false,
        filter: false,
        closingBlock: false,
        preserveWhitespace: false,
        raw: [],
    };
}

export function parseFlags(flagSymbols = []) {
    const flags = createEmptyFlags();
    for (const sym of flagSymbols) {
        if (ValidFlagSymbols.has(sym)) {
            flags.raw.push(sym);
            const def = MacroFlagDefinitions.get(sym);
            if (def) flags[def.name] = true;
        }
    }
    return flags;
}

export function hasAnyFlag(flags) {
    return Boolean(flags && Array.isArray(flags.raw) && flags.raw.length > 0);
}

export function getFlagDefinition(symbol) {
    return MacroFlagDefinitions.get(symbol) || null;
}

export function isValidFlag(symbol) {
    return ValidFlagSymbols.has(symbol);
}

export const MacroFlags = MacroFlagType;
