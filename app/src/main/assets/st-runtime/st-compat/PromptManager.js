/**
 * rikkaST st-compat: PromptManager.js
 * ============================================================
 * 对齐 SillyTavern `public/scripts/PromptManager.js` 的 Prompt / PromptCollection 与激活预设读写。
 */

export const INJECTION_POSITION = {
    RELATIVE: 0,
    ABSOLUTE: 1,
};

export class Prompt {
    constructor(collection, identifier, ...rest) {
        this.collection = collection;
        this.identifier = identifier;
        this.role = 'system';
        this.content = '';
        this.enabled = true;
        this.injection_position = 0;
        this.injection_depth = 4;
        this.injection_order = 100;
        this.extra = rest;
    }

    static from(props) {
        const p = new Prompt(props && props.collection, props && props.identifier, props);
        if (props && typeof props === 'object') Object.assign(p, props);
        return p;
    }
}

export class PromptCollection {
    constructor() {
        this.collection = [];
        this.overriddenPrompts = [];
    }

    add(prompt) {
        try {
            const p = prompt instanceof Prompt ? prompt : Prompt.from(prompt);
            this.collection.push(p);
            return p;
        } catch (_e) {
            return prompt;
        }
    }

    get(id) {
        try {
            return this.collection.find((p) => p && (p.identifier === id || p.id === id)) || null;
        } catch (_e) {
            return null;
        }
    }

    has(id) {
        return !!this.get(id);
    }

    index(id) {
        return this.collection.findIndex((p) => p && (p.identifier === id || p.id === id));
    }

    override(prompt) {
        if (!prompt) return;
        const id = prompt.identifier || prompt.id;
        const existingIdx = this.index(id);
        if (existingIdx >= 0) {
            this.collection[existingIdx] = Object.assign(this.collection[existingIdx], prompt);
        } else {
            this.add(prompt);
        }
        if (id && !this.overriddenPrompts.includes(id)) {
            this.overriddenPrompts.push(id);
        }
    }

    render() {
        try {
            if (window.RikkaBridge && typeof window.RikkaBridge.emitEvent === 'function') {
                window.RikkaBridge.emitEvent('prompt_manager_rendered', JSON.stringify(this.collection));
            }
        } catch (_e) { /* noop */ }
    }
}

export const promptManager = new PromptCollection();

// ---- [ST 1.19.0 Full Export Parity] ----
export class PromptManager {
    constructor(configuration = {}, serviceSettings = {}) {
        this.configuration = configuration;
        this.serviceSettings = serviceSettings;
        this.prompts = Array.isArray(serviceSettings?.prompts) ? serviceSettings.prompts : [];
    }
    getPromptCollection() { return { collection: this.prompts, has: (id) => this.prompts.some(p => p?.identifier === id) }; }
    getPromptById(id) { return this.prompts.find(p => p?.identifier === id) || null; }
    isPromptDisabledForActiveCharacter(id) { void id; return false; }
    render() {}
}
export const chatCompletionDefaultPrompts = {
    prompts: [
        { name: 'Main Prompt', system_prompt: true, role: 'system', content: "Write {{char}}'s next reply in a fictional chat between {{charIfNotGroup}} and {{user}}.", identifier: 'main' },
        { name: 'Auxiliary Prompt', system_prompt: true, role: 'system', content: '', identifier: 'nsfw' },
        { identifier: 'dialogueExamples', name: 'Chat Examples', system_prompt: true, marker: true },
        { name: 'Post-History Instructions', system_prompt: true, role: 'system', content: '', identifier: 'jailbreak' },
        { identifier: 'chatHistory', name: 'Chat History', system_prompt: true, marker: true },
        { identifier: 'worldInfoAfter', name: 'World Info (after)', system_prompt: true, marker: true },
        { identifier: 'worldInfoBefore', name: 'World Info (before)', system_prompt: true, marker: true },
        { identifier: 'enhanceDefinitions', role: 'system', name: 'Enhance Definitions', content: "If you have more knowledge of {{char}}, add to the character's lore and personality to enhance them but keep the Character Sheet's definitions absolute.", system_prompt: true, marker: false },
        { identifier: 'charDescription', name: 'Char Description', system_prompt: true, marker: true },
        { identifier: 'charPersonality', name: 'Char Personality', system_prompt: true, marker: true },
        { identifier: 'scenario', name: 'Scenario', system_prompt: true, marker: true },
        { identifier: 'personaDescription', name: 'Persona Description', system_prompt: true, marker: true },
    ],
};
export const promptManagerDefaultPromptOrders = {
    prompt_order: [
        { identifier: 'main', enabled: true },
        { identifier: 'worldInfoBefore', enabled: true },
        { identifier: 'personaDescription', enabled: true },
        { identifier: 'charDescription', enabled: true },
        { identifier: 'charPersonality', enabled: true },
        { identifier: 'scenario', enabled: true },
        { identifier: 'enhanceDefinitions', enabled: false },
        { identifier: 'nsfw', enabled: true },
        { identifier: 'worldInfoAfter', enabled: true },
        { identifier: 'dialogueExamples', enabled: true },
        { identifier: 'chatHistory', enabled: true },
        { identifier: 'jailbreak', enabled: true },
    ],
};
export const registerPromptManagerMigration = () => {};
