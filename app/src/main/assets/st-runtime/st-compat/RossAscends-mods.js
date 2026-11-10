/**
 * rikkaST st-compat: RossAscends-mods.js
 * ============================================================
 * 模拟 SillyTavern `public/scripts/RossAscends-mods.js` 的常用导出（P0）。
 */

export const favsToHotswap = [];

export const isMobile = true;

export function shouldSendOnEnter() {
    return false;
}

export function getMessageTimeStamp(timestamp) {
    try {
        const d = timestamp ? new Date(timestamp) : new Date();
        return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    } catch (_e) {
        return '';
    }
}

// ---- [ST 1.19.0 Full Export Parity] ----
export async function RA_CountCharTokens() { return 0; }
export const autoFitSendTextAreaDebounced = () => {};
export function dragElement() {}
export function getParsedUA() { return typeof navigator !== 'undefined' ? navigator.userAgent : ''; }
export function humanizeGenTime(ms = 0) { return `${Math.max(0, Number(ms) || 0)}ms`; }
export function humanizedDateTime() { return new Date().toISOString().replace(/[:.]/g, '-'); }
export async function initMovingUI() {}
export function initRossMods() {}
