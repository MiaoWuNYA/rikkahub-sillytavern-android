// 卡路里记录插件
//
// 数据落在插件自己的 dataStore 里（宿主按插件 id 隔离的 KV），一天一条 key，
// 不依赖网络，所以 manifest 的 allowedHosts 是空的。
//
// 热量和蛋白质一起记，目标值都从插件设置页读。
// 沙箱注入的 dataStore 是同步的，不需要 await；Date / JSON 由 QuickJS 提供。

var DAY_PREFIX = 'day:';

function pad2(n) {
  return (n < 10 ? '0' : '') + n;
}

function formatDate(d) {
  return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate());
}

// 把模型给的日期写法收敛成 YYYY-MM-DD。
// 会遇到的写法很杂：YYYY-MM-DD、今天/昨天/前天、MM-DD、纯数字时间戳。
function resolveDate(input) {
  var now = new Date();
  if (input === undefined || input === null) return formatDate(now);

  var raw = String(input).replace(/^\s+|\s+$/g, '');
  if (raw === '') return formatDate(now);

  if (raw === '今天' || raw === '今日' || raw === 'today') return formatDate(now);

  if (raw === '昨天' || raw === '昨日' || raw === 'yesterday') {
    var y = new Date(now.getTime());
    y.setDate(y.getDate() - 1);
    return formatDate(y);
  }

  if (raw === '前天') {
    var b = new Date(now.getTime());
    b.setDate(b.getDate() - 2);
    return formatDate(b);
  }

  // 纯数字：10 位是秒，13 位是毫秒
  if (/^\d{10}$/.test(raw)) return formatDate(new Date(parseInt(raw, 10) * 1000));
  if (/^\d{13}$/.test(raw)) return formatDate(new Date(parseInt(raw, 10)));

  // YYYY-MM-DD / YYYY/MM/DD / MM-DD / MM/DD
  var m = raw.match(/^(\d{4})[-\/.](\d{1,2})[-\/.](\d{1,2})/);
  if (m) {
    return m[1] + '-' + pad2(parseInt(m[2], 10)) + '-' + pad2(parseInt(m[3], 10));
  }
  m = raw.match(/^(\d{1,2})[-\/.](\d{1,2})$/);
  if (m) {
    return now.getFullYear() + '-' + pad2(parseInt(m[1], 10)) + '-' + pad2(parseInt(m[2], 10));
  }

  return formatDate(now);
}

function guessMealType() {
  var h = new Date().getHours();
  if (h < 10) return '早餐';
  if (h < 15) return '午餐';
  if (h < 17) return '加餐';
  if (h < 21) return '晚餐';
  return '加餐';
}

function loadDay(date) {
  var raw = dataStore.get(DAY_PREFIX + date);
  if (!raw) return { date: date, meals: [] };
  try {
    var parsed = JSON.parse(raw);
    // 只有解析出来是个正常人样才用，否则一律当作空的一天——
    // 旧版本数据可能缺 meals 字段，手改坏了也不该让插件瘫掉
    if (!parsed || Object.prototype.toString.call(parsed.meals) !== '[object Array]') {
      return { date: date, meals: [] };
    }
    return parsed;
  } catch (e) {
    // 数据坏了不该让整个插件瘫掉，当作空的一天
    return { date: date, meals: [] };
  }
}

function saveDay(day) {
  dataStore.set(DAY_PREFIX + day.date, JSON.stringify(day));
}

/** 读设置页里的数值配置，没配或配坏了返回 fallback。 */
function configNumber(name, fallback) {
  var v = fallback;
  try {
    if (typeof config !== 'undefined' && config && config[name] !== undefined && config[name] !== null) {
      v = parseFloat(config[name]);
    }
  } catch (e) {
    v = fallback;
  }
  if (isNaN(v) || v < 0) v = 0;
  return v;
}

function calorieTarget() { return configNumber('daily_target', 0); }
function proteinTarget() { return configNumber('protein_target', 0); }

/** 保留一位小数，避免浮点累加出 82.30000000000001 这种数。 */
function round1(n) {
  return Math.round(n * 10) / 10;
}

// 旧记录没有 protein 字段，一律按 0 算
function calorieOf(meal) { return parseInt(meal.kcal, 10) || 0; }
function proteinOf(meal) { return parseFloat(meal.protein) || 0; }

function totalCalories(day) {
  var sum = 0;
  for (var i = 0; i < day.meals.length; i++) sum += calorieOf(day.meals[i]);
  return sum;
}

function totalProtein(day) {
  var sum = 0;
  for (var i = 0; i < day.meals.length; i++) sum += proteinOf(day.meals[i]);
  return round1(sum);
}

function newId() {
  return 'm' + new Date().getTime().toString(36) + Math.floor(Math.random() * 46656).toString(36);
}

/** 汇总结果里只有设了目标（>0）才带上对应的目标与剩余额度。 */
function attachTargets(result, calories, protein) {
  var ct = calorieTarget();
  if (ct > 0) {
    result.calorie_target = ct;
    result.calories_remaining = ct - calories;
  }
  var pt = proteinTarget();
  if (pt > 0) {
    result.protein_target = pt;
    result.protein_remaining = round1(pt - protein);
  }
  return result;
}

