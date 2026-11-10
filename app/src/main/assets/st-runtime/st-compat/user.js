/**
 * rikkaST st-compat: user.js
 * ============================================================
 * 模拟 SillyTavern `public/scripts/user.js` 的最小导出面（单用户语义）。
 */

export let currentUser = { handle: 'user', name: 'User', admin: true };

export let accountsEnabled = false;

export async function setUserControls() {}

export function isAdmin() {
    return true;
}

export function getCurrentUserHandle() {
    return (currentUser && currentUser.handle) || 'user';
}