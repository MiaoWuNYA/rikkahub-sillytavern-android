/**
 * rikkaST st-compat: extensions/regex/engine.js
 * ============================================================
 * 移植自 ST1.18 `public/scripts/extensions/regex/engine.js`（核心执行语义对齐）。
 *
 * 第三方扩展（如 JS-Slash-Runner）经 `../../../../../scripts/extensions/regex/engine.js`
 * import 本模块；import 说明符与 ST 完全一致，由宿主 URL 归一化后路由到本 shim。
 *
 * 差异说明：
 * - 预设/作用域脚本依赖的宿主能力（preset manager / 角色卡写回）在本层为安全降级；
 * - getRegexedString / runRegexScript 为完整实现（真正执行正则替换）。
 */

import { characters, saveSettingsDebounced, substituteParams, substituteParamsExtended, this_chid } from '../../../script.js';
import { extension_settings, writeExtensionField } from '../../extensions.js';
import { getPresetManager } from '../../preset-manager.js';
import {
    extractInlineRegexFlags,
    isLikelyCatastrophicRegex,
    regexFromString,
    replaceCompatString,
    stripUnmatchedBoundaryWrappers,
} from '../../utils.js';
import { lodash } from '../../../lib.js';

export { extractInlineRegexFlags, isLikelyCatastrophicRegex, replaceCompatString, stripUnmatchedBoundaryWrappers };

/** @enum {number} Regex scripts types（顺序即优先级） */
export const SCRIPT_TYPES = {
    // ORDER MATTERS: defines the regex script priority
    GLOBAL: 0,
    PRESET: 2,
    SCOPED: 1,
};

/** Special type for unknown/invalid script types. */
export const SCRIPT_TYPE_UNKNOWN = -1;

const DEFAULT_GET_REGEX_SCRIPTS_OPTIONS = Object.freeze({ allowedOnly: false });

/** Manages the compiled regex cache with LRU eviction. */
export class RegexProvider {
    constructor() {
        this._cache = new Map();
        this._maxSize = 1000;
    }

    /**
     * Gets a regex instance by its string representation and optional flags/scriptOptions.
     * @param {string} regexString The regex string to retrieve
     * @param {string} [flags=''] Optional explicit regex flags
     * @param {string} [scriptOptions=''] Optional script options key
     * @returns {RegExp?} Compiled regex or null if invalid
     */
    get(regexString, flags = '', scriptOptions = '') {
        const cacheKey = (flags || scriptOptions)
            ? `${regexString}\u0000${flags}\u0000${scriptOptions}`
            : String(regexString);
        const isCached = this._cache.has(cacheKey);
        const regex = isCached
            ? this._cache.get(cacheKey)
            : regexFromString(regexString, flags);

        if (!regex) {
            return null;
        }
        if (regex._isCatastrophic === undefined) {
            regex._isCatastrophic = isCatastrophicRegexPattern(regexString);
        }

        if (isCached) {
            // LRU: Move to end by re-inserting
            this._cache.delete(cacheKey);
            this._cache.set(cacheKey, regex);
        } else {
            // Evict oldest if at capacity
            if (this._cache.size >= this._maxSize) {
                const firstKey = this._cache.keys().next().value;
                this._cache.delete(firstKey);
            }
            this._cache.set(cacheKey, regex);
        }

        // Reset lastIndex for global/sticky regexes
        if (regex.global || regex.sticky) {
            regex.lastIndex = 0;
        }

        return regex;
    }

    /** Clears the entire cache. */
    clear() {
        this._cache.clear();
    }
}

RegexProvider.instance = new RegexProvider();

/**
 * Retrieves the list of regex scripts by combining the scripts from the extension settings and the character data.
 * @param {object} options Options for retrieving the regex scripts
 * @returns {object[]} An array of regex scripts
 */
export function getRegexScripts(options = DEFAULT_GET_REGEX_SCRIPTS_OPTIONS) {
    return [...Object.values(SCRIPT_TYPES).flatMap((type) => getScriptsByType(type, options))];
}

/**
 * Retrieves the regex scripts for a specific type.
 * @param {number} scriptType The type of regex scripts to retrieve
 * @param {object} options Options for retrieving the regex scripts
 * @returns {object[]} An array of regex scripts for the specified type
 */
