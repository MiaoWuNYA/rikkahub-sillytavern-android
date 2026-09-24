// AI 模型世界插件 —— 数据来自 liyupi/ai-model-world（Epoch AI / models.dev /
// LiveBench / Hugging Face，每小时自动同步），拉回来裁剪后存在插件自己的
// dataStore 里，之后的查询全部走本地，不依赖网络。
//
// 沙箱注意：QuickJS 是 ES5 语境，没有 require / DOM；fetch 是宿主注入的同步版。
// dataStore 的值是字符串，大 JSON 只能整体存取，所以数据分两块存：
//   meta  —— 数据日期、模型数、厂商表（每次调用都要读，保持小）
//   db    —— 全量模型档案（约 600KB，只在需要明细时解析一次并缓存到内存）

var DATA_URL = 'https://raw.githubusercontent.com/liyupi/ai-model-world/main/data/models.json';

var META_KEY = 'meta';
var DB_KEY = 'db';

// 常用基准的中文说明，search_models 报错时一并返回给模型参考
var BENCH_HINTS = {
  eci: 'Epoch 综合智力指数',
  swe_bench_verified: 'SWE-bench Verified 真实编程修复',
  swe_bench_pro: 'SWE-bench Pro 高难度编程',
  gpqa_diamond: 'GPQA Diamond 科学问答',
  aime: 'AIME 数学竞赛',
  arc_agi_2: 'ARC-AGI-2 视觉推理',
  arc_agi: 'ARC-AGI 抽象推理',
  aider_polyglot: 'Aider Polyglot 多语言编程',
  hle: 'Humanity\'s Last Exam 人类终极考试',
  terminal_bench_2_0: 'Terminal-Bench 2.0 终端操作',
  terminal_bench: 'Terminal-Bench 终端操作',
  webdev_arena_elo: 'WebDev Arena 网页开发竞技场 Elo',
  livebench_coding: 'LiveBench 编程',
  livebench_agentic_coding: 'LiveBench 智能体编程',
  dtbench: 'DTBench',
  frontiermath: 'FrontierMath 前沿数学',
  math_level_5: 'MATH Level-5 数学题',
  mmlu: 'MMLU 综合知识',
  bbh: 'BIG-Bench Hard',
  simpleqa_verified: 'SimpleQA 事实问答',
  simplebench: 'SimpleBench',
  metr_time_horizons: 'METR 任务时长（智能体自主能力）',
  chess_puzzles: '国际象棋谜题',
  weirdml: 'WeirdML 机器学习',
  fiction_live: 'Fiction.liveBench 创意写作',
  lech_mazur_writing: 'Lech Mazur 创意写作',
  geobench: 'GeoBench 地理',
  video_mme: 'Video-MME 视频理解',
  os_world: 'OSWorld 桌面操作',
  the_agent_company: 'TheAgentCompany 智能体综合',
  balrog: 'BALROG 游戏智能体',
  gsm8k: 'GSM8K 小学数学',
  trivia_qa: 'TriviaQA 知识问答'
};

// ---------------------------------------------------------------------------
// dataStore：值必须是字符串。db 是一整坨 JSON（约 600KB），解析一次后挂在
// 内存缓存里——沙箱是常驻的，同一次会话里反复查询不用重复 JSON.parse。
// ---------------------------------------------------------------------------

var _dbCache = null; // 解析后的 models 数组
var _metaCache = null; // 解析后的 meta（厂商名等）
var _vendorHay = null; // vendorId -> 厂商名的可搜索串（小写）

function loadMeta() {
  if (_metaCache) return _metaCache;
  var raw = dataStore.get(META_KEY);
  if (!raw) return null;
  try {
    _metaCache = JSON.parse(raw);
    return _metaCache;
  } catch (e) {
    return null;
  }
}

function saveMeta(meta) {
  _metaCache = meta;
  _vendorHay = null;
  dataStore.set(META_KEY, JSON.stringify(meta));
}

/** 厂商 id -> 「英文名 中文名」小写串。搜索结果里带的中文厂商名也要能搜到，
 *  否则「豆包」「通义」这类中文名搜不出来（模型记录里只有 id 和英文名）。 */
