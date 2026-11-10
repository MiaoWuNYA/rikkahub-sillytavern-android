/**
 * rikkaST st-compat: macros/engine/MacroRegistry.js
 * ============================================================
 * 对齐 SillyTavern 1.15.0 ~ 1.19.0 `public/scripts/macros/engine/MacroRegistry.js`。
 * 预注册全部 82+ ST 1.19 内置具名宏及别名，并通过 `RikkaBridge.substituteParams` 委托 Kotlin `MacroEngine` 执行。
 */

import { MacrosParser } from '../../macros.js';
import { getLocalVariable, setLocalVariable, getGlobalVariable, setGlobalVariable } from '../../variables.js';

export const MacroCategory = {
    CHAT: 'chat',
    CHARACTER: 'character',
    ENVIRONMENT: 'environment',
    STATE: 'state',
    TIME: 'time',
    UTILITY: 'utility',
    VARIABLE: 'variable',
    RANDOM: 'random',
    NAMES: 'names',
    PROMPTS: 'prompts',
    INSTRUCT: 'instruct',
    CUSTOM: 'custom',
    MISC: 'misc',
    UNCATEGORIZED: 'uncategorized',
};

export const MacroValueType = {
    STRING: 'string',
    NUMBER: 'number',
    INTEGER: 'integer',
    BOOLEAN: 'boolean',
    ANY: 'any',
};

const registryMap = new Map();

function evalViaBridge(expr) {
    try {
        if (typeof window !== 'undefined' && window.RikkaBridge && typeof window.RikkaBridge.substituteParams === 'function') {
            return String(window.RikkaBridge.substituteParams(expr, '{}') ?? '');
        }
    } catch (_e) { /* noop */ }
    return '';
}