// ---------------------------------------------------------------------------
// 工具
// ---------------------------------------------------------------------------

function save_meal(params) {
  params = params || {};

  var desc = params.description ? String(params.description).replace(/^\s+|\s+$/g, '') : '';
  if (!desc) return { success: false, error: 'description 不能为空' };

  var kcal = parseInt(params.calories, 10);
  if (isNaN(kcal) || kcal < 0) {
    return { success: false, error: 'calories 必须是大于等于 0 的整数千卡' };
  }

  // 蛋白质可以缺省（用户只说了吃啥、没给营养信息时按 0 记）
  var protein = 0;
  if (params.protein !== undefined && params.protein !== null && params.protein !== '') {
    protein = parseFloat(params.protein);
    if (isNaN(protein) || protein < 0) {
      return { success: false, error: 'protein 必须是大于等于 0 的克数' };
    }
    protein = round1(protein);
  }

  var date = resolveDate(params.date);
  var mealType = params.meal_type ? String(params.meal_type) : guessMealType();

  var day = loadDay(date);
  var meal = {
    id: newId(),
    desc: desc,
    kcal: kcal,
    protein: protein,
    type: mealType,
    ts: new Date().getTime()
  };
  day.meals.push(meal);
  saveDay(day);

  var total = totalCalories(day);
  var proteinTotal = totalProtein(day);
  var result = {
    success: true,
    id: meal.id,
    date: date,
    meal_type: mealType,
    description: desc,
    calories: kcal,
    protein: protein,
    day_total: total,
    day_protein: proteinTotal,
    meal_count: day.meals.length
  };
  return attachTargets(result, total, proteinTotal);
}

function query_calories(params) {
  params = params || {};
  var date = resolveDate(params.date);
  var day = loadDay(date);
  var total = totalCalories(day);
  var proteinTotal = totalProtein(day);

  var items = [];
  for (var i = 0; i < day.meals.length; i++) {
    var m = day.meals[i];
    items.push({
      id: m.id,
      type: m.type,
      description: m.desc,
      calories: calorieOf(m),
      protein: proteinOf(m)
    });
  }

  var result = {
    success: true,
    date: date,
    total_calories: total,
    total_protein: proteinTotal,
    meal_count: day.meals.length,
    meals: items
  };
  return attachTargets(result, total, proteinTotal);
}

function delete_meal(params) {
  params = params || {};
  var id = params.id ? String(params.id) : '';
  if (!id) return { success: false, error: 'id 不能为空' };

  var date = resolveDate(params.date);
  var day = loadDay(date);
  var kept = [];
  var removed = null;
  for (var i = 0; i < day.meals.length; i++) {
    if (day.meals[i].id === id) {
      removed = day.meals[i];
    } else {
      kept.push(day.meals[i]);
    }
  }
  if (!removed) {
    return { success: false, error: '在 ' + date + ' 没找到 id 为 ' + id + ' 的记录，先调用 query_calories 确认' };
  }

  day.meals = kept;
  saveDay(day);

  var total = totalCalories(day);
  var proteinTotal = totalProtein(day);
  var result = {
    success: true,
    date: date,
    removed: removed.desc,
    removed_calories: calorieOf(removed),
    removed_protein: proteinOf(removed),
    day_total: total,
    day_protein: proteinTotal,
    meal_count: day.meals.length
  };
  return attachTargets(result, total, proteinTotal);
}

// ---------------------------------------------------------------------------
// 详情页数据卡片
//
// 宿主在插件详情页打开时调用一次，返回的 title / items / note 直接渲染成卡片。
// 抛错或超时只是卡片不显示，不影响上面几个工具。
// ---------------------------------------------------------------------------

function detail_card(params) {
  var today = formatDate(new Date());
  var day = loadDay(today);
  var total = totalCalories(day);
  var proteinTotal = totalProtein(day);
  var ct = calorieTarget();
  var pt = proteinTarget();

  var items = [{ label: '今日热量', value: total + ' kcal' }];
  if (ct > 0) {
    var calLeft = ct - total;
    items.push({ label: '热量剩余', value: Math.abs(calLeft) + ' kcal' });
  }

  items.push({ label: '今日蛋白', value: proteinTotal + ' g' });
  if (pt > 0) {
    var proLeft = round1(pt - proteinTotal);
    items.push({ label: '蛋白剩余', value: Math.abs(proLeft) + ' g' });
  }

  items.push({ label: '记录', value: day.meals.length + ' 条' });

  var note;
  if (day.meals.length === 0) {
    note = '今天还没有记录。跟 AI 说一句吃了什么就行，比如「午饭吃了牛肉面」。';
  } else {
    var last = day.meals[day.meals.length - 1];
    note = '最近一条：' + last.type + ' · ' + last.desc +
      '（' + calorieOf(last) + ' kcal / ' + proteinOf(last) + ' g）';
  }
  if (ct <= 0 && pt <= 0) {
    note += '\n还没设目标，可以在插件设置里填每日热量和蛋白质目标。';
  }

  return { title: '今日饮食', items: items, note: note };
}

exports.save_meal = save_meal;
exports.query_calories = query_calories;
exports.delete_meal = delete_meal;
exports.detail_card = detail_card;
