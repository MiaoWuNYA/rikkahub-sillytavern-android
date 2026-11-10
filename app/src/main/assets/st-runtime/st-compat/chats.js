// rikkaST st-compat: scripts/chats.js
export function addDOMPurifyHooks() {}
export async function appendFileContent() { return ''; }
export function decodeStyleTags(text) { return String(text ?? ''); }
export function encodeStyleTags(text) { return String(text ?? ''); }
export async function deleteAttachment() {}
export async function deleteFileFromServer() {}
export async function deleteMediaFromServer() {}
export function formatCreatorNotes(notes) { return String(notes ?? ''); }
export function getCurrentEntityId() {
    const ctx = globalThis.SillyTavern?.getContext?.() || {};
    return ctx.groupId || ctx.characterId || '';
}
export function getDataBankAttachments() { return []; }
export function getDataBankAttachmentsForSource() { return []; }
export async function getFileAttachment() { return ''; }
export function hasPendingFileAttachment() { return false; }
export async function hideChatMessage() {}
export async function hideChatMessageRange() {}
export async function unhideChatMessage() {}
export function initChatUtilities() {}
export function isExternalMediaAllowed() { return true; }
export function populateFileAttachment() {}
export function preserveNeutralChat() {}
export function restoreNeutralChat() {}
export function registerFileConverter() {}
export async function uploadFileAttachment() {}
export async function uploadFileAttachmentToServer() { return ''; }
