# 插件开发说明（内部）

这个目录里是两个官方插件，同时也是插件系统的参考资料。

## 目录

```
calorie/     卡路里与蛋白质记录
  manifest.json      插件清单（工具声明、配置项、系统提示词）
  main.js            插件本体（单文件，直接可打包）

ynufe/       云南财经大学教务系统
  manifest.json
  captcha_templates.js   验证码字模表（由 CaptchaTemplates.kt 生成，311 张 14×36 位图）
  captcha.js             验证码本地 OCR 引擎
  parser.js              教务页面 HTML 解析
  yunfe.js               业务逻辑与工具入口
  build.py               把上面四个源码拼成 main.js 并打包
  main.js                构建产物，勿手改

aimodels/    大模型资料库（数据来自 liyupi/ai-model-world）
  manifest.json      插件清单
  main.js            插件本体（单文件）
  build.py           校验 manifest 与导出函数对得上，并打 zip
  test.js            Node 自测（假 dataStore/fetch + 真实数据快照，40+ 断言）
```

打好的 zip 放在 `docs/plugins/` 下（`calorie.zip`、`ynufe.zip`、`aimodels.zip`），导入用。

## 为什么 ynufe 要拼文件

沙箱只对 `manifest.entry` 指的那个文件求值一次，没有 `require()`，
所以多文件的插件必须在打包前拼成单个 `main.js`。源码拆开是为了可读，
`main.js` 是构建产物——**改完源码一定要重新跑构建**：

```bash
python3 docs/plugins/ynufe/build.py --zip
```

## 用到的沙箱能力

- `http.*` 会话式请求，自带 Cookie 罐。教务登录要先拿验证码和 JSESSIONID，
  登录成功后服务端还会换发新的会话 ID，每一跳的 `Set-Cookie` 都得留住——
  这是 `fetch` 做不到的。
- `image.decode` 把图片解成 RGBA 像素数组。沙箱没有 canvas，
  验证码 OCR 这类逐像素计算只能靠宿主解码。
- `dataStore` 按插件隔离的键值存储，卡路里的每日记录放在这里；
  aimodels 把整份模型库（约 600KB JSON）也塞在这里，配合模块级缓存避免反复 parse。
- `manifest.detailCard` 详情页数据卡片，声明一个导出函数名即可。
- `fetch` 走的是宿主那个带 `SettingsProxySelector` 的 OkHttpClient，
  所以在「设置 → 网络」里配的代理（含 clash 的 127.0.0.1:7897）对插件请求一样生效。

### 大 JSON 入库的注意点

aimodels 的数据源单个文件 2.6MB，直接 JSON.parse 对嵌入式 JS 引擎太重。
做法是先 fetch 成字符串、逐条裁剪成短字段再入库，分两个键写：

1. 先写 `db`（模型数组），成功后再写 `meta`（日期、厂商表、统计）。
2. 读的时候只认 `meta`——它在就代表 `db` 一定写完过，不会读到半截数据。

## 验证

插件的纯逻辑都可以在 Node 里跑（沙箱 API 用假的顶上），
不必装到设备上试。改完代码建议至少跑一遍解析器和插件的自测。