export function getScriptsByType(scriptType, { allowedOnly } = DEFAULT_GET_REGEX_SCRIPTS_OPTIONS) {
    switch (scriptType) {
        case SCRIPT_TYPE_UNKNOWN:
            return [];
        case SCRIPT_TYPES.GLOBAL:
            return extension_settings.regex ?? [];
        case SCRIPT_TYPES.SCOPED: {
            if (allowedOnly && !extension_settings?.character_allowed_regex?.includes(characters?.[this_chid]?.avatar)) {
                return [];
            }
            const scopedScripts = characters[this_chid]?.data?.extensions?.regex_scripts;
            return Array.isArray(scopedScripts) ? scopedScripts : [];
        }
        case SCRIPT_TYPES.PRESET: {
            if (allowedOnly && !extension_settings?.preset_allowed_regex?.[getCurrentPresetAPI()]?.includes(getCurrentPresetName())) {
                return [];
            }
            const presetManager = getPresetManager();
            const presetScripts = presetManager?.readPresetExtensionField?.({ path: 'regex_scripts' });
            return Array.isArray(presetScripts) ? presetScripts : [];
        }
        default:
            console.warn(`getScriptsByType: Invalid script type ${scriptType}`);
            return [];
    }
}

/**
 * Saves an array of regex scripts for a specific type.
 * @param {object[]} scripts An array of regex scripts to save
 * @param {number} scriptType The type of regex scripts to save
 */
export async function saveScriptsByType(scripts, scriptType) {
    switch (scriptType) {
        case SCRIPT_TYPES.GLOBAL:
            extension_settings.regex = scripts;
            saveSettingsDebounced();
            break;
        case SCRIPT_TYPES.SCOPED:
            try { await writeExtensionField(this_chid, 'regex_scripts', scripts); } catch (_e) { /* noop */ }
            break;
        case SCRIPT_TYPES.PRESET: {
            const presetManager = getPresetManager();
            try { await presetManager.writePresetExtensionField({ path: 'regex_scripts', value: scripts }); } catch (_e) { /* noop */ }
            break;
        }
        default:
            console.warn(`saveScriptsByType: Invalid script type ${scriptType}`);
            break;
    }
}

/**
 * Check if character's regexes are allowed to be used; if character is undefined, returns false
 * @param {object|undefined} character
 * @returns {boolean}
 */
export function isScopedScriptsAllowed(character) {
    return !!extension_settings?.character_allowed_regex?.includes(character?.avatar);
}

/**
 * Allow character's regexes to be used; if character is undefined, do nothing
 * @param {object|undefined} character
 */
export function allowScopedScripts(character) {
    const avatar = character?.avatar;
    if (!avatar) {
        return;
    }
    if (!Array.isArray(extension_settings?.character_allowed_regex)) {
        extension_settings.character_allowed_regex = [];
    }
    if (!extension_settings.character_allowed_regex.includes(avatar)) {
        extension_settings.character_allowed_regex.push(avatar);
        saveSettingsDebounced();
    }
}

/**
 * Disallow character's regexes to be used; if character is undefined, do nothing
 * @param {object|undefined} character
 */
export function disallowScopedScripts(character) {
    const avatar = character?.avatar;
    if (!avatar) {
        return;
    }
    if (!Array.isArray(extension_settings?.character_allowed_regex)) {
        return;
    }
    const index = extension_settings.character_allowed_regex.indexOf(avatar);
    if (index !== -1) {
        extension_settings.character_allowed_regex.splice(index, 1);
        saveSettingsDebounced();
    }
}

/**
 * Check if preset's regexes are allowed to be used
 * @param {string} apiId API ID
 * @param {string} presetName Preset name
 * @returns {boolean} True if allowed, false if not
 */
export function isPresetScriptsAllowed(apiId, presetName) {
    if (!apiId || !presetName) {
        return false;
    }
    return !!extension_settings?.preset_allowed_regex?.[apiId]?.includes(presetName);
}

/**
 * Allow preset's regexes to be used
 * @param {string} apiId API ID
 * @param {string} presetName Preset name
 */
export function allowPresetScripts(apiId, presetName) {
    if (!apiId || !presetName) {
        return;
    }
    if (!Array.isArray(extension_settings?.preset_allowed_regex?.[apiId])) {
        try {
            lodash.set(extension_settings, ['preset_allowed_regex', apiId], []);
        } catch (_e) {
            if (!extension_settings.preset_allowed_regex) extension_settings.preset_allowed_regex = {};
            extension_settings.preset_allowed_regex[apiId] = [];
        }
    }
    if (!extension_settings.preset_allowed_regex[apiId].includes(presetName)) {
        extension_settings.preset_allowed_regex[apiId].push(presetName);
        saveSettingsDebounced();
    }
}

