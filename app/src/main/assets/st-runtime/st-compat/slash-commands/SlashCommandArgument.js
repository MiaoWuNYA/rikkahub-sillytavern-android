/**
 * rikkaST st-compat: slash-commands/SlashCommandArgument.js
 * ============================================================
 * 模拟 ST1.18 `SlashCommandArgument` / `SlashCommandNamedArgument` / ARGUMENT_TYPE。
 */

export const ARGUMENT_TYPE = {
    STRING: 'string',
    NUMBER: 'number',
    RANGE: 'range',
    BOOLEAN: 'boolean',
    VARIABLE_NAME: 'variable',
    CLOSURE: 'closure',
    SUBCOMMAND: 'subcommand',
    LIST: 'list',
};

export class SlashCommandArgument {
    constructor(type, description, required, ...rest) {
        this.type = type;
        this.description = description;
        this.required = required;
        this.extra = rest;
    }

    static fromProps(props) {
        const arg = new SlashCommandArgument(props && props.type, props && props.description, props && props.required, props);
        if (props && typeof props === 'object') Object.assign(arg, props);
        return arg;
    }
}

export class SlashCommandNamedArgument {
    constructor(name, type, description, ...rest) {
        this.name = name;
        this.type = type;
        this.description = description;
        this.extra = rest;
    }

    static fromProps(props) {
        const arg = new SlashCommandNamedArgument(props && props.name, props && props.type, props && props.description, props);
        if (props && typeof props === 'object') Object.assign(arg, props);
        return arg;
    }
}