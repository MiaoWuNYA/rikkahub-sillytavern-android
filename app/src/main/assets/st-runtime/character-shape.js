/**
 * rikkaST · ST 角色对象形态归一化（characters[i] / v1CharData 契约）
 * ============================================================
 * 【为什么有这份文件 · v204 真机事故】
 *   真机日志：
 *     [Prompt Template] Error processing world info:
 *       TypeError: Cannot read properties of undefined (reading 'trim')
 *   看上去像世界书（world info）的问题，其实与世界书无关：
 *   ST-Prompt-Template 的 getCharacterDefine()（src/function/characters.ts:92，dist 里叫 Vh()）
 *   第一句就是 `let example = char.mes_example.trim();`，
 *   而宿主当时交给扩展的角色对象只有 { name, data: { extensions: { world } } }
 *   → mes_example === undefined → .trim() 抛 TypeError。
 *   这条抛错发生在 handlePreloadWorldInfo() 的 try 里，于是日志顶着
 *   「Error processing world info」的壳 —— 又一次「栈顶函数名会骗人」。
 *
 * 【第一原则】对齐 ST 的返回形态，不是「有这个名字就行」。
 *   角色对象必须是完整的 v1CharData：少一个键，扩展就会在某个
 *   .trim() / .replace() / for..of 处炸掉，而且报错点名的是扩展自己的函数。
 *   本文件负责把「桥回来的原始 JSON」补成完整形态（缺失字段给安全默认值）。
 *
 * 【设计约束】
 *   纯函数 · 零 import（不碰 DOM / bridge）→ 既能被 runtime.js 引入，
 *   也能被 logs/jsfixchk/_test_char_define.mjs 直接跑（回归测试）。
 */

const isPlainObject = (v) => !!v && typeof v === 'object' && !Array.isArray(v);
const asString = (v) => (typeof v === 'string' ? v : (v == null ? '' : String(v)));
const asStringArray = (v) => (Array.isArray(v) ? v.filter((x) => typeof x === 'string') : []);

/** 取第一个非空字符串（ST 里顶层与 data 常有同名键，顶层优先、data 兜底）。 */
const pickString = (...cands) => {
    for (const c of cands) {
        const s = asString(c);
        if (s) return s;
    }
    return '';
};

/**
 * ST data.depth_prompt → { prompt, depth, role }
 * （官方默认 depth=4 / role=system；字符串形态与缺字段都归一，避免扩展打印 [object Object] 之外还炸）。
 */
function normalizeDepthPrompt(v) {
    if (typeof v === 'string') return { prompt: v, depth: 4, role: 'system' };
    const o = isPlainObject(v) ? v : {};
    const depth = Number.parseInt(asString(o.depth), 10);
    return {
        prompt: asString(o.prompt),
        depth: Number.isFinite(depth) ? depth : 4,
        role: pickString(o.role) || 'system',
    };
}

/**
 * 把宿主桥回来的原始角色 JSON 补成 ST 真源同构的 v1CharData。
 * @param {object} raw 宿主 getCharacterJson() 的解析结果
 * @returns {object} 完整角色对象（字符串字段必定是 string；数组字段必定是 array）
 */
export function normalizeStCharacter(raw) {
    const r = isPlainObject(raw) ? raw : {};
    const d = isPlainObject(r.data) ? r.data : {};

    const name = pickString(r.name, d.name);
    const description = pickString(r.description, d.description);
    const personality = pickString(r.personality, d.personality);
    const scenario = pickString(r.scenario, d.scenario);
    const firstMes = pickString(r.first_mes, d.first_mes);
    const mesExample = pickString(r.mes_example, d.mes_example);
    const creatorNotes = pickString(d.creator_notes, r.creatorcomment);
    const tags = asStringArray(r.tags).length ? asStringArray(r.tags) : asStringArray(d.tags);

    // data.extensions 是 ST 真源位置（world / tavern_helper 都在这里）；顶层 extensions 只作兜底合并。
    const extensions = Object.assign(
        {},
        isPlainObject(r.extensions) ? r.extensions : {},
        isPlainObject(d.extensions) ? d.extensions : {},
    );

    return {
        name,
        // avatar 在 ST 里是角色文件名：JSR 拿它当 per-character store 的键、还会 .replace() 它，
        // 绝不能是 undefined；宿主没给就用空串。
        avatar: asString(r.avatar),
        description,
        personality,
        scenario,
        first_mes: firstMes,
        mes_example: mesExample,
        creatorcomment: pickString(r.creatorcomment, creatorNotes),
        chat: asString(r.chat),
        talkativeness: pickString(r.talkativeness) || '0.5',
        fav: r.fav === true,
        tags,
        spec: asString(r.spec),
        spec_version: pickString(r.spec_version, r.specVersion),
        data: {
            name: pickString(d.name, name),
            description: pickString(d.description, description),
            personality: pickString(d.personality, personality),
            scenario: pickString(d.scenario, scenario),
            first_mes: pickString(d.first_mes, firstMes),
            mes_example: pickString(d.mes_example, mesExample),
            creator_notes: creatorNotes,
            system_prompt: asString(d.system_prompt),
            post_history_instructions: asString(d.post_history_instructions),
            alternate_greetings: asStringArray(d.alternate_greetings),
            character_book: isPlainObject(d.character_book) ? d.character_book : null,
            tags,
            creator: asString(d.creator),
            character_version: asString(d.character_version),
            extensions,
            group_only_greetings: asStringArray(d.group_only_greetings),
            depth_prompt: normalizeDepthPrompt(d.depth_prompt),
        },
    };
}