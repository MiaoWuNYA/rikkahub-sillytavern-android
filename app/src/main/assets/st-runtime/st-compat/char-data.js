/**
 * rikkaST st-compat: char-data.js
 * ============================================================
 * 模拟 SillyTavern `public/scripts/char-data.js` 的最小导出（P0）。
 * v1CharData 为宽容构造函数（可 new 也可直接调用）。
 */

export function v1CharData(name) {
    if (!(this instanceof v1CharData)) {
        return { name: name || '' };
    }
    this.name = name || '';
}