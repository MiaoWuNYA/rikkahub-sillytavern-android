// 云南财经大学教务系统插件 —— 业务逻辑层。
//
// 从原应用内硬编码实现（data/ai/tools/ynufe/*.kt）移植而来。
// 导出 8 个 action：status / login / logout / schedule / grade / exam /
// announcement / classroom，以及详情页卡片 detail_card。
//
// 会话靠宿主 http 桥接自带的 Cookie 罐维持（服务端登录成功会换发 JSESSIONID，
// 每一跳响应的 Set-Cookie 都由宿主留住），所以这里不自己管 cookie。
// 账号密码来自插件设置（config.account / config.password），明文存在设置里，
// 不再需要原来那套本地异或混淆——混淆的密钥同样躺在本地，防不住任何人。

var YNUFE_BASE = 'https://xjwis.ynufe.edu.cn';
var YNUFE_ACTIONS = 'status | login | logout | schedule | grade | exam | announcement | classroom | help';
var YNUFE_HEADERS = {
  'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8',
  'Accept-Language': 'zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7',
  'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36',
  'Referer': YNUFE_BASE + '/jsxsd/framework/xsMain.jsp'
};

// 课表缓存：查过一次就存下来，详情页卡片靠它算「今天的课」，
// 不用为了显示一张卡片再打一次网络。
var YNUFE_CACHE_TIMETABLE = 'timetable_cache';

function configStr(name) {
  try {
    if (typeof config === 'undefined' || !config) return '';
    var v = config[name];
    if (v === undefined || v === null) return '';
    return String(v).replace(/^\s+|\s+$/g, '');
  } catch (e) {
    return '';
  }
}

// ---------------------------------------------------------------------------
// 登录协议
// ---------------------------------------------------------------------------

/**
 * 强智教务的账号密码加密（变种 base64）。
 *
 * 拼的是 UTF-8 字节，所以先手工编码；沙箱里没有 TextEncoder。
 */
function utf8Bytes(s) {
  var out = [];
  for (var i = 0; i < s.length; i++) {
    var c = s.charCodeAt(i);
    if (c < 0x80) {
      out.push(c);
    } else if (c < 0x800) {
      out.push(0xC0 | (c >> 6), 0x80 | (c & 0x3F));
    } else if (c >= 0xD800 && c <= 0xDBFF && i + 1 < s.length) {
      var c2 = s.charCodeAt(i + 1);
      if (c2 >= 0xDC00 && c2 <= 0xDFFF) {
        i++;
        var cp = 0x10000 + ((c - 0xD800) << 10) + (c2 - 0xDC00);
        out.push(0xF0 | (cp >> 18), 0x80 | ((cp >> 12) & 0x3F),
          0x80 | ((cp >> 6) & 0x3F), 0x80 | (cp & 0x3F));
      } else {
        out.push(0xE0 | (c >> 12), 0x80 | ((c >> 6) & 0x3F), 0x80 | (c & 0x3F));
      }
    } else {
      out.push(0xE0 | (c >> 12), 0x80 | ((c >> 6) & 0x3F), 0x80 | (c & 0x3F));
    }
  }
  return out;
}

function encodeInp(input) {
  if (!input) return '';
  var keyStr = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
  var bytes = utf8Bytes(input);
  var sb = '';
  var i = 0;
  while (i < bytes.length) {
    var chr1 = bytes[i++];
    var chr2 = i < bytes.length ? bytes[i++] : -1;
    var chr3 = i < bytes.length ? bytes[i++] : -1;

    var enc1 = chr1 >> 2;
    var enc2 = ((chr1 & 3) << 4) | (chr2 < 0 ? 0 : chr2 >> 4);
    // chr2/chr3 缺席时是 -1，必须先判空再移位：-1 >> 6 还是 -1，会污染或运算
    var enc3, enc4;
    if (chr2 < 0) {
      enc3 = 64;
    } else if (chr3 < 0) {
      enc3 = (chr2 & 15) << 2;
    } else {
      enc3 = ((chr2 & 15) << 2) | (chr3 >> 6);
    }
    enc4 = chr3 < 0 ? 64 : (chr3 & 63);

    sb += keyStr.charAt(enc1) + keyStr.charAt(enc2) + keyStr.charAt(enc3) + keyStr.charAt(enc4);
  }
  return sb;
}