function vendorHaystack(vendorId) {
  if (!vendorId) return '';
  if (!_vendorHay) {
    _vendorHay = {};
    var meta = loadMeta();
    var vendors = (meta && meta.vendors) || {};
    for (var k in vendors) {
      if (!Object.prototype.hasOwnProperty.call(vendors, k)) continue;
      var v = vendors[k] || {};
      _vendorHay[k] = ((v.name || '') + ' ' + (v.nameZh || '')).toLowerCase();
    }
  }
  return _vendorHay[vendorId] || '';
}

function loadModels() {
  if (_dbCache) return _dbCache;
  var raw = dataStore.get(DB_KEY);
  if (!raw) return null;
  try {
    _dbCache = JSON.parse(raw);
    return _dbCache;
  } catch (e) {
    // 数据坏了就当没有，下次 update_data 会重写
    dataStore.del(DB_KEY);
    return null;
  }
}

function clearCache() {
  _dbCache = null;
}

// ---------------------------------------------------------------------------
// 拉取 + 裁剪。源文件 2.6MB，直接 JSON.parse 对嵌入式 JS 引擎太重，好在
// fetch 返回的是字符串，先在这里把每个模型缩到查询需要的字段，再入库。
// 字段名压缩后全量约 600KB，dataStore（SharedPreferences）存得下。
// ---------------------------------------------------------------------------

function fetchAndStore() {
  // 沙箱的 fetch 在请求失败时是抛异常而不是返回 ok:false（连不上、DNS 挂了、
  // 代理没开都走这里），所以必须自己接住，否则 AI 看到的是一句裸异常。
  var resp;
  try {
    resp = fetch(DATA_URL, {
      headers: { 'User-Agent': 'RikkaHub-aimodels-plugin/1.0' }
    });
  } catch (e) {
    return {
      success: false,
      error: '下载失败：' + (e && e.message ? e.message : e) +
        '。请检查网络；源站在 GitHub，国内通常需要在「设置 → 网络」里配置代理。'
    };
  }
  if (!resp.ok) {
    return { success: false, error: '下载失败，HTTP ' + resp.status };
  }

  var source;
  try {
    source = JSON.parse(resp.body);
  } catch (e) {
    return { success: false, error: '返回内容不是合法 JSON: ' + e.message };
  }

  if (!source || Object.prototype.toString.call(source.models) !== '[object Array]') {
    return { success: false, error: '数据格式不符（缺少 models 数组）' };
  }
  // 空数据当作失败：宁可留着旧数据，也不要拿一份空的覆盖掉本地可用的库
  if (source.models.length === 0) {
    return { success: false, error: '源数据里一个模型都没有，疑似源站异常，已保留本地旧数据' };
  }

  var models = [];
  for (var i = 0; i < source.models.length; i++) {
    var raw0 = source.models[i];
    if (!raw0 || !raw0.id) continue; // 没有 id 的记录没法被查到，直接丢掉
    models.push(compactModel(raw0));
  }

  var vendors = {};
  if (Object.prototype.toString.call(source.vendors) === '[object Array]') {
    for (var j = 0; j < source.vendors.length; j++) {
      var v = source.vendors[j];
      vendors[v.id] = {
        name: v.name || v.id,
        nameZh: v.nameZh || null,
        country: v.country || null,
        homepage: v.homepage || null,
        continent: v.continent || null
      };
    }
  }

  var meta = {
    generatedAt: source.generatedAt || null,
    fetchedAt: new Date().toISOString(),
    modelCount: models.length,
    vendorCount: countKeys(vendors),
    vendors: vendors,
    benchCount: countKeys(BENCH_HINTS)
  };

  // 先写 db 再写 meta：meta 在就代表 db 一定写完了
  dataStore.set(DB_KEY, JSON.stringify(models));
  saveMeta(meta);
  clearCache();

  return { success: true, meta: meta };
}

