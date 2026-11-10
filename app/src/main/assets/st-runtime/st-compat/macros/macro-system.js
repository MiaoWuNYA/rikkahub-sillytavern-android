/**
 * rikkaST st-compat: macros/macro-system.js
 * ============================================================
 * 对齐 SillyTavern 1.19.0 `public/scripts/macros/macro-system.js`。
 */

import { MacroEngine } from './engine/MacroEngine.js';
import { MacroRegistry, MacroCategory, MacroValueType, initRegisterMacros as seedRegistryMacros } from './engine/MacroRegistry.js';
import { MacroLexer } from './engine/MacroLexer.js';
import { MacroParser } from './engine/MacroParser.js';
import { MacroCstWalker } from './engine/MacroCstWalker.js';
import { MacroEnvBuilder } from './engine/MacroEnvBuilder.js';

export { MacroEngine, MacroRegistry, MacroCategory, MacroValueType, MacroLexer, MacroParser, MacroCstWalker, MacroEnvBuilder };

export const macros = {
    engine: MacroEngine,
    registry: MacroRegistry,
    envBuilder: MacroEnvBuilder,
    lexer: MacroLexer,
    parser: MacroParser,
    cstWalker: MacroCstWalker,
    category: MacroCategory,
    valueType: MacroValueType,
    register: MacroRegistry.registerMacro.bind(MacroRegistry),
    registerAlias: MacroRegistry.registerMacroAlias.bind(MacroRegistry),
    unregister: MacroRegistry.unregisterMacro.bind(MacroRegistry),
    get: MacroRegistry.getMacro.bind(MacroRegistry),
    has: MacroRegistry.hasMacro.bind(MacroRegistry),
    list: MacroRegistry.getAllMacros.bind(MacroRegistry),
};

export function initRegisterMacros() { seedRegistryMacros(); }

export default macros;