// ---------------------------------------------------------------------------
// HTTP
// ---------------------------------------------------------------------------

function resolveUrl(endpoint) {
  if (endpoint.indexOf('http://') === 0 || endpoint.indexOf('https://') === 0) return endpoint;
  return YNUFE_BASE + endpoint;
}

/**
 * 服务器把登录页/错误页当业务页返回时（会话过期），用它一眼认出来，
 * 免得拿登录页的 HTML 去解析，最后解析出一堆空数据。
 */
function checkSessionTimeout(text, endpoint) {
  if (endpoint.indexOf('LoginToXkLdap') >= 0) return;

  var structural = text.indexOf('sys/login.jsp') >= 0 ||
    text.indexOf('LoginToXkLdap') >= 0 ||
    text.indexOf('SYSTEM_LOGIN') >= 0;
  var hasBusiness = text.indexOf('id="dataList"') >= 0 ||
    text.indexOf('id="kbtable"') >= 0 ||
    text.indexOf('middletopdwxxcont') >= 0 ||
    text.indexOf('id="Table1"') >= 0;
  var is404 = (text.indexOf('404 error') >= 0 || text.indexOf('404 错误') >= 0 ||
    text.indexOf('Request Page Not Found') >= 0 || text.indexOf('您请求的页面不存在') >= 0 ||
    text.indexOf('HTTP Status 404') >= 0) && !hasBusiness;
  var phraseOnly = (text.indexOf('非法访问') >= 0 || text.indexOf('请重新登录') >= 0 || is404) && !hasBusiness;

  if (structural || phraseOnly) throw new Error('session expired on ' + endpoint);
}

function getHtml(endpoint) {
  var r = http.get(resolveUrl(endpoint), { headers: YNUFE_HEADERS });
  var text = r.text();
  checkSessionTimeout(text, endpoint);
  return text;
}

function postHtml(endpoint, formData) {
  var r = http.postForm(resolveUrl(endpoint), formData, { headers: YNUFE_HEADERS });
  var text = r.text();
  checkSessionTimeout(text, endpoint);
  return text;
}

/** 拉一张验证码图片，返回 base64（不带 data: 前缀）。 */
function getCaptchaBase64() {
  var headers = {
    'Accept': 'image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8',
    'Accept-Language': YNUFE_HEADERS['Accept-Language'],
    'User-Agent': YNUFE_HEADERS['User-Agent'],
    'Referer': YNUFE_HEADERS['Referer']
  };
  var r = http.get(YNUFE_BASE + '/jsxsd/verifycode.servlet?t=' + new Date().getTime(), { headers: headers });
  return r.base64();
}

// ---------------------------------------------------------------------------
// 会话
// ---------------------------------------------------------------------------

/** 能正常拉到主框架页就算会话有效。 */
function verifySession() {
  try {
    getHtml('/jsxsd/framework/xsMain_new.jsp?t1=1');
    return true;
  } catch (e) {
    return false;
  }
}

/** 当前学年学期，如 2025-2026-1。 */
function defaultSemesterId() {
  var now = new Date();
  var y = now.getFullYear();
  // getMonth() 是 0 起算，所以这里 +1 对齐自然月
  var m = now.getMonth() + 1;
  if (m >= 8 && m <= 12) return y + '-' + (y + 1) + '-1';
  if (m === 1) return (y - 1) + '-' + y + '-1';
  return (y - 1) + '-' + y + '-2';
}

// ---------------------------------------------------------------------------
// 登录
// ---------------------------------------------------------------------------

function doLogin(account, password, captcha) {
  var encoded = encodeInp(account) + '%%%' + encodeInp(password);
  try {
    var html = postHtml('/jsxsd/xk/LoginToXkLdap', {
      userAccount: account,
      userPassword: '',
      RANDOMCODE: captcha,
      encoded: encoded
    });

    if (html.indexOf('用户名或密码错误') >= 0 ||
      html.indexOf('账号或密码不正确') >= 0 ||
      html.indexOf('密码错误') >= 0) {
      return {
        success: false,
        bad_credentials: true,
        message: '学号或密码有误，请让用户到「插件 → 云南财经教务系统 → 配置」里核对后重试'
      };
    }
    if (html.indexOf('验证码错误') >= 0 || html.indexOf('验证码已过期') >= 0) {
      return { success: false, bad_captcha: true, message: '验证码错误' };
    }

    if (verifySession()) {
      return {
        success: true,
        logged_in: true,
        account: account,
        message: '登录成功，会话已保持，后续查询无需再次登录'
      };
    }
    return { success: false, bad_captcha: true, message: '登录后未能验证会话，可能验证码错误' };
  } catch (e) {
    return { success: false, error: '登录请求失败: ' + (e && e.message ? e.message : e) };
  }
}