function compactModel(m) {
  // 评分存成紧凑数组 [league, score, source, sourceUrl, unit]，
  // 字段名用 sc（scores）和 b（benchmarks 快照），避免和 s（slug）混
  var sc = [];
  var src = m.scores;
  if (Object.prototype.toString.call(src) === '[object Array]') {
    for (var i = 0; i < src.length; i++) {
      var s0 = src[i];
      sc.push([s0.league || null, s0.score, s0.source || null, s0.sourceUrl || null, s0.unit || 'pct']);
    }
  }
  var bench = {};
  var b0 = m.benchmarks;
  if (b0 && typeof b0 === 'object') {
    for (var k in b0) {
      if (Object.prototype.hasOwnProperty.call(b0, k) && b0[k] !== null && b0[k] !== undefined) {
        bench[k] = b0[k];
      }
    }
  }
  var p = m.pricing || {};
  var pa = m.params || {};
  var cap = m.capabilities || {};
  var md = m.modalities || {};
  return {
    i: m.id,
    n: m.name || m.id,
    v: m.vendorId || null,
    s: m.slug || null,
    c: m.contextWindow,
    o: m.maxOutput,
    p: {
      i: p.inputPerMTok,
      o: p.outputPerMTok,
      c: p.cachedInputPerMTok
    },
    pa: { t: pa.totalB, a: pa.activeB, conf: pa.confidence || null },
    cap: {
      r: !!cap.reasoning,
      t: !!cap.toolCall,
      so: !!cap.structuredOutput,
      pc: !!cap.promptCaching
    },
    mi: md.input || null,
    mo: md.output || null,
    r: m.releaseDate || null,
    k: m.knowledgeCutoff || null,
    lic: m.license || null,
    ow: m.openWeights === true,
    x: m.retiredAt || null,
    sc: sc,
    b: bench
  };
}

function countKeys(obj) {
  var n = 0;
  for (var k in obj) if (Object.prototype.hasOwnProperty.call(obj, k)) n++;
  return n;
}

// ---------------------------------------------------------------------------
// 查询辅助
// ---------------------------------------------------------------------------

function modelPriceIn(m) {
  return m.p && m.p.i != null ? m.p.i : null;
}

function modelPriceOut(m) {
  return m.p && m.p.o != null ? m.p.o : null;
}

function isRetired(m) {
  return !!m.x;
}

/** 把用户给的厂商串（id / 英文名 / 中文名）解析成厂商 id。
 *  唯一命中返回 {id}，多义返回 {id:null, candidates}，没找到返回 {id:null}。 */
function resolveVendorId(rawWant) {
  var raw = String(rawWant == null ? '' : rawWant).replace(/^\s+|\s+$/g, '');
  if (!raw) return { id: null };
  var meta = loadMeta();
  var vendors = (meta && meta.vendors) || {};
  if (vendors[raw]) return { id: raw };

  var want = norm(raw);
  var hits = [];
  for (var vid in vendors) {
    if (!Object.prototype.hasOwnProperty.call(vendors, vid)) continue;
    if (norm(vid) === want) return { id: vid };
    var vd = vendors[vid] || {};
    var hay = norm(vid + ' ' + (vd.name || '') + ' ' + (vd.nameZh || ''));
    if (hay.indexOf(want) !== -1) hits.push(vid);
  }
  if (hits.length === 1) return { id: hits[0] };
  if (hits.length > 1) return { id: null, candidates: hits.slice(0, 10) };
  return { id: null };
}

/** 空格/下划线/连字符一律视作同一个分隔符：「deepseek v3.2」「deepseek_v3.2」
 *  和「deepseek-v3.2」都归一成同一个串，用户怎么打都能命中。 */
function norm(s) {
  return String(s == null ? '' : s).toLowerCase()
    .replace(/[\s_\-]+/g, '-')
    .replace(/^-+|-+$/g, '');
}

function matchQuery(m, q) {
  var hay = norm((m.n || '') + ' ' + (m.i || '') + ' ' + (m.s || '') + ' ' +
    (m.v || '') + ' ' + vendorHaystack(m.v));
  return hay.indexOf(q) !== -1;
}

