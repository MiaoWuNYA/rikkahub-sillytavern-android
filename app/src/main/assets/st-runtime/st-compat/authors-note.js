/**
 * rikkaST st-compat: authors-note.js
 * ============================================================
 * 对齐 SillyTavern `public/scripts/authors-note.js`。
 */

const st = () => (window.SillyTavern && window.SillyTavern.getContext ? window.SillyTavern.getContext() : {});

export const NOTE_MODULE_NAME = 'authorsNote';

export const metadata_keys = {
    prompt: 'note_prompt',
    interval: 'note_interval',
    position: 'note_position',
    depth: 'note_depth',
    role: 'note_role',
};

export function shouldWIAddPrompt() {
    try {
        const ctx = st();
        const meta = (ctx && ctx.chatMetadata) || {};
        const prompt = meta.note_prompt || '';
        const interval = Number(meta.note_interval ?? 1);
        if (!prompt || interval <= 0) return false;
        const chat = Array.isArray(ctx && ctx.chat) ? ctx.chat : [];
        const userCount = chat.filter((m) => m && m.is_user).length;
        return userCount > 0 && (userCount % interval === 0);
    } catch (_e) {
        return false;
    }
}

// ---- [ST 1.19.0 Full Export Parity] ----
export function initAuthorsNote(..._args) { return null; }
export function setFloatingPrompt(..._args) { return null; }