function seedBuiltinMacros() {
    const builtins = [
        // Core & Utility (18)
        { name: 'space', category: MacroCategory.UTILITY, description: 'Inserts spaces.' },
        { name: 'newline', category: MacroCategory.UTILITY, description: 'Inserts newlines.' },
        { name: 'noop', aliases: [{ alias: 'blank' }], category: MacroCategory.UTILITY, description: 'Outputs nothing.' },
        { name: 'trim', category: MacroCategory.UTILITY, description: 'Trims surrounding newlines.' },
        { name: 'if', category: MacroCategory.UTILITY, description: 'Conditional block macro.' },
        { name: 'else', category: MacroCategory.UTILITY, description: 'Else branch inside {{if}}.' },
        { name: 'input', category: MacroCategory.ENVIRONMENT, description: 'Current chat input text.' },
        { name: 'maxPrompt', aliases: [{ alias: 'maxPromptTokens' }], category: MacroCategory.STATE, description: 'Maximum prompt tokens.' },
        { name: 'maxContext', aliases: [{ alias: 'maxContextTokens' }], category: MacroCategory.STATE, description: 'Maximum context tokens.' },
        { name: 'maxResponse', aliases: [{ alias: 'maxResponseTokens' }], category: MacroCategory.STATE, description: 'Maximum response tokens.' },
        { name: 'reverse', category: MacroCategory.UTILITY, description: 'Reverses a string by code points.' },
        { name: '//', aliases: [{ alias: 'comment', visible: false }], category: MacroCategory.UTILITY, description: 'Comment macro.' },
        { name: 'roll', category: MacroCategory.RANDOM, description: 'Rolls dice using droll notation.' },
        { name: 'random', category: MacroCategory.RANDOM, list: true, description: 'Picks a random item on every evaluation.' },
        { name: 'pick', category: MacroCategory.RANDOM, list: true, description: 'Deterministically picks an item for the current chat and offset.' },
        { name: 'banned', category: MacroCategory.PROMPTS, description: 'Marks banned words.' },
        { name: 'summary', category: MacroCategory.PROMPTS, description: 'Current chat summary.' },
        { name: 'outlet', category: MacroCategory.PROMPTS, description: 'World Info outlet content.' },
        // Time & Date (8)
        { name: 'time', category: MacroCategory.TIME, description: 'Current local time.' },
        { name: 'date', category: MacroCategory.TIME, description: 'Current local date.' },
        { name: 'weekday', category: MacroCategory.TIME, description: 'Current day of the week.' },
        { name: 'isotime', category: MacroCategory.TIME, description: 'Current ISO time.' },
        { name: 'isodate', category: MacroCategory.TIME, description: 'Current ISO date.' },
        { name: 'datetimeformat', category: MacroCategory.TIME, description: 'Formats current date/time.' },
        { name: 'idleDuration', aliases: [{ alias: 'idle_duration' }], category: MacroCategory.TIME, description: 'Time elapsed since last message.' },
        { name: 'timeDiff', category: MacroCategory.TIME, description: 'Difference between two timestamps.' },
        // Chat (9)
        { name: 'lastMessage', aliases: [{ alias: 'lastChatMessage' }], category: MacroCategory.CHAT, description: 'Last chat message text.' },
        { name: 'lastMessageId', category: MacroCategory.CHAT, description: 'Last chat message 0-based index.' },
        { name: 'lastUserMessage', category: MacroCategory.CHAT, description: 'Last user message text.' },
        { name: 'lastCharMessage', category: MacroCategory.CHAT, description: 'Last character message text.' },
        { name: 'firstIncludedMessageId', category: MacroCategory.CHAT, description: 'First included context message ID.' },
        { name: 'firstDisplayedMessageId', category: MacroCategory.CHAT, description: 'First displayed message ID.' },
        { name: 'lastSwipeId', category: MacroCategory.CHAT, description: 'Total swipe count on last message.' },
        { name: 'currentSwipeId', category: MacroCategory.CHAT, description: '1-based current swipe index.' },
        { name: 'allChatRange', category: MacroCategory.CHAT, description: 'Range of all chat messages.' },
        // Names & Character & Environment (17)
        { name: 'user', category: MacroCategory.NAMES, description: 'Current user name.' },
        { name: 'char', category: MacroCategory.NAMES, description: 'Current character name.' },
        { name: 'group', aliases: [{ alias: 'charIfNotGroup' }], category: MacroCategory.NAMES, description: 'Group member names or character name.' },
        { name: 'groupNotMuted', category: MacroCategory.NAMES, description: 'Unmuted group member names.' },
        { name: 'notChar', category: MacroCategory.NAMES, description: 'Other participants excluding char.' },
        { name: 'charPrompt', aliases: [{ alias: 'char_prompt' }, { alias: 'system_prompt' }], category: MacroCategory.CHARACTER, description: 'Character system prompt.' },
        { name: 'charInstruction', aliases: [{ alias: 'charJailbreak' }, { alias: 'post_history_instructions' }, { alias: 'jailbreak' }], category: MacroCategory.CHARACTER, description: 'Post-history instructions.' },
        { name: 'charDescription', aliases: [{ alias: 'description' }], category: MacroCategory.CHARACTER, description: 'Character description.' },
        { name: 'charPersonality', aliases: [{ alias: 'personality' }], category: MacroCategory.CHARACTER, description: 'Character personality.' },
        { name: 'charScenario', aliases: [{ alias: 'scenario' }], category: MacroCategory.CHARACTER, description: 'Character scenario.' },
        { name: 'persona', category: MacroCategory.CHARACTER, description: 'User persona description.' },
        { name: 'mesExamplesRaw', aliases: [{ alias: 'mes_example_raw' }], category: MacroCategory.CHARACTER, description: 'Raw example dialogues.' },
        { name: 'mesExamples', aliases: [{ alias: 'mes_example' }], category: MacroCategory.CHARACTER, description: 'Formatted example dialogues.' },
        { name: 'charDepthPrompt', aliases: [{ alias: 'depth_prompt' }, { alias: 'char_depth_prompt' }], category: MacroCategory.CHARACTER, description: 'Character depth prompt.' },
        { name: 'charCreatorNotes', aliases: [{ alias: 'creatorNotes' }, { alias: 'creator_notes' }], category: MacroCategory.CHARACTER, description: 'Creator notes.' },
        { name: 'charFirstMessage', aliases: [{ alias: 'greeting' }, { alias: 'first_mes' }, { alias: 'firstMessage' }], category: MacroCategory.CHARACTER, description: 'Character first greeting.' },
        { name: 'charVersion', aliases: [{ alias: 'version' }, { alias: 'char_version' }], category: MacroCategory.CHARACTER, description: 'Character version.' },
        // State & System (8)
        { name: 'model', category: MacroCategory.STATE, description: 'Current active model name.' },
        { name: 'authorsNote', category: MacroCategory.PROMPTS, description: 'Current Author\'s Note.' },
        { name: 'charAuthorsNote', category: MacroCategory.PROMPTS, description: 'Character Author\'s Note.' },
        { name: 'defaultAuthorsNote', category: MacroCategory.PROMPTS, description: 'Default Author\'s Note.' },
        { name: 'original', category: MacroCategory.PROMPTS, description: 'Original overridden prompt content.' },
        { name: 'isMobile', category: MacroCategory.ENVIRONMENT, description: 'Whether running on mobile client.' },
        { name: 'lastGenerationType', category: MacroCategory.STATE, description: 'Last generation trigger type.' },
        { name: 'hasExtension', category: MacroCategory.ENVIRONMENT, description: 'Checks if an extension is installed and enabled.' },
        // Variables (18)
        { name: 'setvar', category: MacroCategory.VARIABLE, description: 'Sets a local chat variable.', handler: ({ unnamedArgs = [] } = {}) => { setLocalVariable(unnamedArgs[0] ?? '', unnamedArgs[1] ?? ''); return ''; } },
        { name: 'addvar', category: MacroCategory.VARIABLE, description: 'Adds to a local chat variable.' },
        { name: 'incvar', category: MacroCategory.VARIABLE, description: 'Increments a local chat variable.' },
        { name: 'decvar', category: MacroCategory.VARIABLE, description: 'Decrements a local chat variable.' },
        { name: 'getvar', category: MacroCategory.VARIABLE, description: 'Gets a local chat variable.', handler: ({ unnamedArgs = [] } = {}) => String(getLocalVariable(unnamedArgs[0] ?? '') ?? '') },
        { name: 'hasvar', aliases: [{ alias: 'varexists' }], category: MacroCategory.VARIABLE, description: 'Checks if a local variable exists.' },
        { name: 'deletevar', aliases: [{ alias: 'flushvar' }], category: MacroCategory.VARIABLE, description: 'Deletes a local chat variable.' },
        { name: 'setglobalvar', category: MacroCategory.VARIABLE, description: 'Sets a global variable.', handler: ({ unnamedArgs = [] } = {}) => { setGlobalVariable(unnamedArgs[0] ?? '', unnamedArgs[1] ?? ''); return ''; } },
        { name: 'addglobalvar', category: MacroCategory.VARIABLE, description: 'Adds to a global variable.' },
        { name: 'incglobalvar', category: MacroCategory.VARIABLE, description: 'Increments a global variable.' },
        { name: 'decglobalvar', category: MacroCategory.VARIABLE, description: 'Decrements a global variable.' },
        { name: 'getglobalvar', category: MacroCategory.VARIABLE, description: 'Gets a global variable.', handler: ({ unnamedArgs = [] } = {}) => String(getGlobalVariable(unnamedArgs[0] ?? '') ?? '') },
        { name: 'hasglobalvar', aliases: [{ alias: 'globalvarexists' }], category: MacroCategory.VARIABLE, description: 'Checks if a global variable exists.' },
        { name: 'deleteglobalvar', aliases: [{ alias: 'flushglobalvar' }], category: MacroCategory.VARIABLE, description: 'Deletes a global variable.' },
        { name: 'setvarkey', aliases: [{ alias: 'setvarindex' }], category: MacroCategory.VARIABLE, description: 'Sets a key/index in a local object or array.' },
        { name: 'getvarkey', aliases: [{ alias: 'getvarindex' }], category: MacroCategory.VARIABLE, description: 'Gets a key/index from a local object or array.' },
        { name: 'setglobalvarkey', aliases: [{ alias: 'setglobalvarindex' }], category: MacroCategory.VARIABLE, description: 'Sets a key/index in a global object or array.' },
        { name: 'getglobalvarkey', aliases: [{ alias: 'getglobalvarindex' }], category: MacroCategory.VARIABLE, description: 'Gets a key/index from a global object or array.' },
        // Instruct & Formatting (4)
        { name: 'exampleSeparator', aliases: [{ alias: 'chatSeparator' }], category: MacroCategory.INSTRUCT, description: 'Example dialogue separator.' },
        { name: 'chatStart', category: MacroCategory.INSTRUCT, description: 'Chat start marker.' },
        { name: 'systemPrompt', category: MacroCategory.INSTRUCT, description: 'Active system prompt.' },
        { name: 'defaultSystemPrompt', aliases: [{ alias: 'default_system_prompt' }], category: MacroCategory.INSTRUCT, description: 'Default system prompt.' },
    ];
    for (const def of builtins) {
        const entry = {
            name: def.name,
            aliases: def.aliases || [],
            category: def.category || MacroCategory.UTILITY,
            unnamedArgs: def.unnamedArgs || [],
            list: Boolean(def.list),
            description: def.description || '',
            returns: def.returns || '',
            exampleUsage: def.exampleUsage || [],
            handler: def.handler || ((ctx = {}) => {
                const args = Array.isArray(ctx.unnamedArgs) && ctx.unnamedArgs.length > 0
                    ? '::' + ctx.unnamedArgs.join('::')
                    : '';
                return evalViaBridge(`{{${def.name}${args}}}`);
            }),
        };
        registryMap.set(def.name, entry);
        for (const al of entry.aliases) {
            if (al && al.alias) {
                registryMap.set(String(al.alias), entry);
            }
        }
    }
}
seedBuiltinMacros();