/** 模糊命中里挑唯一的一个：命中数 1 直接给；否则只有「最后一个路径段完全等于
 *  查询串」的才算无歧义（「deepseek v3.2」能选中 deepseek-v3.2，但不会瞎猜
 *  「claude opus 4」到底是 4、4-1 还是 4-8）。选不出来就交给调用方列候选。 */
function pickUniqueFuzzy(fuzzy, want) {
  if (!fuzzy || fuzzy.length === 0) return null;
  if (fuzzy.length === 1) return fuzzy[0];
  var tailHits = [];
  for (var i = 0; i < fuzzy.length; i++) {
    var iid = String(fuzzy[i].i || '').toLowerCase();
    var tail = iid.split('/').pop();
    if (norm(tail) === want) tailHits.push(fuzzy[i]);
  }
  return tailHits.length === 1 ? tailHits[0] : null;
}

/** 按 id / slug / 名字找模型；模糊时收集所有命中 */
function findModels(idOrName, vendor) {
  var models = loadModels();
  if (!models) return { models: null };

  var raw = String(idOrName == null ? '' : idOrName).replace(/^\s+|\s+$/g, '');
  var want = norm(raw); // 归一后的查询串（分隔符统一成 '-'）
  var wantVendor = null;
  if (vendor) {
    var rv = resolveVendorId(vendor);
    wantVendor = norm(rv.id || vendor);
  }
  var exact = null;
  var exactSlug = null;
  var fuzzy = [];

  for (var i = 0; i < models.length; i++) {
    var m = models[i];
    if (wantVendor && norm(m.v) !== wantVendor) continue;
    if (!want) {
      fuzzy.push(m);
      continue;
    }
    // 精确命中：原样 id、归一后 id、或 slug
    if (m.i === raw || (m.s && m.s === raw)) {
      exact = m;
      break;
    }
    if (norm(m.i) === want) {
      exact = m;
      break;
    }
    if (m.s && norm(m.s) === want && !exactSlug) {
      exactSlug = m; // 别急着 break，后面可能有同名的原样 id 命中
      continue;
    }
    if (matchQuery(m, want)) fuzzy.push(m);
  }

  if (exact) return { models: [exact], exact: true };
  if (exactSlug) return { models: [exactSlug], exact: true };
  var picked = pickUniqueFuzzy(fuzzy, want);
  if (picked) return { models: [picked], exact: false };
  return { models: fuzzy, exact: false };
}

/** 分数表：league -> {score, source, sourceUrl, unit}；
 *  sc 是评分列表（第三方，带出处），b 是官方 bench 快照（无出处时并入）。
 *  b 里的值优先级低于 sc，同名基准以 sc 为准。 */
function scoreMapOf(m) {
  var map = {};
  var arr = m.sc;
  if (arr && arr.length) {
    for (var i = 0; i < arr.length; i++) {
      var s = arr[i];
      if (!s || !s[0]) continue;
      map[s[0]] = { score: s[1], source: s[2], sourceUrl: s[3], unit: s[4] || 'pct' };
    }
  }
  if (m.b) {
    for (var k in m.b) {
      if (Object.prototype.hasOwnProperty.call(m.b, k) && !map[k]) {
        map[k] = { score: m.b[k], source: 'epoch.ai', sourceUrl: null, unit: 'pct' };
      }
    }
  }
  return map;
}

// ---------------------------------------------------------------------------
// 工具实现
// ---------------------------------------------------------------------------

function update_data(params) {
  var r = fetchAndStore();
  if (!r.success) return r;
  return {
    success: true,
    data_date: r.meta.generatedAt,
    fetched_at: r.meta.fetchedAt,
    model_count: r.meta.modelCount,
    vendor_count: r.meta.vendorCount,
    message: '已保存到本地。后续查询不再需要网络。'
  };
}

