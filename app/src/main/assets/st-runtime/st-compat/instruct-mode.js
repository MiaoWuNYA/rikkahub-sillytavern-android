// rikkaST st-compat: scripts/instruct-mode.js
export const instruct_presets = [];
export const names_behavior_types = { NONE: -1, DEFAULT: 0, COMPLETION: 1, CONTENT: 2 };
export const force_output_sequence = { FIRST: 1, LAST: 2, ALL: 3 };
export function autoSelectInstructPreset() {}
export function formatInstructModeChat(_name, mes) { return String(mes ?? ''); }
export function formatInstructModeExamples(mes) { return String(mes ?? ''); }
export function formatInstructModePrompt(_name, _isImpersonate, prompt) { return String(prompt ?? ''); }
export function formatInstructModeStoryString(story) { return String(story ?? ''); }
export function formatInstructModeSystemPrompt(sys) { return String(sys ?? ''); }
export function getInstructMacros() { return []; }
export function getInstructStoppingSequences() { return []; }
export async function loadInstructMode() {}
export function selectContextPreset() {}
export function selectInstructPreset() {}
export function updateBindModelTemplatesState() {}
