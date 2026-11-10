// rikkaST st-compat: scripts/textgen-settings.js
export const textgen_types = {
    OOBA: 'ooba',
    MANCER: 'mancer',
    VLLM: 'vllm',
    APHRODITE: 'aphrodite',
    TABBY: 'tabby',
    KOBOLDCPP: 'koboldcpp',
    TOGETHERAI: 'togetherai',
    LLAMACPP: 'llamacpp',
    OLAPHRODITE: 'olaphrodite',
    INFERMATICAI: 'infermaticai',
    DREAMGEN: 'dreamgen',
    OPENROUTER: 'openrouter',
    FEATHERLESS: 'featherless',
    GENERIC: 'generic',
};
export const textgenerationwebui_settings = { type: 'ooba', temp: 1.0, max_new_tokens: 512 };
export const textgenerationwebui_presets = [];
export const textgenerationwebui_preset_names = {};
export const textgenerationwebui_banned_in_macros = [];
export const setting_names = [];
export const SERVER_INPUTS = {};
export const APHRODITE_DEFAULT_ORDER = [];
export const DREAMGEN_SERVER = '';
export const FEATHERLESS_SERVER = '';
export const INFERMATICAI_SERVER = '';
export const MANCER_SERVER = '';
export const OPENROUTER_SERVER = '';
export const TOGETHERAI_SERVER = '';

export function createTextGenGenerationData(prompt) { return { prompt }; }
export function formatTextGenURL(url) { return String(url ?? ''); }
export async function generateTextGenWithStreaming() { return () => ({}); }
export function getLogprobsNumber() { return 0; }
export function getTextGenGenerationData(prompt) { return { prompt }; }
export function getTextGenModel() { return ''; }
export function getTextGenServer() { return ''; }
export function initTextGenSettings() {}
export function isJsonSchemaSupported() { return true; }
export function loadTextGenSettings() {}
export function parseTabbyLogprobs() { return null; }
export function parseTextgenLogprobs() { return null; }
export function replaceMacrosInList(list) { return Array.isArray(list) ? list : []; }
export function showTGSamplerControls() {}
export function validateTextGenUrl(url) { return String(url ?? ''); }
