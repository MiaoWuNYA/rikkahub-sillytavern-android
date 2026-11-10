// rikkaST st-compat: scripts/nai-settings.js
export const nai_settings = { model_novel: 'clio-v1', temperature: 1.0 };
export const novelai_settings = [];
export const novelai_setting_names = {};
export function adjustNovelInstructionPrompt(p) { return String(p ?? ''); }
export function convertNovelPreset(p) { return p || {}; }
export async function generateNovelWithStreaming() { return () => ({}); }
export function getKayraMaxContextTokens() { return 8192; }
export function getNovelAnlas() { return 0; }
export function getNovelGenerationData(prompt) { return { input: prompt }; }
export function getNovelMaxResponseTokens() { return 512; }
export function getNovelTier() { return 'Opus'; }
export function getNovelUnlimitedImageGeneration() { return true; }
export async function getStatusNovel() { return false; }
export function initNovelAISettings() {}
export function loadNovelPreset() {}
export function loadNovelSettings() {}
export async function loadNovelSubscriptionData() {}
export function parseNovelAILogprobs() { return null; }
export function setNovelData() {}
