/**
 * MVU Standalone — 深拷贝工具
 * klona 不是 SillyTavern 全局变量，需要自行提供替代
 */

export function deepClone(obj) {
    // 优先使用 structuredClone（现代浏览器原生支持）
    if (typeof structuredClone === 'function') {
        try {
            return structuredClone(obj);
        } catch {
            // structuredClone 不支持某些类型（如 RegExp, Function），降级
        }
    }
    // 降级到 lodash _.cloneDeep（SillyTavern 全局提供）
    if (typeof _ !== 'undefined' && typeof _.cloneDeep === 'function') {
        return _.cloneDeep(obj);
    }
    // 最终降级到 JSON 序列化
    return JSON.parse(JSON.stringify(obj));
}
