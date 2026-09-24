// 插件自测：用 Node 顶上沙箱的 dataStore / fetch，跑真实数据全流程。
// 用法：node test.js
// 数据源优先用本地快照 /tmp/amt_models.json；没有就现下（国内需要代理：
//   https_proxy=http://127.0.0.1:7897 node test.js）
// 快照不进仓库——源文件 2.6MB，每小时还在变。

'use strict';
const fs = require('fs');

const SNAPSHOT = '/tmp/amt_models.json';
const DATA_URL = 'https://raw.githubusercontent.com/liyupi/ai-model-world/main/data/models.json';

function loadSource() {
  if (fs.existsSync(SNAPSHOT)) {
    return JSON.parse(fs.readFileSync(SNAPSHOT, 'utf8'));
  }
  console.log('# 没有本地快照，正在下载 ' + DATA_URL + ' ...');
  const { execFileSync } = require('child_process');
  const raw = execFileSync('curl', ['-sSL', '--max-time', '120', DATA_URL], {
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
  });
  fs.writeFileSync(SNAPSHOT, raw);
  return JSON.parse(raw);
}

const source = loadSource();

// ---- 沙箱假体 ---------------------------------------------------------------
const store = new Map();
const dataStore = {
  set: (k, v) => { store.set(k, String(v)); return true; },
  get: (k) => (store.has(k) ? store.get(k) : null),
  del: (k) => store.delete(k),
  list: (prefix) => [...store.keys()].filter((k) => k.startsWith(prefix || '')),
};

const fetch = () => ({ ok: true, status: 200, body: JSON.stringify(source) });

// ---- 加载插件 ---------------------------------------------------------------
const code = fs.readFileSync(__dirname + '/main.js', 'utf8');
const exportsObj = {};
const fn = new Function('dataStore', 'fetch', 'config', 'exports', code + '\nreturn exports;');
const api = fn(dataStore, fetch, {}, exportsObj);

// ---- 断言辅助 ---------------------------------------------------------------
let failed = 0;
function check(name, cond, extra) {
  if (cond) {
    console.log('  ok  ' + name);
  } else {
    failed++;
    console.log('FAIL  ' + name + (extra !== undefined ? ' :: ' + JSON.stringify(extra).slice(0, 400) : ''));
  }
}

// ---- 用例 -------------------------------------------------------------------
console.log('== update_data ==');
const upd = api.update_data({});
check('update 成功', upd.success === true, upd);
// 源数据每小时同步、模型每月都在加，所以只校验下界，不写死数字
check('模型数 >= 600', upd.model_count >= 600, upd.model_count);
check('厂商数 >= 60', upd.vendor_count >= 60, upd.vendor_count);
check('data_date 有值', !!upd.data_date, upd);
check('落库体积可接受', store.get('db').length < 2 * 1024 * 1024, store.get('db').length);

console.log('== get_data_info ==');
const info = api.get_data_info({});
check('info has_data', info.has_data === true, info);

console.log('== get_model 完整档案 ==');
const g1 = api.get_model({ id: 'anthropic/claude-opus-4' });
check('get_model 成功', g1.success === true, g1);
check('带 scores 数组', g1.model && Array.isArray(g1.model.scores) && g1.model.scores.length > 0,
  g1.model && g1.model.scores && g1.model.scores.length);
const aider = g1.model.scores.find((s) => s.benchmark === 'aider_polyglot');
check('aider_polyglot=72', aider && aider.score === 72, aider);
check('价格 input=15', g1.model.pricing.input_per_mtok === 15, g1.model.pricing);

console.log('== get_model 模糊名 ==');
const g2 = api.get_model({ id: 'deepseek v3.2' });
check('模糊唯一命中', g2.success === true && g2.model.id === 'deepseek/deepseek-v3.2', g2 && g2.model && g2.model.id);
const g3 = api.get_model({ id: 'opus' });
check('多个命中给候选', g3.success === false && Array.isArray(g3.candidates) && g3.candidates.length > 1, g3);
const g4 = api.get_model({ id: 'qwen max', vendor: 'alibaba' });
check('vendor 限定的模糊命中', g4.success === true && g4.model.id === 'alibaba/qwen-max', g4 && g4.model && g4.model.id);
// 「claude opus 4」虽然是 4.x 全家的公共子串，但正好等于 anthropic/claude-opus-4
// 的末段，末段全等可以放心选中
const g6 = api.get_model({ id: 'claude opus 4' });
check('末段全等可命中', g6.success === true && g6.model.id === 'anthropic/claude-opus-4', g6 && g6.model && g6.model.id);
// 真·多义（没有任何一个模型的末段就叫 opus 4）必须给候选而不是瞎猜
const g7 = api.get_model({ id: 'claude opus' });
check('公共子串不瞎猜', g7.success === false && g7.candidates.length > 1, g7 && g7.candidates && g7.candidates.length);
check('分隔符归一', api.get_model({ id: 'deepseek_v3.2' }).success === true, null);
check('精确 id 优先', api.get_model({ id: 'anthropic/claude-opus-4' }).success === true, null);