/**
 * Disallow preset's regexes to be used
 * @param {string} apiId API ID
 * @param {string} presetName Preset name
 */
export function disallowPresetScripts(apiId, presetName) {
    if (!apiId || !presetName) {
        return;
    }
    if (!Array.isArray(extension_settings?.preset_allowed_regex?.[apiId])) {
        return;
    }
    const index = extension_settings.preset_allowed_regex[apiId].indexOf(presetName);
    if (index !== -1) {
        extension_settings.preset_allowed_regex[apiId].splice(index, 1);
        saveSettingsDebounced();
    }
}

/**
 * Gets the current API ID from the preset manager.
 * @returns {string|null} Current API ID, or null if no preset manager
 */
export function getCurrentPresetAPI() {
    return getPresetManager()?.apiId ?? null;
}

/**
 * Gets the name of the currently selected preset.
 * @returns {string|null} The name of the currently selected preset, or null if no preset manager
 */
export function getCurrentPresetName() {
    return getPresetManager()?.getSelectedPresetName() ?? null;
}

/** @enum {number} Where the regex script should be applied */
export const regex_placement = {
    /**
     * @deprecated MD Display is deprecated. Do not use.
     */
    MD_DISPLAY: 0,
    USER_INPUT: 1,
    AI_OUTPUT: 2,
    SLASH_COMMAND: 3,
    // 4 - sendAs (legacy)
    WORLD_INFO: 5,
    REASONING: 6,
};

/** @enum {number} How to substitute parameters in the find regex */
export const substitute_find_regex = {
    NONE: 0,
    RAW: 1,
    ESCAPED: 2,
};

function sanitizeRegexMacro(x) {
    return (x && typeof x === 'string') ?
        x.replaceAll(/[\n\r\t\v\f\0.^$*+?{}[\]\\/|()]/gs, function (s) {
            switch (s) {
                case '\n':
                    return '\\n';
                case '\r':
                    return '\\r';
                case '\t':
                    return '\\t';
                case '\v':
                    return '\\v';
                case '\f':
                    return '\\f';
                case '\0':
                    return '\\0';
                default:
                    return '\\' + s;
            }
        }) : x;
}

/**
 * Parent function to fetch a regexed version of a raw string
 * @param {string} rawString The raw string to be regexed
 * @param {number} placement The placement of the string
 * @param {object} params The parameters to use for the regex script
 * @returns {string} The regexed string
 */
export function getRegexedString(rawString, placement, { characterOverride, isMarkdown, isPrompt, isEdit, depth } = {}) {
    // WTF have you passed me?
    if (typeof rawString !== 'string') {
        console.warn('getRegexedString: rawString is not a string. Returning empty string.');
        return '';
    }

    let finalString = rawString;
    if ((extension_settings.disabledExtensions || []).includes('regex') || !rawString || placement === undefined) {
        return finalString;
    }

    const allRegex = getRegexScripts({ allowedOnly: true });
    allRegex.forEach((script) => {
        if (!script || typeof script !== 'object') {
            return;
        }
        if (
            // Script applies to Markdown and input is Markdown
            (script.markdownOnly && isMarkdown) ||
            // Script applies to Generate and input is Generate
            (script.promptOnly && isPrompt) ||
            // Script applies to all cases when neither "only"s are true, but there's no need to do it when `isMarkdown`, the as source (chat history) should already be changed beforehand
            (!script.markdownOnly && !script.promptOnly)
        ) {
            if (isEdit && !script.runOnEdit) {
                console.debug(`getRegexedString: Skipping script ${script.scriptName} because it does not run on edit`);
                return;
            }

            // Check if the depth is within the min/max depth
            if (typeof depth === 'number') {
                if (!isNaN(script.minDepth) && script.minDepth !== null && script.minDepth >= -1 && depth < script.minDepth) {
                    console.debug(`getRegexedString: Skipping script ${script.scriptName} because depth ${depth} is less than minDepth ${script.minDepth}`);
                    return;
                }

                if (!isNaN(script.maxDepth) && script.maxDepth !== null && script.maxDepth >= 0 && depth > script.maxDepth) {
                    console.debug(`getRegexedString: Skipping script ${script.scriptName} because depth ${depth} is greater than maxDepth ${script.maxDepth}`);
                    return;
                }
            }

            if (Array.isArray(script.placement) && script.placement.includes(placement)) {
                finalString = runRegexScript(script, finalString, { characterOverride });
            }
        }
    });

    return finalString;
}