export function initRegisterMacros() {
    if (registryMap.size === 0) seedBuiltinMacros();
}

export const MacroRegistry = {
    registerMacro(name, def = {}) {
        const entry = {
            name: String(name),
            aliases: Array.isArray(def.aliases) ? def.aliases : [],
            category: def.category || MacroCategory.CUSTOM,
            unnamedArgs: Array.isArray(def.unnamedArgs) ? def.unnamedArgs : [],
            list: Boolean(def.list),
            description: String(def.description || ''),
            returns: String(def.returns || ''),
            exampleUsage: Array.isArray(def.exampleUsage) ? def.exampleUsage : [],
            handler: typeof def.handler === 'function' ? def.handler : (() => String(def.value ?? '')),
        };
        registryMap.set(entry.name, entry);
        try { MacrosParser.registerMacro(entry.name, entry.handler); } catch (_e) { /* noop */ }
        return entry;
    },
    registerMacroAlias(alias, target, { visible = true } = {}) {
        const targetEntry = registryMap.get(String(target));
        if (targetEntry) {
            targetEntry.aliases = targetEntry.aliases || [];
            targetEntry.aliases.push({ alias: String(alias), visible: Boolean(visible) });
            registryMap.set(String(alias), targetEntry);
        }
        return targetEntry || null;
    },
    getPrimaryMacro(name) {
        return registryMap.get(String(name)) || null;
    },
    unregisterMacro(name) {
        registryMap.delete(String(name));
        try { MacrosParser.unregisterMacro(String(name)); } catch (_e) { /* noop */ }
    },
    getMacro(name) {
        return registryMap.get(String(name)) || null;
    },
    hasMacro(name) {
        return registryMap.has(String(name));
    },
    getAllMacros({ excludeAliases = false } = {}) {
        const seen = new Set();
        const out = [];
        for (const [k, entry] of registryMap.entries()) {
            if (excludeAliases && k !== entry.name) continue;
            if (seen.has(entry.name) && excludeAliases) continue;
            seen.add(entry.name);
            out.push(entry);
        }
        for (const [k, v] of MacrosParser.macros.entries()) {
            if (!registryMap.has(k)) {
                out.push({
                    name: k,
                    aliases: [],
                    category: MacroCategory.CUSTOM,
                    unnamedArgs: [],
                    list: false,
                    description: '',
                    returns: '',
                    exampleUsage: [],
                    handler: typeof v === 'function' ? v : () => String(v ?? ''),
                });
            }
        }
        return out;
    },
};

export default MacroRegistry;
