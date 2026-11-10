// rikkaST st-compat: scripts/tool-calling.js
export class ToolManager {
    static tools = new Map();
    static registerFunctionTool(tool) {
        if (tool && tool.name) ToolManager.tools.set(String(tool.name), tool);
        const st = globalThis.SillyTavern;
        if (st && typeof st.registerFunctionTool === 'function') {
            try { st.registerFunctionTool(tool); } catch (_e) { /* noop */ }
        }
    }
    static unregisterFunctionTool(name) {
        ToolManager.tools.delete(String(name ?? ''));
        const st = globalThis.SillyTavern;
        if (st && typeof st.unregisterFunctionTool === 'function') {
            try { st.unregisterFunctionTool(name); } catch (_e) { /* noop */ }
        }
    }
    static isToolCallingSupported() { return true; }
}
export default ToolManager;
