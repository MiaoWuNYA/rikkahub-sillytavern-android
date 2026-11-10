/**
 * rikkaST st-compat: preset-manager.js
 * ============================================================
 * 模拟 SillyTavern `public/scripts/preset-manager.js`。
 *
 * ★ v203 修复（真机 TypeError: Cannot read properties of undefined (reading '0')）
 * 旧实现 `getPresetList()` 返回的是 **裸数组** `[]`；而 ST 的真实契约是
 *     { presets: [...], preset_names: [...] }
 * JSR(酒馆助手) 的 `store/settings/preset.ts` 里直接这么用：
 *     Tk.getPresetList().presets[Number(e)]        // ← presets 为 undefined 时，
 *     _.get(r.preset_names, t, -1)                 //    再取 [Number(e)] 就是 "reading '0'"
 * 真机报错栈（JSR dist/index.js:74:147795，函数 dF）正是这一行。
 * → 只要把返回值补成 ST 的形状，这个报错就消失（宿主侧修，不动扩展源码）。
 *
 * 注意：本 shim 只提供「形状正确 + 安全降级」的空实现；
 * 真正的预设读写由宿主的 StPresetStorage / 预设页负责，后续按需接线。
 */

class PresetManager {
    constructor(apiId = '') {
        this.apiId = String(apiId || '');
        // ST 的真实结构：一个对象同时带 presets 与 preset_names
        this.presetList = { presets: [], preset_names: [] };
        this.currentPreset = null;
        // [v213] select 桩：ST 的 this.select 是 jQuery 化的 DOM select（preset-manager.js:113），
        // JSR function/preset.ts:669 会 preset_manager.select.append($("<option>..."))。
        // 用 no-op 链式 jQuery 鸭子对象，避免 TypeError。
        const noopChain = { append: () => noopChain, find: () => noopChain, val: () => "", trigger: () => noopChain, text: () => "", each: () => noopChain };
        this.select = noopChain;
    }

    /** @returns {{presets: any[], preset_names: string[]}} ST 真实形状（含兜底） */
    getPresetList() {
        const l = this.presetList;
        if (!l || typeof l !== 'object' || !Array.isArray(l.presets) || !Array.isArray(l.preset_names)) {
            this.presetList = { presets: [], preset_names: [] };
        }
        return this.presetList;
    }

    getSelectedPreset() {
        return this.currentPreset;
    }

    getSelectedPresetName() {
        try {
            const l = this.getPresetList();
            const i = l.preset_names.indexOf(this.currentPreset);
            return i >= 0 ? this.currentPreset : (l.preset_names[0] ?? '');
        } catch (_e) { return ''; }
    }

    findPreset(name) {
        try {
            const i = this.getPresetList().preset_names.indexOf(String(name));
            return i >= 0 ? this.getPresetList().presets[i] : null;
        } catch (_e) { return null; }
    }

    /**
     * [v227 N1] 统一事件出口（ST 语义 = eventSource.emit）。
     *
     * 事件名逐字对齐 ST 真源 public/scripts/events.js；载荷形状对齐
     * public/scripts/preset-manager.js(1069/1073/1153) 与 public/scripts/openai.js(5034/5078/5079)。
     * payload === undefined 时按 ST 的「无载荷事件」发（如 OAI_PRESET_CHANGED_AFTER）。
     */
    _emit(name, payload) {
        try {
            const ctx = (window.SillyTavern && window.SillyTavern.getContext && window.SillyTavern.getContext()) || null;
            const es = (ctx && ctx.eventSource) || window.eventSource || null;
            if (es && typeof es.emit === "function") {
                if (payload === undefined) es.emit(name); else es.emit(name, payload);
            }
        } catch (_e) { /* noop */ }
    }

