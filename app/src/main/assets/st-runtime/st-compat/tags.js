/**
 * rikkaST st-compat: tags.js
 * ============================================================
 * 对齐 SillyTavern `public/scripts/tags.js`，从当前角色卡与上下文构建标签映射。
 */

const st = () => (window.SillyTavern && window.SillyTavern.getContext ? window.SillyTavern.getContext() : {});

export const tag_map = {};
export const tags = [];

export function getTagKeyForEntity(entityId) {
    if (entityId != null && String(entityId) !== '') return String(entityId);
    const ctx = st();
    return String((ctx && (ctx.characterId ?? ctx.name2)) || 'default');
}

export function getTagMap() {
    try {
        const ctx = st();
        if (ctx && Array.isArray(ctx.characters)) {
            ctx.characters.forEach((ch, idx) => {
                if (ch && Array.isArray(ch.tags)) {
                    const key = ch.avatar || ch.name || String(idx);
                    tag_map[key] = ch.tags.slice();
                }
            });
        }
    } catch (_e) { /* noop */ }
    return tag_map;
}

export function getTagsList(entityId) {
    const map = getTagMap();
    const key = getTagKeyForEntity(entityId);
    return Array.isArray(map[key]) ? map[key] : [];
}

// ---- [ST 1.19.0 Full Export Parity] ----
export const TAG_FOLDER_DEFAULT_TYPE = 'NONE';
export const TAG_FOLDER_TYPES = { OPEN: { icon: '✔', class: 'folder_open', fa_icon: 'fa-folder-open', tooltip: 'Open Folder', color: 'green', size: '1' }, CLOSED: { icon: '👁', class: 'folder_closed', fa_icon: 'fa-eye-slash', tooltip: 'Closed Folder', color: 'lightgoldenrodyellow', size: '0.7' }, NONE: { icon: '✕', class: 'no_folder', tooltip: 'No Folder', color: 'red', size: '1' } };
export function addTagsToEntity(..._args) { return null; }
export function appendTagToList(..._args) { return null; }
export function applyCharacterTagsToMessageDivs(..._args) { return null; }
export function applyTagsOnCharacterSelect(..._args) { return null; }
export function applyTagsOnGroupSelect(..._args) { return null; }
export function chooseBogusFolder(..._args) { return null; }
export function compareTagsForSort(..._args) { return null; }
export function createTagInput(..._args) { return null; }
export function createTagMapFromList(..._args) { return null; }
export function filterByTagState(..._args) { return null; }
export function getTagBlock(..._args) { return null; }
export function getTagKeyForEntityElement(..._args) { return null; }
export async function importTags(..._args) { return null; }
export function initTags(..._args) { return null; }
export function isBogusFolder(..._args) { return null; }
export function isBogusFolderOpen(..._args) { return null; }
export function loadTagsSettings(..._args) { return null; }
export function printTagFilters(..._args) { return null; }
export function printTagList(..._args) { return null; }
export function removeTagFromEntity(..._args) { return null; }
export function removeTagFromMap(..._args) { return null; }
export function renameTagKey(..._args) { return null; }
export function searchCharByName(..._args) { return null; }
export function sortTags(..._args) { return null; }
export const tag_filter_type = { character: 0, group_member: 1, group_candidates_list: 1, group_members_list: 2 };
export const tag_import_setting = { ASK: 1, NONE: 2, ALL: 3, ONLY_EXISTING: 4 };
export const tag_sort_mode = { MANUAL: 'manual', ALPHABETICAL: 'alphabetical', BY_ENTRIES: 'by_entries' };
