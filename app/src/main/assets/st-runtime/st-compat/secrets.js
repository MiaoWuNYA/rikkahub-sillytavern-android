// rikkaST st-compat: scripts/secrets.js
export const SECRET_KEYS = {
    OPENAI: 'api_key_openai',
    CLAUDE: 'api_key_claude',
    MAKERSUITE: 'api_key_makersuite',
    VERTEXAI: 'api_key_vertexai',
    OPENROUTER: 'api_key_openrouter',
    AI21: 'api_key_ai21',
    MISTRALAI: 'api_key_mistralai',
    CUSTOM: 'api_key_custom',
    COHERE: 'api_key_cohere',
    PERPLEXITY: 'api_key_perplexity',
    GROQ: 'api_key_groq',
    DEEPSEEK: 'api_key_deepseek',
    XAI: 'api_key_xai',
    NANOGPT: 'api_key_nanogpt',
    AIMLAPI: 'api_key_aimlapi',
    POLLINATIONS: 'api_key_pollinations',
    MOONSHOT: 'api_key_moonshot',
    FIREWORKS: 'api_key_fireworks',
    COMETAPI: 'api_key_cometapi',
    NOVEL: 'api_key_novel',
};
export const secret_state = {};
export function canViewSecrets() { return false; }
export async function checkOpenRouterAuth() {}
export async function deleteSecret() {}
export async function findSecret() { return ''; }
export function getSecretLabelById(id) { return String(id ?? ''); }
export async function initSecrets() {}
export async function readSecretState() { return secret_state; }
export async function renameSecret() {}
export function resolveSecretKey(key) { return SECRET_KEYS[key] || String(key ?? ''); }
export async function rotateSecret() {}
export function updateSecretDisplay() {}
export async function writeSecret() {}