    async setPreset(name) { this.currentPreset = name; }
    // [v213] ST preset-manager.js:415-422 selectPreset(value)：选预设 + 触发 OAI_PRESET_CHANGED 事件对。
    // [v227 N1] 修正事件面：v213~v226 发的是 ST 里根本不存在的 oai_settings_preset_changed
    // （ST 真源 events.js 只有 OAI_PRESET_CHANGED_BEFORE / OAI_PRESET_CHANGED_AFTER），
    // 所以任何 eventSource.on(...) 订阅它的扩展都收不到东西 —— 这是「扩展接不上」的典型症状。
    // 现在按 ST 真源补齐三连（顺序也与真源一致）：
    //   ① oai_preset_changed_before { apiId, presetName, preset, savePreset }   (openai.js:5034)
    //   ② oai_preset_changed_after  （无载荷）                                    (openai.js:5078)
    //   ③ preset_changed            { apiId, name }                              (openai.js:5079)
    // ① 的 preset / savePreset / presetName 三个字段都必须给：ST-Prompt-Template 的
    // PromptManager.js:74 迁移钩子 migrate(event.preset, event.savePreset, event.presetName) 直接读它们。
    async selectPreset(value) {
        try {
            const name = typeof value === "string" ? value : String(value);
            const preset = this.findPreset(name);
            this._emit("oai_preset_changed_before", {
                apiId: this.apiId,
                presetName: name,
                preset: preset,
                savePreset: (nextName) => this.savePreset(nextName || name, preset),
            });
            this.currentPreset = name;
            this._emit("oai_preset_changed_after");
            this._emit("preset_changed", { apiId: this.apiId, name: name });
        } catch (_e) { /* noop */ }
    }
    async savePreset(name, preset) {
        try {
            const l = this.getPresetList();
            const i = l.preset_names.indexOf(String(name));
            if (i >= 0) l.presets[i] = preset; else { l.preset_names.push(String(name)); l.presets.push(preset); }
        } catch (_e) { /* noop */ }
        // [v227 N1] ST openai.js:5079 / kai-settings.js:533 / textgen-settings.js:957 同形：
        // PRESET_CHANGED = { apiId, name }。扩展（含 JSR 预设页）靠它刷新缓存。
        this._emit("preset_changed", { apiId: this.apiId, name: String(name) });
        return String(name);
    }
    deletePreset(name) {
        try {
            const l = this.getPresetList();
            const i = l.preset_names.indexOf(String(name));
            if (i >= 0) { l.preset_names.splice(i, 1); l.presets.splice(i, 1); }
        } catch (_e) { /* noop */ }
        // [v227 N1] ST preset-manager.js:1153：PRESET_DELETED = { apiId, name }。
        this._emit("preset_deleted", { apiId: this.apiId, name: String(name) });
    }

    /**
     * [v227 N1] 新增 rename 入口（v226 之前没有，JSR 预设页的「重命名」因此无处可去）。
     *
     * ST preset-manager.js:1060-1073 语义：先发 PRESET_RENAMED_BEFORE、改完再发 PRESET_RENAMED，
     * 载荷均为 { apiId, oldName, newName }（两处 emit 逐字对齐真源）。
     * 返回最终名字，方便调用方链式使用。
     */
    async renamePreset(oldName, newName) {
        const from = String(oldName === undefined || oldName === null ? "" : oldName);
        const to = String(newName === undefined || newName === null ? "" : newName);
        this._emit("preset_renamed_before", { apiId: this.apiId, oldName: from, newName: to });
        try {
            const l = this.getPresetList();
            const i = l.preset_names.indexOf(from);
            if (i >= 0) l.preset_names[i] = to;
            if (this.currentPreset === from) this.currentPreset = to;
        } catch (_e) { /* noop */ }
        this._emit("preset_renamed", { apiId: this.apiId, oldName: from, newName: to });
        return to;
    }
    // [v213] ST preset-manager.js:376-378：getAllPresets() 返回 string[]（option 文本集）。旧实现返回对象 → JSR getPresetNames() 出现 [in_use, {…}] 脏项。
    getAllPresets() { try { return this.getPresetList().preset_names.slice(); } catch (_e) { return []; } }
    isPresetDirty() { return false; }
    getDefaultPreset() { return null; }
    async savePresetDebounced() {}
    readPresetExtensionField() { return undefined; }
    async writePresetExtensionField() {}
}

export { PresetManager };

const cache = new Map();

export function getPresetManager(apiId) {
    const key = String(apiId || 'default');
    if (!cache.has(key)) cache.set(key, new PresetManager(key));
    return cache.get(key);
}

export default getPresetManager;

// ---- [ST 1.19.0 Full Export Parity] ----
export async function initPresetManager(..._args) { return null; }
