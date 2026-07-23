"""
  【奇门三式】
  奇门遁甲            →  QimenEngine(JS,7局法+断语,拆补+茅山+置闰×时/日/月/年4流派+十干克应)  日家自包含(推荐),时家需先有日家baseChart
  大六壬              →  [首选] LiuRen(JS,eval_javascript)一键排盘字段全  [备选] kinliuren(Python,需手动节气/干支)  生日可选
  # 小六壬已移除 — 纯Python手写逻辑，无引擎调用

  kinliuren           →  kinliuren.Liuren(节气, 农历月, 日干支如'甲子', 时干支如'甲子')
      构造后调 .result(0) 排盘(返回课体/三传/神将等) .sike_dict()查四课
      .moongeneral()月将 .dayhorse()驿马
      参数从 lunar_python 取: EightChar.getDayGan()+getDayZhi()=日干支, 时干支同理

  QimenEngine → eval_javascript(library='qimen-engine', code='QimenEngine.generate({...})')
      可用type:
        {type:"rijia", year:2026, month:6, day:19}       → 日家,自包含(推荐)
        {type:"nianjia", year:2026}                       → 年家,自包含
        {type:"yuejia", year:2026, month:5}                → 月家,自包含(节气月)
        {type:"shijia", juMethod:"chaibu", baseChart:日家结果} → 时家,需先调日家拿baseChart
      返回 QimenChart: palaces(9宫数据), zhiFuStar/zhiShiDoor, dun/juNumber/yuan, fourPillars, kongWang

LiuRen(大六壬) → eval_javascript(library='liuren-engine', code="LiuRen.getLiuRenByDate(new Date(2026,5,19,12,0))")
      返回 LiuRenResult 含:
        dateInfo          → 日期+四柱干支(可在后续原子函数中复用)
        tianDiPan         → {diPan(地盘), tianPan(天盘), tianJiang(天将)}
        siKe              → {ke1,ke2,ke3,ke4} 四课
        sanChuan          → {chuChuan(初传),zhongChuan(中传),moChuan(末传),keTi(课体)}
        dunGan            → {子~亥} 遁干
        chuJian/fuJian    → 初监/覆监 {子~亥}
        jianChu           → 兼初 {子~亥}
        shenSha           → [{name,value,description}] 神煞数组
        yinYangGuiRen     → {yangGuiRen(阳贵人), yinGuiRen(阴贵人)} 天将分布
      ✅ getLiuRenByDate(Date) 一键起课最方便，返回全部盘面
      原子函数(参数中date需为DateInfo类型,来自result.dateInfo):
        LiuRen.getTianDiPan(dateInfo)                    → 天地盘 (dateInfo来自result)
        LiuRen.getSiKe(dateInfo, tianDiPan)              → 四课
        LiuRen.getSanChuan(siKe, tianDiPan)              → 三传
        LiuRen.fillSanChuan(sanChuan,tianDiPan,dunGan,riGan) → 补全三传+课体
        LiuRen.getDunGan(dateInfo, tianDiPan)            → 遁干
        LiuRen.getChuJian(dateInfo)                      → 初监
        LiuRen.getFuJian(dateInfo)                       → 覆监
        LiuRen.getJianChu(dateInfo, tianDiPan)           → 兼初
        LiuRen.getShenSha(dateInfo)                      → 神煞
        LiuRen.getYinYangGuiRen(dateInfo,tianDiPan)      → 阴阳贵人
        LiuRen.getTianJiang(tianDiPan, "子")              → 天将(需传地支)
        LiuRen.getShangShen(tianDiPan, "子")             → 上神(需传地支)
        LiuRen.getXiaShen(tianDiPan, "子")               → 下神(需传地支)
        LiuRen.getGanZhi2WuXing("甲子")                  → 干支五行(干支合成1串)
        LiuRen.getGanZhi2Relation("甲子")                → 干支关系(干支合成1串)
        LiuRen.getGongIndex(tianDiPan, "子")             → 宫索引(需传tianDiPan+地支)
        LiuRen.getLiuQin("甲", "乙")                     → 六亲
      快捷起课:
        LiuRen.getLiuRenBySiZhu("甲辰","丙寅","戊午","庚申")  → 通过四柱起课
        LiuRen.getNianMing(new Date(1990,5,15), "男")       → 虚岁流年
      日期工具:
        LiuRen.getDateByObj(new Date(...))               → Date对象→DateInfo
        LiuRen.getDateBySiZhu(y,m,d,h)                   → 四柱→DateInfo
      十二宫/拼音常量(数组,直接用索引取):
        LiuRen.DiZhiPinyin[0] = "zi"                     → 索引0=子
        LiuRen.DiZhiToPinyin.子 = "zi"                   → 字典查
        LiuRen.PinyinToDiZhi.zi = "子"                   → 拼音反查
      十二宫key为拼音: zi/chou/yin/mao/chen/si/wu/wei/shen/you/xu/hai
      ⚠️ 原子函数的第1个date参数是DateInfo类型(从getLiuRenByDate().dateInfo取),
         不是Date对象。直接传Date对象请用 getLiuRenByDate(Date) 一键起课。
      (零随机,纯确定性排盘)

【输入说明】
  奇门遁甲 → 日家只需日期, 时家需先日家baseChart
  大六壬 → 生日可选
  # 小六壬已移除
"""