function get_data_info(params) {
  // meta 和 db 都要能读出来才算「有数据」。db 坏了时 loadModels 会把它删掉，
  // 这里就自然退化成「没数据」，而不是让后面每个工具都去 length 一个 null。
  var meta = loadMeta();
  var models = loadModels();
  if (!meta || !models) {
    return {
      success: true,
      has_data: false,
      message: '本地还没有数据。请直接调用 update_data 拉取（需要联网）。'
    };
  }
  return {
    success: true,
    has_data: true,
    data_date: meta.generatedAt,
    fetched_at: meta.fetchedAt,
    model_count: meta.modelCount,
    vendor_count: meta.vendorCount,
    common_benchmarks: Object.keys(BENCH_HINTS)
  };
}

function search_models(params) {
  params = params || {};
  var info = get_data_info({});
  if (!info.has_data) return info;

  var models = loadModels();
  var meta = loadMeta();

  var bench = params.benchmark ? String(params.benchmark) : null;
  if (bench && !BENCH_HINTS[bench] && !isKnownBench(models, bench)) {
    return {
      success: false,
      error: '未知基准: ' + bench,
      known_benchmarks: BENCH_HINTS
    };
  }

  var q = params.query ? norm(params.query) : null;
  var vendor = null;
  if (params.vendor) {
    var rv = resolveVendorId(params.vendor);
    if (rv.candidates) {
      return {
        success: false,
        error: '「' + params.vendor + '」匹配到多个厂商，请用完整 id 再查:',
        candidates: rv.candidates
      };
    }
    // 认不出来就按原样当 id 过滤，结果为空时用户能立刻看出来
    vendor = norm(rv.id || params.vendor);
  }
  var activeOnly = params.active_only !== false;

  var out = [];
  for (var i = 0; i < models.length; i++) {
    var m = models[i];
    if (activeOnly && isRetired(m)) continue;
    if (vendor && norm(m.v) !== vendor) continue;
    if (q && !matchQuery(m, q)) continue;

    if (params.reasoning === true && !m.cap.r) continue;
    if (params.tool_call === true && !m.cap.t) continue;
    if (params.vision === true && !hasVision(m)) continue;
    if (params.open_weights === true && !m.ow) continue;

    if (params.min_context != null && !(m.c >= params.min_context)) continue;
    if (params.released_after != null && (!m.r || m.r < String(params.released_after))) continue;

    var pin = modelPriceIn(m);
    var pout = modelPriceOut(m);
    if (params.max_input_price != null) {
      if (pin == null || pin > params.max_input_price) continue;
    }
    if (params.max_output_price != null) {
      if (pout == null || pout > params.max_output_price) continue;
    }

    var bScore = null;
    if (bench) {
      var sm = scoreMapOf(m);
      if (!sm[bench]) continue;
      bScore = sm[bench].score;
    }

    out.push({ m: m, benchScore: bScore });
  }

  sortResults(out, params.sort, bench);

  var limit = parseInt(params.limit, 10);
  if (isNaN(limit) || limit <= 0) limit = 20;
  if (limit > 60) limit = 60;
  var total = out.length;

  var items = [];
  for (var j = 0; j < out.length && j < limit; j++) {
    items.push(summaryOf(out[j].m, out[j].benchScore));
  }

  var result = {
    success: true,
    total_matched: total,
    returned: items.length,
    truncated: total > items.length,
    models: items
  };
  if (bench) {
    result.benchmark = bench;
    result.benchmark_name = BENCH_HINTS[bench] || bench;
  }
  return result;
}

function isKnownBench(models, bench) {
  // 不在白名单里但确实有模型得过分，也接受
  for (var i = 0; i < models.length; i++) {
    if (scoreMapOf(models[i])[bench]) return true;
  }
  return false;
}

function hasVision(m) {
  if (!m.mi) return false;
  for (var i = 0; i < m.mi.length; i++) {
    if (m.mi[i] === 'image') return true;
  }
  return false;
}