console.log('== search_models ==');
const s1 = api.search_models({ benchmark: 'eci', limit: 10 });
check('eci 排行', s1.success === true && s1.models.length === 10, s1 && s1.models && s1.models.length);
check('排行降序', s1.models[0].bench_score >= s1.models[9].bench_score,
  s1.models.map((x) => x.bench_score));
check('排行含名字', !!s1.models[0].name && !!s1.models[0].id, s1.models[0]);

const s2 = api.search_models({ query: 'deepseek', limit: 50 });
check('deepseek 搜索', s2.success === true && s2.total_matched >= 5, s2.total_matched);

const s3 = api.search_models({ max_input_price: 0.5, tool_call: true, sort: 'context', limit: 10 });
check('便宜+工具调用,按上下文排', s3.success === true && s3.models.length > 0 &&
  s3.models.every((m) => m.input_price !== null && m.input_price <= 0.5 && m.tool_call === true), s3.models && s3.models[0]);
const ctxs = s3.models.map((m) => m.context_window).filter((x) => x != null);
check('context 降序', ctxs.every((v, i) => i === 0 || ctxs[i - 1] >= v), ctxs);

const s4 = api.search_models({ benchmark: 'no_such_bench' });
check('未知基准报错+提示', s4.success === false && s4.known_benchmarks && s4.known_benchmarks.eci, s4);

const s5 = api.search_models({ released_after: '2026-01-01', limit: 60 });
check('新模型筛选', s5.success === true && s5.models.every((m) => m.release_date >= '2026-01-01'),
  s5.models && s5.models.slice(0, 3).map((m) => [m.id, m.release_date]));

const s6 = api.search_models({ open_weights: true, benchmark: 'aime', limit: 5 });
check('开源+ aime 排行', s6.success === true && s6.models.length > 0 &&
  s6.models.every((m) => m.open_weights === true), s6.models && s6.models[0]);

const s7 = api.search_models({ query: 'opus', limit: 30 });
check('opus 搜索含退役标记', s7.success === true && s7.total_matched >= 3, s7.total_matched);

console.log('== compare_models ==');
const c1 = api.compare_models({ ids: ['anthropic/claude-opus-4', 'openai/gpt-5.2', 'deepseek/deepseek-v3.2'] });
check('对比成功', c1.success === true, c1);
check('3 列', c1.columns && c1.columns.length === 3, c1.columns);
check('含价格行', c1.rows.some((r) => r.metric.includes('输入价')), null);
const benchRows = c1.rows.filter((r) => r.metric.includes('('));
check('有共有基准行', benchRows.length > 0, benchRows.map((r) => r.metric).slice(0, 5));
check('基准行数值对齐', benchRows.every((r) => r[0] !== undefined || r[0] === null), null);

const c2 = api.compare_models({ ids: ['opus'] });
check('模糊多命中报错', c2.success === false, c2);
const c3 = api.compare_models({ ids: ['anthropic/claude-opus-4'] });
check('单个拒绝', c3.success === false, c3);

console.log('== list_vendors ==');
const v1 = api.list_vendors({});
check('厂商列表', v1.success === true && v1.vendor_count >= 60, v1.vendor_count);
const vAnthropic = v1.vendors.find((v) => v.id === 'anthropic');
check('anthropic 中文名', vAnthropic && /Anthropic/i.test(vAnthropic.name), vAnthropic);
const v2 = api.list_vendors({ vendor: 'deepseek' });
check('deepseek 旗下模型', v2.success === true && v2.model_count >= 3, v2 && v2.model_count);
const v3 = api.list_vendors({ vendor: '字节' });
check('中文厂商名定位', v3.success === true && v3.model_count >= 3, v3 && (v3.vendor || v3.error));
const v4 = api.list_vendors({ vendor: 'openai' });
check('英文 id 不受中文名影响', v4.success === true && v4.model_count >= 10, v4 && v4.model_count);
const s8 = api.search_models({ query: '豆包', limit: 5 });
check('中文名搜模型', s8.success === true && s8.total_matched > 0, s8 && s8.total_matched);
const s9 = api.search_models({ vendor: '月之暗面', limit: 5 });
check('中文厂商名筛模型', s9.success === true && s9.total_matched > 0, s9 && s9.total_matched);