/**
 * Runs the provided regex script on the given string
 * @param {object} regexScript The regex script to run
 * @param {string} rawString The string to run the regex script on
 * @param {object} params The parameters to use for the regex script
 * @returns {string} The new string
 */
export function runRegexScript(regexScript, rawString, { characterOverride } = {}) {
    let newString = typeof rawString === 'string' && rawString.includes('\r')
        ? rawString.replace(/\r\n?/g, '\n')
        : rawString;
    if (!regexScript || !!(regexScript.disabled) || !regexScript?.findRegex || !newString) {
        return newString;
    }

    const getRegexString = () => {
        switch (Number(regexScript.substituteRegex || 0)) {
            case substitute_find_regex.NONE:
                return regexScript.findRegex;
            case substitute_find_regex.RAW:
                return substituteParamsExtended(regexScript.findRegex);
            case substitute_find_regex.ESCAPED:
                return substituteParamsExtended(regexScript.findRegex, {}, sanitizeRegexMacro);
            default:
                console.warn(`runRegexScript: Unknown substituteRegex value ${regexScript.substituteRegex}. Using raw regex.`);
                return regexScript.findRegex;
        }
    };
    const regexString = getRegexString();
    const scriptOptionsKey = `${Number(regexScript.substituteRegex || 0)}:${Boolean(regexScript.markdownOnly)}:${Boolean(regexScript.promptOnly)}`;
    const findRegex = RegexProvider.instance.get(regexString, '', scriptOptionsKey);

    // The user skill issued. Return with nothing.
    if (!findRegex) {
        return newString;
    }
    if (findRegex._isCatastrophic && newString.length >= MIN_CATASTROPHIC_INPUT_CHARS) {
        console.warn(`[regex] skipped potentially catastrophic regex on input (${newString.length} chars): ${regexString}`);
        return newString;
    }

    // Run replacement. Single-pass token replacement supporting $$, $&, {{match}}, $0..$99, and $<name>
    newString = newString.replace(findRegex, function (fullMatch) {
        const args = [...arguments];
        const hasNamedGroups = typeof args[args.length - 1] === 'object' && args[args.length - 1] !== null;
        const offsetArgIdx = hasNamedGroups ? args.length - 3 : args.length - 2;
        const groupsObj = hasNamedGroups ? args[args.length - 1] : null;
        const rawReplace = String(regexScript.replaceString == null ? '' : regexScript.replaceString);
        const replaceWithGroups = rawReplace.replaceAll(
            /\{\{match\}\}|\$\$|\$&|\$(\d+)|\$<([^>]+)>/gi,
            (token, num, groupName) => {
                if (token === '$$') {
                    return '$';
                }
                let target = undefined;
                if (token === '$&' || token.toLowerCase() === '{{match}}') {
                    target = fullMatch;
                } else if (num !== undefined) {
                    const idx = Number(num);
                    target = idx === 0 ? fullMatch : (idx >= 1 && idx < offsetArgIdx ? args[idx] : undefined);
                } else if (groupName !== undefined) {
                    target = groupsObj ? groupsObj[groupName] : undefined;
                }
                if (target === undefined || target === null || target === '') {
                    return '';
                }
                return filterString(String(target), regexScript.trimStrings || [], { characterOverride });
            },
        );

        // Substitute at the end
        return substituteParams(replaceWithGroups);
    });

    return newString;
}

/**
 * Filters anything to trim from the regex match
 * @param {string} rawString The raw string to filter
 * @param {string[]} trimStrings The strings to trim
 * @param {object} params The parameters to use for the regex filter
 * @returns {string} The filtered string
 */
function filterString(rawString, trimStrings, { characterOverride } = {}) {
    let finalString = rawString;
    trimStrings.forEach((trimString) => {
        const subTrimString = substituteParams(trimString, { name2Override: characterOverride });
        finalString = finalString.replaceAll(subTrimString, '');
    });

    return finalString;
}

export const MIN_CATASTROPHIC_INPUT_CHARS = 16;
export const MAX_CATASTROPHIC_INPUT_CHARS = 4096;

/**
 * Detects nested quantifiers and overlapping alternations inside repeated groups
 * (e.g. /(a+)+b/, /((a+))+b/, /(a|aa)+b/, /([a-z]+|[a-z0-9]+)+!/, /(a+){1,50}b/)
 * that can trigger exponential catastrophic backtracking on non-matching inputs.
 * @param {string} pattern
 * @returns {boolean}
 */
export function isCatastrophicRegexPattern(pattern) {
    return isLikelyCatastrophicRegex(pattern);
}