function sortResults(arr, sort, bench) {
  var key = sort ? String(sort) : (bench ? 'benchmark' : 'release');
  var by = function (a, b, fn) {
    var va = fn(a), vb = fn(b);
    if (va === null && vb === null) return 0;
    if (va === null) return 1;  // 无值的排后面
    if (vb === null) return -1;
    return va === vb ? 0 : (va < vb ? -1 : 1);
  };
  if (key === 'benchmark' && bench) {
    arr.sort(function (a, b) { return by(a, b, function (x) { return x.benchScore; }) * -1; });
  } else if (key === 'price') {
    arr.sort(function (a, b) { return by(a, b, function (x) { return modelPriceIn(x.m); }); });
  } else if (key === 'output_price') {
    arr.sort(function (a, b) { return by(a, b, function (x) { return modelPriceOut(x.m); }); });
  } else if (key === 'price_desc') {
    arr.sort(function (a, b) { return by(a, b, function (x) { return modelPriceIn(x.m); }) * -1; });
  } else if (key === 'context') {
    arr.sort(function (a, b) { return by(a, b, function (x) { return x.m.c; }) * -1; });
  } else if (key === 'release') {
    arr.sort(function (a, b) { return by(a, b, function (x) { return x.m.r; }) * -1; });
  } else if (bench) {
    // 传了 benchmark 但 sort 不认识，仍然按分数排
    arr.sort(function (a, b) { return by(a, b, function (x) { return x.benchScore; }) * -1; });
  } else {
    arr.sort(function (a, b) { return by(a, b, function (x) { return x.m.r; }) * -1; });
  }
}

function summaryOf(m, benchScore) {
  var vendorName = vendorDisplayName(m.v);
  var item = {
    id: m.i,
    name: m.n,
    vendor: m.v,
    vendor_name: vendorName,
    context_window: m.c,
    input_price: modelPriceIn(m),
    output_price: modelPriceOut(m),
    release_date: m.r,
    reasoning: m.cap.r,
    tool_call: m.cap.t
  };
  if (m.pa && m.pa.t != null) item.params_b = m.pa.t;
  if (m.ow) item.open_weights = true;
  if (isRetired(m)) item.retired = true;
  if (benchScore !== null && benchScore !== undefined) {
    item.bench_score = benchScore;
  }
  return item;
}

function vendorDisplayName(vendorId) {
  if (!vendorId) return null;
  var meta = loadMeta();
  if (meta && meta.vendors && meta.vendors[vendorId]) {
    var v = meta.vendors[vendorId];
    return v.nameZh || v.name || vendorId;
  }
  return vendorId;
}

function get_model(params) {
  params = params || {};
  var info = get_data_info({});
  if (!info.has_data) return info;

  var id = params.id ? String(params.id) : '';
  if (!id) return { success: false, error: 'id 不能为空' };

  var found = findModels(id, params.vendor);
  if (!found.models || found.models.length === 0) {
    return { success: false, error: '没找到模型: ' + id + '，先用 search_models 确认名称' };
  }
  if (found.models.length > 1) {
    var cands = [];
    for (var i = 0; i < found.models.length && i < 10; i++) {
      cands.push(found.models[i].i);
    }
    return {
      success: false,
      error: '匹配到 ' + found.models.length + ' 个模型，请用完整 id 再取:',
      candidates: cands
    };
  }

  return { success: true, model: fullProfile(found.models[0]) };
}

function fullProfile(m) {
  var scores = scoreMapOf(m);
  var scoreList = [];
  for (var k in scores) {
    if (Object.prototype.hasOwnProperty.call(scores, k)) {
      scoreList.push({
        benchmark: k,
        name: BENCH_HINTS[k] || k,
        score: scores[k].score,
        unit: scores[k].unit,
        source: scores[k].source,
        source_url: scores[k].sourceUrl
      });
    }
  }
  // 分数从高到低，模型方看着方便
  scoreList.sort(function (a, b) {
    return (b.score === null || b.score === undefined ? -1 : b.score) -
           (a.score === null || a.score === undefined ? -1 : a.score);
  });

  var p = m.p || {};
  var prof = {
    id: m.i,
    slug: m.s,
    name: m.n,
    vendor: m.v,
    vendor_name: vendorDisplayName(m.v),
    context_window: m.c,
    max_output: m.o,
    pricing: {
      input_per_mtok: p.i,
      output_per_mtok: p.o,
      cached_input_per_mtok: p.c,
      unit: 'USD / 1M tokens'
    },
    params: {
      total_b: m.pa ? m.pa.t : null,
      active_b: m.pa ? m.pa.a : null,
      confidence: m.pa ? m.pa.conf : null
    },
    capabilities: {
      reasoning: m.cap.r,
      tool_call: m.cap.t,
      structured_output: m.cap.so,
      prompt_caching: m.cap.pc
    },
    modalities: { input: m.mi, output: m.mo },
    release_date: m.r,
    knowledge_cutoff: m.k,
    license: m.lic,
    open_weights: m.ow,
    retired_at: m.x || null,
    scores: scoreList
  };
  return prof;
}