console.log('== detail_card ==');
const d1 = api.detail_card({});
check('卡片带数据', d1 && d1.items && d1.items.length >= 3, d1);

console.log('== update 覆盖旧数据 ==');
const upd2 = api.update_data({});
check('二次 update 成功', upd2.success === true, upd2);
const g5 = api.get_model({ id: 'anthropic/claude-opus-4' });
check('二次取数正常', g5.success === true && g5.model.scores.length > 0, null);

console.log('== 异常路径 ==');
// 沙箱的 fetch 请求失败时是抛异常（不是 ok:false），插件必须自己接住
const offlineStore = new Map();
const offlineDataStore = {
  set: (k, v) => { offlineStore.set(k, String(v)); return true; },
  get: (k) => (offlineStore.has(k) ? offlineStore.get(k) : null),
  del: (k) => offlineStore.delete(k),
  list: (p) => [...offlineStore.keys()].filter((k) => k.startsWith(p || '')),
};
const boom = () => { throw new Error('Failed to connect to /127.0.0.1:7897'); };
const offlineApi = new Function('dataStore', 'fetch', 'config', 'exports', code + '\nreturn exports;')(
  offlineDataStore, boom, {}, {});

let threw = null;
let offUpd = null;
try { offUpd = offlineApi.update_data({}); } catch (e) { threw = e; }
check('断网 update 不抛异常', threw === null, threw && threw.message);
check('断网 update 返回可读错误', offUpd && offUpd.success === false && /下载失败/.test(offUpd.error),
  offUpd && offUpd.error);
check('断网时无数据提示', offlineApi.get_data_info({}).has_data === false, null);
check('断网时详情卡不炸', !!offlineApi.detail_card({}).title, null);

// 库坏了（半截 JSON）应当当作没数据，而不是抛异常
const brokenStore = new Map();
const brokenDataStore = {
  set: (k, v) => { brokenStore.set(k, String(v)); return true; },
  get: (k) => (brokenStore.has(k) ? brokenStore.get(k) : null),
  del: (k) => brokenStore.delete(k),
  list: (p) => [...brokenStore.keys()].filter((k) => k.startsWith(p || '')),
};
brokenDataStore.set('db', '{"half":');
brokenDataStore.set('meta', JSON.stringify({ modelCount: 1, vendors: {} }));
const brokenApi = new Function('dataStore', 'fetch', 'config', 'exports', code + '\nreturn exports;')(
  brokenDataStore, fetch, {}, {});
let brokeThrew = null;
let brokeSearch = null;
try { brokeSearch = brokenApi.search_models({}); } catch (e) { brokeThrew = e; }
check('坏库不抛异常', brokeThrew === null, brokeThrew && brokeThrew.message);
check('坏库当没数据', brokeSearch && brokeSearch.success === true && brokeSearch.has_data === false,
  brokeSearch);
check('坏库被清掉', brokenDataStore.get('db') === null, brokenDataStore.get('db'));

// 源站返回空 models：不能拿空的覆盖掉本地已有的库
const emptyStore = new Map();
const emptyDataStore = {
  set: (k, v) => { emptyStore.set(k, String(v)); return true; },
  get: (k) => (emptyStore.has(k) ? emptyStore.get(k) : null),
  del: (k) => emptyStore.delete(k),
  list: (p) => [...emptyStore.keys()].filter((k) => k.startsWith(p || '')),
};
const emptyApi = new Function('dataStore', 'fetch', 'config', 'exports', code + '\nreturn exports;')(
  emptyDataStore, fetch, {}, {});
emptyApi.update_data({});                       // 先灌一份好数据
const before = emptyStore.get('db').length;
const emptyFetch = () => ({ ok: true, status: 200, body: JSON.stringify({ generatedAt: 'x', models: [], vendors: [] }) });
const emptyApi2 = new Function('dataStore', 'fetch', 'config', 'exports', code + '\nreturn exports;')(
  emptyDataStore, emptyFetch, {}, {});
const emptyUpd = emptyApi2.update_data({});
check('空源数据被拒绝', emptyUpd.success === false && /一个模型都没有/.test(emptyUpd.error), emptyUpd);
check('拒绝后旧数据还在', emptyStore.get('db').length === before, emptyStore.get('db').length);
check('拒绝后仍能查', emptyApi2.get_data_info({}).has_data === true, null);

console.log(failed === 0 ? '\n全部通过' : '\n' + failed + ' 项失败');
process.exit(failed === 0 ? 0 : 1);
