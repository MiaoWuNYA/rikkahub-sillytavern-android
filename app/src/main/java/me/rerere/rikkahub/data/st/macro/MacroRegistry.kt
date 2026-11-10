package me.rerere.rikkahub.data.st.macro

/**
 * 宏注册表。对应 SillyTavern 1.18.0 `MacroRegistry`（单例）。
 *
 * 简化说明：ST 为别名创建独立条目（保留别名展示名与 aliasOf 关系），
 * 本实现中别名直接映射到主定义，查询语义一致。
 */
object MacroRegistry {
    private val macros = mutableMapOf<String, MacroDefinition>()
    private val aliasMap = mutableMapOf<String, MacroDefinition>()

    /** 注册宏（含别名）。重复注册覆盖旧定义（与 ST 行为一致）。 */
    fun register(definition: MacroDefinition) {
        macros[definition.name.lowercase()] = definition
        for (alias in definition.aliases) {
            aliasMap[alias.lowercase()] = definition
        }
    }

    fun unregister(name: String) {
        val key = name.lowercase()
        val removed = macros.remove(key)
        if (removed != null) {
            aliasMap.entries.removeIf { it.value === removed }
        }
        aliasMap.remove(key)
    }

    fun hasMacro(name: String): Boolean {
        val key = name.lowercase()
        return macros.containsKey(key) || aliasMap.containsKey(key)
    }

    /** 名称或别名 → 定义 */
    fun getMacro(name: String): MacroDefinition? {
        val key = name.lowercase()
        return macros[key] ?: aliasMap[key]
    }

    /**
     * 主定义。对应 ST getPrimaryMacro（别名解析到主定义）。
     * 本实现中别名即指向主定义对象，直接返回。
     */
    fun getPrimaryMacro(name: String): MacroDefinition? = getMacro(name)

    /** 全部主定义（不含独立别名条目；用于文档与自动补全） */
    fun getAllMacros(): List<MacroDefinition> = macros.values.toList()

    /** 测试辅助：清空注册表 */
    fun clear() {
        macros.clear()
        aliasMap.clear()
    }
}