function compare_models(params) {
  params = params || {};
  var info = get_data_info({});
  if (!info.has_data) return info;

  var ids = params.ids;
  if (Object.prototype.toString.call(ids) !== '[object Array]' || ids.length < 2) {
    return { success: false, error: 'ids 必须是至少 2 个模型 id 的数组' };
  }
  if (ids.length > 5) {
    return { success: false, error: '一次最多对比 5 个模型' };
  }

  var resolved = [];
  for (var i = 0; i < ids.length; i++) {
    var found = findModels(ids[i], null);
    if (!found.models || found.models.length === 0) {
      return { success: false, error: '没找到模型: ' + ids[i] + '，先用 search_models 确认' };
    }
    if (found.models.length > 1) {
      var cands = [];
      for (var j = 0; j < found.models.length && j < 8; j++) cands.push(found.models[j].i);
      return {
        success: false,
        error: '「' + ids[i] + '」匹配到 ' + found.models.length + ' 个模型，请用完整 id:',
        candidates: cands
      };
    }
    resolved.push(found.models[0]);
  }

  // 表头：行 = 指标，列 = 模型
  var columns = [];
  for (var c = 0; c < resolved.length; c++) {
    columns.push({ id: resolved[c].i, name: resolved[c].n });
  }

  var rows = [
    { metric: '厂商' },
    { metric: '上下文窗口 (token)' },
    { metric: '输入价 (USD/Mtok)' },
    { metric: '输出价 (USD/Mtok)' },
    { metric: '总参数量 (B)' },
    { metric: '推理/思考' },
    { metric: '工具调用' },
    { metric: '图片输入' },
    { metric: '开源权重' },
    { metric: '发布日期' }
  ];
  for (var r = 0; r < resolved.length; r++) {
    var m = resolved[r];
    rows[0][r] = vendorDisplayName(m.v);
    rows[1][r] = m.c;
    rows[2][r] = modelPriceIn(m);
    rows[3][r] = modelPriceOut(m);
    rows[4][r] = m.pa && m.pa.t != null ? m.pa.t : null;
    rows[5][r] = m.cap.r ? '是' : '否';
    rows[6][r] = m.cap.t ? '是' : '否';
    rows[7][r] = hasVision(m) ? '是' : '否';
    rows[8][r] = m.ow ? '是' : '否';
    rows[9][r] = m.r;
  }

  // 共有基准：所有模型都有分的才进对比表
  var common = null;
  for (var k = 0; k < resolved.length; k++) {
    var sm = scoreMapOf(resolved[k]);
    var keys = Object.keys(sm);
    if (common === null) {
      common = {};
      for (var x = 0; x < keys.length; x++) common[keys[x]] = true;
    } else {
      var next = {};
      for (var y = 0; y < keys.length; y++) if (common[keys[y]]) next[keys[y]] = true;
      common = next;
    }
  }

  var benchRows = [];
  if (common) {
    var benchKeys = Object.keys(common);
    // 多的在前：有分的才比，全 null 的没意义
    var weighted = [];
    for (var w = 0; w < benchKeys.length; w++) {
      var bk = benchKeys[w];
      var sum = 0, has = false;
      for (var z = 0; z < resolved.length; z++) {
        var v = scoreMapOf(resolved[z])[bk].score;
        if (v !== null && v !== undefined) { sum += v; has = true; }
      }
      if (has) weighted.push({ k: bk, avg: sum / resolved.length });
    }
    weighted.sort(function (a, b) { return b.avg - a.avg; });
    for (var q = 0; q < weighted.length && q < 15; q++) {
      var bk2 = weighted[q].k;
      var row = { metric: bk2 + (BENCH_HINTS[bk2] ? ' (' + BENCH_HINTS[bk2] + ')' : '') };
      for (var z2 = 0; z2 < resolved.length; z2++) {
        var sv = scoreMapOf(resolved[z2])[bk2].score;
        row[z2] = sv === null || sv === undefined ? null : sv;
      }
      benchRows.push(row);
    }
  }

  return {
    success: true,
    columns: columns,
    rows: rows.concat(benchRows),
    note: '评分为第三方基准（Epoch AI / LiveBench 等），null 表示该模型没有这项数据。'
  };
}