/**
 * 登录流程：先本地 OCR 验证码自动登录，最多试 3 次。
 * 3 次都栽在验证码上，就把最后那张验证码以 data URI 返回，让 AI 展示给用户手输，
 * 用户读出来之后再带着 captcha 参数调一次 login。
 */
function loginFlow(manualCaptcha, explicitAccount, explicitPassword) {
  var account = explicitAccount || configStr('account');
  var password = explicitPassword || configStr('password');

  if (!account || !password) {
    return {
      success: false,
      need_login: true,
      has_credentials: false,
      message: '还没有保存学号和密码。请让用户打开「插件 → 云南财经教务系统 → 配置」，' +
        '把学号和密码填进去保存，然后再试；不要在对话里索取密码。'
    };
  }

  if (manualCaptcha) return doLogin(account, password, String(manualCaptcha).replace(/^\s+|\s+$/g, ''));

  var lastImage = '';
  for (var attempt = 1; attempt <= 3; attempt++) {
    var b64;
    try {
      b64 = getCaptchaBase64();
    } catch (e) {
      return { success: false, error: '验证码获取失败: ' + (e && e.message ? e.message : e) };
    }
    lastImage = b64;

    var code = '';
    try {
      code = recognizeCaptchaImage(b64).text;
    } catch (e) {
      code = '';
    }
    // 识别结果不是 4 位就换一张重试，别拿明显不对的东西去撞
    if (code.length !== 4) continue;

    var result = doLogin(account, password, code);
    if (result.logged_in || !result.bad_captcha) return result;
  }

  return {
    success: false,
    need_captcha: true,
    message: '自动识别验证码连续失败。请把下面这张验证码图片原样展示给用户，' +
      '请用户读出 4 位字符，然后调用 action=login 并带上 captcha 参数重试。',
    captcha_image: '![验证码](data:image/png;base64,' + lastImage + ')'
  };
}

// ---------------------------------------------------------------------------
// 数据查询
// ---------------------------------------------------------------------------

/** 查数据前确保已登录；凭据在的话就静默续期。 */
function ensureLoggedIn() {
  if (verifySession()) return null;

  var account = configStr('account');
  var password = configStr('password');
  if (!account || !password) {
    return {
      success: false,
      need_login: true,
      message: '尚未登录，且设置里没有保存学号密码。请引导用户到「插件 → 云南财经教务系统 → 配置」填写并保存。'
    };
  }

  var login = loginFlow(null, null, null);
  if (!login.logged_in) {
    return { success: false, need_login: true, login_result: login };
  }
  return null;
}

// 校区编号：安宁是个固定 GUID
function campusCodeOf(arg) {
  if (!arg) return '1';
  if (arg.indexOf('南') >= 0) return '1';
  if (arg.indexOf('北') >= 0) return '2';
  if (arg.indexOf('呈贡') >= 0) return '3';
  if (arg.indexOf('安宁') >= 0) return 'E298641275B7471181C291FA9BC76452';
  return arg; // 已经是编号就原样透传
}

// 教室类型编号取自学校教务实际下发的下拉选项（见查询页的 select#jslx）
function roomTypeCodeOf(arg) {
  if (!arg || arg === '全部') return '';
  if (/^\d+$/.test(arg)) return arg;
  if (arg.indexOf('一般') >= 0 || arg.indexOf('普通') >= 0) return '01';
  if (arg.indexOf('制图') >= 0) return '02';
  if (arg.indexOf('实验室') >= 0 || arg === '实验') return '03';
  if (arg.indexOf('语音') >= 0) return '04';
  if (arg.indexOf('多媒体') >= 0) return '05';
  if (arg.indexOf('视听') >= 0) return '07';
  if (arg.indexOf('机房') >= 0 || arg.indexOf('计算机') >= 0) return '08';
  if (arg.indexOf('网络') >= 0) return '09';
  if (arg.indexOf('体育') >= 0) return '15';
  if (arg.indexOf('琴') >= 0) return '12';
  if (arg.indexOf('画') >= 0) return '13';
  return arg;
}

