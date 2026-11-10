// rikkaST st-compat: scripts/slash-commands/SlashCommandScope.js
export class SlashCommandScopeVariableNotFoundError extends Error {}
export class SlashCommandScopeVariableExistsError extends Error {}

export class SlashCommandScope {
    constructor(parent = null) {
        this.parent = parent;
        this.variables = {};
        this.macros = {};
    }
    letVariable(key, value = '') {
        this.variables[key] = value;
    }
    setVariable(key, value) {
        this.variables[key] = value;
    }
    getVariable(key) {
        if (key in this.variables) return this.variables[key];
        if (this.parent) return this.parent.getVariable(key);
        return '';
    }
    existsVariable(key) {
        return (key in this.variables) || (this.parent ? this.parent.existsVariable(key) : false);
    }
}
export default SlashCommandScope;
