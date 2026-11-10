/**
 * rikkaST st-compat: util/AccountStorage.js
 * ============================================================
 * 对齐 SillyTavern `public/scripts/util/AccountStorage.js`，提供基于 localStorage + 内存回退的键值存储。
 */

const memoryStore = new Map();

export class AccountStorage {
    init() {
        return Promise.resolve();
    }

    getState() {
        const out = {};
        try {
            if (typeof window !== 'undefined' && window.localStorage) {
                for (let i = 0; i < window.localStorage.length; i++) {
                    const k = window.localStorage.key(i);
                    if (k != null) out[k] = window.localStorage.getItem(k);
                }
            }
        } catch (_e) { /* noop */ }
        for (const [k, v] of memoryStore.entries()) {
            out[k] = v;
        }
        return out;
    }

    getItem(key) {
        const k = String(key ?? '');
        try {
            if (typeof window !== 'undefined' && window.localStorage) {
                const v = window.localStorage.getItem(k);
                if (v !== null) return v;
            }
        } catch (_e) { /* noop */ }
        return memoryStore.has(k) ? memoryStore.get(k) : null;
    }

    setItem(key, value) {
        const k = String(key ?? '');
        const v = String(value ?? '');
        memoryStore.set(k, v);
        try {
            if (typeof window !== 'undefined' && window.localStorage) {
                window.localStorage.setItem(k, v);
            }
        } catch (_e) { /* noop */ }
    }

    removeItem(key) {
        const k = String(key ?? '');
        memoryStore.delete(k);
        try {
            if (typeof window !== 'undefined' && window.localStorage) {
                window.localStorage.removeItem(k);
            }
        } catch (_e) { /* noop */ }
    }

    clear() {
        memoryStore.clear();
    }
}

export const accountStorage = new AccountStorage();
export default accountStorage;