function examTypeCode(arg) {
  if (arg === '期初' || arg === '1') return { code: '1', label: '期初' };
  if (arg === '期中' || arg === '2') return { code: '2', label: '期中' };
  return { code: '3', label: '期末' };
}

function clampInt(arg, fallback, lo, hi) {
  var n = parseInt(arg, 10);
  if (isNaN(n)) n = fallback;
  if (n < lo) n = lo;
  if (n > hi) n = hi;
  return n;
}

function queryData(action, params) {
  params = params || {};

  var gate = ensureLoggedIn();
  if (gate) {
    var out = { action: action, success: false, need_login: true };
    if (gate.message) out.message = gate.message;
    if (gate.login_result) out.login_result = gate.login_result;
    return out;
  }

  var sem = params.semester && String(params.semester).replace(/^\s+|\s+$/g, '')
    ? String(params.semester).replace(/^\s+|\s+$/g, '')
    : defaultSemesterId();

  try {
    var data;
    if (action === 'schedule') {
      data = parseTimetable(getHtml('/jsxsd/xskb/xskb_list.do?xnxq01id=' + encodeURIComponent(sem)));
      data.semester = sem;
      // 存一份给详情页卡片用；缓存写不进去不该影响这次查询
      try {
        dataStore.set(YNUFE_CACHE_TIMETABLE, JSON.stringify({
          ts: new Date().getTime(),
          semester: sem,
          courses: data.courses
        }));
      } catch (e) { /* 缓存失败忽略 */ }
    } else if (action === 'grade') {
      data = parseGrades(getHtml('/jsxsd/kscj/cjcx_list?xsfs=all'));
    } else if (action === 'exam') {
      var et = examTypeCode(params.type);
      var examHtml = postHtml('/jsxsd/xsks/xsksap_list', {
        xnxqid: sem,
        xqlb: et.code,
        xqlbmc: et.label
      });
      data = {
        exams: parseExams(examHtml, sem, et.label),
        semester: sem,
        examType: et.label
      };
    } else if (action === 'announcement') {
      data = { announcements: parseAnnouncements(getHtml('/jsxsd/ggly/ysgg_query')) };
    } else if (action === 'classroom') {
      var campus = campusCodeOf(params.campus);
      var roomType = roomTypeCodeOf(params.room_type);
      var week = clampInt(params.week, 1, 1, 30);
      var today = new Date().getDay(); // 0=周日
      var day = clampInt(params.day, today === 0 ? 7 : today, 1, 7);
      var jcStart = clampInt(params.session_start, 1, 1, 10);
      var jcEnd = clampInt(params.session_end, 2, 1, 10);
      if (jcStart > jcEnd) {
        return {
          action: action,
          success: false,
          error: '开始节次(' + jcStart + ')不能大于结束节次(' + jcEnd + ')'
        };
      }

      var roomHtml = postHtml('/jsxsd/kbxx/jsjy_query2', {
        typewhere: 'jszq',
        xnxqh: sem,
        xqbh: campus,
        jslx: roomType,
        zc: String(week),
        zc2: String(week),
        xq: String(day),
        xq2: String(day),
        jc: String(jcStart),
        jc2: String(jcEnd),
        // 学校教务配置的作息表模板固定 GUID；学校重新配置过的话这里要跟着更新
        kbjcmsid: 'C8B3C60AE20444B499A15ABFA3ECFF9D'
      });
      data = parseClassrooms(roomHtml);
      data.semester = sem;
      data.campus = campus;
      data.roomType = roomType || '全部';
      data.week = week;
      data.day = day;
      data.sessionRange = jcStart + '-' + jcEnd;
    } else {
      return { action: action, success: false, error: '未知 action: ' + action };
    }

    var result = { action: action, success: true };
    for (var k in data) {
      if (Object.prototype.hasOwnProperty.call(data, k)) result[k] = data[k];
    }
    return result;
  } catch (e) {
    var msg = e && e.message ? e.message : String(e);
    if (msg.indexOf('session expired') >= 0) {
      return {
        action: action,
        success: false,
        need_login: true,
        message: '会话在查询途中失效，请先调用 action=login 重新登录'
      };
    }
    return { action: action, success: false, error: '查询失败: ' + msg };
  }
}

