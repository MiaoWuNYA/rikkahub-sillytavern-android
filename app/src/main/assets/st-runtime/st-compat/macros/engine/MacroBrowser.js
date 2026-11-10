/**
 * rikkaST st-compat: macros/engine/MacroBrowser.js
 */
export class MacroBrowser {}
export function getMacrosHelp() { return ''; }
export function formatMacroSignature(macro) { return macro && macro.name ? `{{${macro.name}}}` : ''; }
export function createSourceIndicator() { return null; }
export function createAliasIndicator() { return null; }
export function createTypeBadge() { return null; }
export function renderMacroDetails() { return null; }