function list_vendors(params) {
  params = params || {};
  var info = get_data_info({});
  if (!info.has_data) return info;

  var meta = loadMeta();
  if (params.vendor) {
    // 精确 id 优先；否则用 id / 英文名 / 中文名做模糊匹配（「豆包」也能定位到厂商）
    var resolved = resolveVendorId(params.vendor);
    if (resolved.candidates) {
      return {
        success: false,
        error: '「' + params.vendor + '」匹配到多个厂商，请用完整 id 再查:',
        candidates: resolved.candidates
      };
    }
    var vendorId = resolved.id;
    if (!vendorId) {
      return { success: false, error: '没找到厂商: ' + params.vendor + '，先不带参数调用看有哪些厂商' };
    }

    var models = loadModels();
    var items = [];
    for (var i = 0; i < models.length; i++) {
      if (norm(models[i].v) === norm(vendorId)) items.push(summaryOf(models[i], null));
    }
    var vendorFound = vendorId;
    return {
      success: true,
      vendor: vendorFound,
      vendor_name: vendorDisplayName(vendorFound),
      model_count: items.length,
      models: items
    };
  }

  // 全厂商：顺手统计旗下模型数
  var counts = {};
  var models2 = loadModels();
  for (var j = 0; j < models2.length; j++) {
    var v2 = models2[j].v || 'unknown';
    counts[v2] = (counts[v2] || 0) + 1;
  }
  var list = [];
  var allVendors = meta.vendors || {};
  for (var vid in allVendors) {
    if (Object.prototype.hasOwnProperty.call(allVendors, vid)) {
      var vd = allVendors[vid];
      list.push({
        id: vid,
        name: vd.nameZh || vd.name || vid,
        name_en: vd.name,
        country: vd.country,
        homepage: vd.homepage,
        model_count: counts[vid] || 0
      });
    }
  }
  list.sort(function (a, b) { return b.model_count - a.model_count; });
  return { success: true, vendor_count: list.length, vendors: list };
}

// ---------------------------------------------------------------------------
// 详情页数据卡片
// ---------------------------------------------------------------------------

function detail_card(params) {
  var meta = loadMeta();
  if (!meta) {
    return {
      title: 'AI 模型世界',
      note: '本地还没有数据。跟 AI 说「更新模型数据」，或者直接调用 update_data 工具拉取。'
    };
  }
  return {
    title: 'AI 模型世界',
    items: [
      { label: '模型数', value: String(meta.modelCount) },
      { label: '厂商数', value: String(meta.vendorCount) },
      { label: '数据日期', value: meta.generatedAt ? meta.generatedAt.slice(0, 10) : '未知' },
      { label: '拉取时间', value: meta.fetchedAt ? meta.fetchedAt.slice(0, 16).replace('T', ' ') : '未知' }
    ],
    note: '源仓库每小时自动同步。要刷新就跟 AI 说「更新模型数据」。'
  };
}

exports.update_data = update_data;
exports.get_data_info = get_data_info;
exports.search_models = search_models;
exports.get_model = get_model;
exports.compare_models = compare_models;
exports.list_vendors = list_vendors;
exports.detail_card = detail_card;