// ---------------------------------------------------------------------------
// 工具入口
// ---------------------------------------------------------------------------

function ynufe(params) {
  params = params || {};
  var action = params.action ? String(params.action) : '';
  if (!action) {
    return { success: false, error: 'action 必填', allowed: YNUFE_ACTIONS };
  }

  if (action === 'help') {
    return {
      tool: 'ynufe',
      actions: YNUFE_ACTIONS,
      notes: '账号密码在「插件 → 云南财经教务系统 → 配置」里保存后自动复用；' +
        'schedule/grade/exam/announcement/classroom 未登录时会自动尝试静默登录；' +
        'classroom 可传 campus(南院/北院/呈贡/安宁)、room_type(一般/多媒体/实验/机房...)、' +
        'week(周次 1-30)、day(星期 1-7)、session_start/session_end(节次 1-10)'
    };
  }

  if (action === 'status') {
    var account = configStr('account');
    var hasCreds = !!(account && configStr('password'));
    var loggedIn = hasCreds ? verifySession() : false;
    var status = {
      action: action,
      logged_in: loggedIn,
      account: account,
      has_credentials: hasCreds
    };
    if (!loggedIn) {
      status.need_login = true;
      status.message = hasCreds
        ? '会话已过期，凭据已保存，调用 action=login 可自动重新登录'
        : '未登录且没有保存账号密码，请引导用户到「插件 → 云南财经教务系统 → 配置」填写并保存';
    }
    return status;
  }

  if (action === 'login') {
    var r = loginFlow(params.captcha, params.account, params.password);
    r.action = action;
    return r;
  }

  if (action === 'logout') {
    try {
      http.clearCookies();
    } catch (e) { /* 清 cookie 失败也当作已退出 */ }
    return {
      action: action,
      success: true,
      logged_in: false,
      message: '已退出登录，会话 Cookie 已清除；设置里保存的账号密码保留。'
    };
  }

  if (action === 'schedule' || action === 'grade' || action === 'exam' ||
    action === 'announcement' || action === 'classroom') {
    return queryData(action, params);
  }

  return { success: false, error: '未知 action: ' + action, allowed: YNUFE_ACTIONS };
}

// ---------------------------------------------------------------------------
// 详情页卡片
//
// 只读课表缓存算「今天有什么课」，不发网络请求——详情页卡片只给 8 秒，
// 而一次课表查询在校园网外很容易超过这个数。
// ---------------------------------------------------------------------------

function parseCachedTimetable() {
  var raw = null;
  try {
    raw = dataStore.get(YNUFE_CACHE_TIMETABLE);
  } catch (e) {
    return null;
  }
  if (!raw) return null;
  try {
    var parsed = JSON.parse(raw);
    if (!parsed || Object.prototype.toString.call(parsed.courses) !== '[object Array]') return null;
    return parsed;
  } catch (e) {
    return null;
  }
}

function detail_card(params) {
  var account = configStr('account');
  var hasCreds = !!(account && configStr('password'));

  var items = [];
  items.push({ label: '账号', value: account || '未设置' });
  items.push({ label: '凭据', value: hasCreds ? '已保存' : '未保存' });

  var note;
  if (!hasCreds) {
    note = '还没保存学号和密码。点下方「配置」填入后，就能直接问 AI 查课表、成绩、考试和空闲教室。';
  } else {
    var cache = parseCachedTimetable();
    if (!cache) {
      note = '跟 AI 说一句「查一下我的课表」，之后这里就会显示当天的课。';
    } else {
      // getDay(): 0=周日，课表里 1=周一 ... 7=周日
      var d = new Date().getDay();
      var today = d === 0 ? 7 : d;
      var todays = [];
      for (var i = 0; i < cache.courses.length; i++) {
        var c = cache.courses[i];
        if (c.day === today) todays.push(c);
      }
      todays.sort(function (a, b) { return a.slot - b.slot; });

      items.push({ label: '今日课程', value: todays.length + ' 节' });
      if (todays.length === 0) {
        note = '今天没有课。';
      } else {
        var names = [];
        for (var j = 0; j < todays.length; j++) {
          names.push(todays[j].name + '（' + todays[j].room + '）');
        }
        note = names.join('、');
      }
    }
  }

  return { title: '云南财经教务', items: items, note: note };
}

exports.ynufe = ynufe;
exports.detail_card = detail_card;
