/**
 * rikkaST st-compat: personas.js
 * ============================================================
 * 对齐 SillyTavern `public/scripts/personas.js`，桥接宿主 Persona 与Slash 命令。
 */

const st = () => (window.SillyTavern && window.SillyTavern.getContext ? window.SillyTavern.getContext() : {});

export let user_avatar = 'user-default.png';

export function getUserAvatar() {
    const ctx = st();
    return (ctx && ctx.userAvatar) || user_avatar || 'user-default.png';
}

export function getUserAvatars() {
    const ctx = st();
    if (ctx && Array.isArray(ctx.personas)) {
        return ctx.personas.map((p) => (p && (p.avatar || p.name)) || '').filter(Boolean);
    }
    return [getUserAvatar()];
}

export async function setUserAvatar(avatarOrName) {
    if (!avatarOrName) return;
    user_avatar = String(avatarOrName);
    try {
        const ctx = st();
        if (ctx && typeof ctx.executeSlashCommandsWithOptions === 'function') {
            await ctx.executeSlashCommandsWithOptions(`/persona ${avatarOrName}`);
        }
    } catch (_e) { /* noop */ }
}

export async function getUserPersona() {
    const ctx = st();
    if (ctx && ctx.extensionSettings && ctx.extensionSettings.persona_description) {
        return String(ctx.extensionSettings.persona_description);
    }
    return '';
}

// ---- [ST 1.19.0 Full Export Parity] ----
export async function askForPersonaSelection(..._args) { return null; }
export async function autoSelectPersona(..._args) { return null; }
export function buildPersonaAvatarList(..._args) { return null; }
export async function convertCharacterToPersona(..._args) { return null; }
export async function createPersona(..._args) { return null; }
export function getConnectedPersonas(..._args) { return null; }
export function getCurrentConnectionObj(..._args) { return null; }
export function getOrCreatePersonaDescriptor(..._args) { return null; }
export async function initPersona(..._args) { return null; }
export async function initPersonas(..._args) { return null; }
export function initUserAvatar(..._args) { return null; }
export function isPersonaConnectionLocked(..._args) { return null; }
export function isPersonaLocked(..._args) { return null; }
export function isPersonaPanelOpen(..._args) { return null; }
export const persona_description_positions = { IN_PROMPT: 0, AFTER_CHAR: 1, TOP_AN: 2, BOTTOM_AN: 3, AT_DEPTH: 4, NONE: 9 };
export const personasFilter = {};
export async function retriggerFirstMessageOnEmptyChat(..._args) { return null; }
export function setPersonaDescription(..._args) { return null; }
export async function setPersonaLockState(..._args) { return null; }
export async function showCharConnections(..._args) { return null; }
export async function togglePersonaLock(..._args) { return null; }
export function updatePersonaConnectionsAvatarList(..._args) { return null; }
