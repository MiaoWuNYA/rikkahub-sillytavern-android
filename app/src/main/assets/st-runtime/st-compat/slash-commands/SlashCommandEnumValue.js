/**
 * rikkaST st-compat: slash-commands/SlashCommandEnumValue.js
 * ============================================================
 * 模拟 ST1.18 `SlashCommandEnumValue` / `enumTypes`。
 */

export const enumTypes = {
    enum: 'enum',
    string: 'string',
    STRING: 'string',
    number: 'number',
    NUMBER: 'number',
    boolean: 'boolean',
    BOOLEAN: 'boolean',
    range: 'range',
    variable: 'variable',
    closure: 'closure',
    subcommand: 'subcommand',
    list: 'list',
};

export class SlashCommandEnumValue {
    constructor(value, description, ...rest) {
        this.value = value;
        this.description = description;
        this.extra = rest;
        this.icon = undefined;
    }

    static fromProps(props) {
        const v = new SlashCommandEnumValue(props && props.value, props && props.description, props);
        if (props && typeof props === 'object') Object.assign(v, props);
        return v;
    }
}