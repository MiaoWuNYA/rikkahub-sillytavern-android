"""
Python executor for Rikkahub.
Executes Python code with stdout capture, matplotlib auto-save,
and result file detection.

Available built-in functions (call these from your code):
  query_knowledge_base(query, limit=10)         - Search knowledge base
  add_knowledge_entry(title, content)           - Add entry to knowledge base
  update_knowledge_entry(id, title, content)    - Update knowledge entry
  delete_knowledge_entry(id)                    - Delete knowledge entry
  list_knowledge_entries(limit=20)               - List knowledge base entries
  list_conversations(limit=10)                   - List recent conversations
  get_conversation_messages(conv_id)             - Read conversation messages
  list_assistants()                              - List all assistants & their key settings
  get_assistant_settings(assistant_id)           - Read full assistant settings
  update_assistant_setting(id, key, value)       - Change any assistant setting
  get_setting(key)                               - Read global app setting
  update_setting(key, value)                     - Change global app setting
  get_app_info()                                 - App version & paths

*** 命理排盘规则 ***

【核心原则】每次排盘都走真实 Python 库计算，模型不虚构任何数据。
⚠️ 技能引用的库若未安装 → 忽略，以本路由表首选库为准，dir() 自探索其完整 API。

【排盘路由】需要完整命理分析时用。
输入要求列：生日=公历日期+时辰+性别，日期=只要日期年月日。

  用户问             →  首选                        ← 也能用这些               输入要求
  ─────────────────────────────────────────────────────────────────────────────────────────
  【中华正统】
  八字/四柱/大运      →  【双库并联】

╔══════════════════════════════════════════════════════╗
║  Step 1: 排盘 — lunar_python                       ║
║    ┌─ Solar.fromYmdHms(year,month,day,hour,minute,0)║
║    └─ → getLunar().getEightChar()  → 四柱干支       ║
╚══════════════════════════════════════════════════════╝
↓
╔══════════════════════════════════════════════════════╗
║  Step 2: 骨架 — lunar_python                        ║
║    ┌─ Solar.toFullString()  → 公历信息+星座          ║
║    ├─ Lunar.toFullString()  → 农历+纳音+星宿+        ║
║    │                         彭祖百忌+喜贵财神方位    ║
║    ├─ Lunar.getJieQiTable() → 24节气精确日期         ║
║    ├─ EightChar             → 四柱/纳音/五行/藏干    ║
║    │                          /十神/旬空/身宫        ║
║    └─ EightChar.getYun(1)   → 大运起岁+十步+流年    ║
╚══════════════════════════════════════════════════════╝
↓
╔══════════════════════════════════════════════════════╗
║  Step 3: 血肉 — bazi_china                          ║
║    ⚠️ 先 sys.path.insert(0, 'app/src/main/python')   ║
║    ⚠️ datas.nayins 的key是tuple;  datas.ganzhi60 的key是int 1-60║
║        正确: datas.nayins[('戊','寅')] → '城头土'   ║
║        错误: datas.nayins['戊寅'] → KeyError        ║
║    ⚠️ datas.empties key也是tuple!                   ║
║        正确: datas.empties[('甲','子')] → ('戌','亥')║
║    ⚠️ datas.tiaohous 是简码需解码:                  ║
║        '1丙2_甲' = 第一用神丙, 第二用神甲           ║
║        '1壬2丙甲' = 第一用神壬, 第二用神丙甲        ║
║    ⚠️ shengxiao.output(des,zhi,key) 三参调用         ║
║                                                    ║
║    ┌─ sizi.summarys['戊日壬子'] → 时柱古诀          ║
║    ├─ datas.day_shens['将星']['午'] → 日支神煞     ║
║    ├─ datas.year_shens['孤辰']['寅'] → 年支神煞    ║
║    ├─ datas.month_shens['天德']['子'] → 月支神煞   ║
║    ├─ datas.g_shens['天乙']['戊'] → 天乙贵人       ║
║    ├─ datas.minggongs['丑'] → 命宫断语             ║
║    ├─ datas.rizhus['戊午'] → 日主断语              ║
║    ├─ datas.jinbuhuan['戊午'] → 金不换调候+大运喜忌║
║    ├─ datas.lu_types['戊'][('戊','巳')] → 禄类型   ║
║    ├─ datas.self_zuo['印'] → 自坐解释              ║
║    ├─ yue.months['甲子'] → 月令详细论述            ║
║    ├─ ganzhi.gan_hes → 天干五合详解                ║
║    ├─ ganzhi.zhi_6hes/3hes/chongs/haies/poes/xings ║
║    ├─ ganzhi.gan_desc/zhi_desc → 干支特性           ║
║    ├─ ganzhi.zhi_zangs → 地支藏干(脏腑对应)        ║
║    └─ ganzhi.ten_deities['戊']['子'] → 十二宫状态  ║
╚══════════════════════════════════════════════════════╝
【关键坑位提醒】
• datas.nayins[('戊','寅')] → '城头土'     (查纳音, key是tuple)
• datas.ganzhi60[1] → '甲子'               (60甲子序列表, key是int 1-60)
• datas.empties key也是tuple: ('甲','子')
• datas.tiaohous 是简码: '1丙2_甲' 格式
  1=第一用神, 2=第二用神, _=分隔符
• shengxiao.output(des, zhi, key) 三参调用
• lunar_python.Yun.getDaYun() 返回的是list, 需遍历
• lunar_python的流年/流月: dy.getLiuNian(year)[0].getGanZhi()  (getLiuNian返回list, 取[0]为干支)
【分工总结】
lunar_python = 排盘骨架 + 大运流年 + 农历信息
→ 先跑, 不可替代
bazi_china   = 神煞断语 + 调候用神 + 古诀解盘
+ 干支关係库 + 禄/十二宫 + 流月论述
→ 后跑, 不可省略
                        生日（含时辰）
  紫微斗数            →  问用户选 Iztro(JS,iztro⭐3841原版,权威基准) 或 ziwei_paipan(Python,iztro标准算法port) 或 ZiweiNihai(JS,倪海夏天纪+古籍) 或多个一起对照   生日（含时辰）

  【奇门三式】
  奇门遁甲            →  QimenEngine(JS,7局法+断语,拆补+茅山+置闰×时/日/月/年4流派+十干克应)  日家自包含(推荐),时家需先有日家baseChart
  大六壬              →  [首选] LiuRen(JS,eval_javascript)一键排盘字段全  [备选] kinliuren(Python,需手动节气/干支)  生日可选
  小六壬(马前课)       →  lunar_python取月日时→6掌诀推算(大安/留连/速喜/赤口/小吉/空亡)                           无需出生（需月日时）

  【象数易】
  太玄筮法            →  问用户选 taixuanshifa(Python) 或 TaixuanLib(JS,4种起卦) 或对照(JS取随机得code→Python pan_from_code(code))   无需出生
  荆诀/先秦占卜       →  jingjue                                                 无需出生

  【六爻/卦象】
  六爻/周易/卦        →  问用户选 ichingshifa(Python,大衍筮法) 或 IchingShifa(JS,6种起卦) 或对照(JS取随机→同爻值喂Python qigua_manual)   无需出生（需起卦数）
  梅花易数            →  meihua_yi                  ← ichingshifa, 或手动排     无需出生（需起卦数）

  【塔罗】
  韦特塔罗            →  arcanite(Python,78张+牌阵+正逆位+元素尊贵)  无需出生
                        TarotKit(JS,中英双语要点式,drawCards裸抽无牌阵,5独立读牌面字段)  无需出生

【西洋占星】 (仅JS)   四个库: Caelus v0.23.0 + NatalEngine + CaelusBirth + Astronomy

╔══════════════════════════ 速览 ═══════════════════════════╗
║ NatalEngine → 日月升+文本+元素平衡+合盘评分+ACG+HD行运     ║
║ Caelus     → 本命(尊贵/格局/互容/7点)+12宫位+7种推运       ║
║ Caelus     → 3种合盘(比较/组合中点/戴维森)+行运+恒星        ║
║ Caelus     → 赤纬相位+日食月食+越界+空亡+映点+调和盘       ║
║ CaelusBirth→ 时区名→UT(备选, 同功能走NatalEngine.timezone) ║
║ Astronomy  → 星座仲裁(双引擎不一致时)+食相秒级精度          ║
╚═══════════════════════════════════════════════════════════╝
── NatalEngine（主力，一键出盘+解读文本） ──
  ── 西洋本命 ──
  NatalEngine.calculateAstrology("1990-06-15", hour, tz, lat, lon)
  → bigThree / summary / sun:{sign:{name,element,modality,ruler,traits,shadow},degree,longitude}
  → moon / rising / midheaven  各含{sign:{name,element},degree}
  → balance:{elements,modalities,dominantElement,dominantModality}  (仅日/月/升3星)
  → planets:{mercury,venus,mars,jupiter,saturn,uranus,neptune,pluto}
    每行星:{sign:{name,element},degree,longitude}  ⚠️ 无宫位/逆行/尊贵
  → nodes:{north,south}  → allAspects:[{a,b,aspect,orb}]
  精度: Meeus算法, VSOP87 — Moon误差0.00″
  ⚠️ NatalEngine 无宫位/逆行/尊贵 — 快速概览用, 深度分析走 Caelus。
  ── 合盘 ──
  NatalEngine.compareAstrology(chartA,chartB) → {overallScore,scoreLabel,aspectSummary,summary}
  NatalEngine.compareHumanDesign(hdA,hdB) → {overallScore,scoreLabel,connections,summary}
  NatalEngine.compareGeneKeys(gkA,gkB) → {overallScore,scoreLabel,pairings,summary}
  NatalEngine.compareCharts(personA,personB,systems?) → 三系统综合对比
  ── ACG 行星线 ──
  NatalEngine.calculateAstroCartography("1990-06-15", 12, -8, {latitude:39.9, longitude:116.4})
  → {sun:{MC:[{lat,lon}],IC:[...],ASC:[...],DSC:[...]}, moon:{...}, ...}
  NatalEngine.getLinesAtLocation(acgResult, lat, lon, orb?) → [{planet,angle,distanceKm,...}]
  NatalEngine.getLocationReport(acgResult, lat, lon, "Beijing") → 文本报告
  常量: NatalEngine.ACG_PLANET_INFO / NatalEngine.ACG_ANGLE_INFO
  ── HD 行运 ──
  NatalEngine.calculateHDTransits(hdResult, "2026-06-22", tz) → {activatedGates,definedCenters,...}
  NatalEngine.calculateTransitGates("2026-06-22", tz) → {sun:{gate,line}, moon:{...},...}
  ── 底层天文 ──
  NatalEngine.calculateBirthPositions(y, mo, d, h, tz, lat?, lon?) → {sun:{longitude,...},...}
  NatalEngine.getZodiacSign(longitude) → {name,element,modality,ruler,symbol}
  ── 时区/地名(同时被CaelusBirth提供,二选一) ──
  NatalEngine.resolveUtcOffset("1990-06-15", "12:00", "Asia/Shanghai") → -8  (小时数)
  NatalEngine.formatUtcOffset(-8) → "UTC-8"    (-5.5 → "UTC+5:30")
  NatalEngine.searchPlaces("Beijing") → [{name,latitude,longitude,timezone,countryCode}]
    ⚠️ searchPlaces 是 async, 返回 Promise — 用 .then(r=>{...}) 或 await
── ③ Caelus（深度分析，231+ 函数） ──
  初始化:
    // 用户报地名→searchPlaces拿时区→resolveUtcOffset拿偏移→拼ISO字符串
    var e=new Caelus.Engine(Caelus.embeddedData);
    var jd=Caelus.isoToJd("1990-06-15T12:00:00+08:00");  // +08:00是示例, isoToJd内部转UT; 已知UT可直接用 julianDay(y,m,d,h,m,s)
    var natalJd=jd; var targetJd=Caelus.julianDay(2026,6,22,12,0,0);
    var chart=e.chartAt(jd,lat,lon,{});  // 默认Placidus+热带
    var ctx=Caelus.interpretationContext(chart);
  ── 本命(18个) ──
  chart.bodies.sun → {lon,sign,signDeg,house,retrograde,dignities,speed,lat,dist,ra,dec}
    可选: sun/moon/mercury/venus/mars/jupiter/saturn/uranus/neptune/pluto/chiron/mean_node/true_node
    扩展: mean_lilith/true_lilith (Caelus.EXTRA_BODIES)
  Caelus.isDayChart(e,jd,lat,lon) → 昼夜盘
  Caelus.lots(e,jd,lat,lon) → {day,fortune,spirit,eros,necessity,courage,victory,nemesis}
    自算星座: Caelus.SIGNS[Math.floor(lon/30)]+" "+(lon%30).toFixed(1)+"°"
  Caelus.chartSignature(chart) → {elements:{},modalities:{},angularity:{},dominant:{element,modality,sign},ruler,hemispheres,quadrants,bodies}
  ctx.atoms→kind:"pattern" → T-square/Kite/Stellium/MysticRectangle/GrandTrine/GrandCross/Yod
  Caelus.detectPatterns(chart) → [{kind,bodies,apex?,orb}]  同上,直接取
  ctx.atoms→kind:"reception"→ 互容(domicile/exaltation/triplicity+mixed)
  ctx.atoms→kind:"dispositor"→ 定位星链
  ctx.atoms→kind:"dignity"  → almuten/face/term/triplicity
  Caelus.findAspects(chart.bodies) → 最紧相位排行
  Caelus.aspectBetween(e,"sun","mars",jd) → {aspect,orb,phase,separation}
  Caelus.aspectPhase(lonA,speedA,lonB,speedB,aspectDeg) → "applying"|"separating"|"exact"
  Caelus.declinationAspects(e,Caelus.DEFAULT_BODIES,jd,1) → [{a,b,kind:"parallel"|"contraparallel"},...]
  Caelus.voidOfCourse(e,jd) → {isVoid,sign,signExit,nextAspect|null}
  Caelus.outOfBounds(e,"moon",jd) → bool   Caelus.outOfBoundsMargin(e,"moon",jd) → 度数
  Caelus.dignityOf(e,"mars",jd) → ["domicile","exaltation",...]
  Caelus.planetarySect("mars") → "diurnal"|"nocturnal"|null
  Caelus.inSect("mars",isDay) → bool  得时/失时
  Caelus.gauquelinSector(e,"mars",jd,lat,lon) → 高奎林扇区
  Caelus.pheno(e,"mars",jd) → {phaseAngle,phase,elongation,diameter,magnitude}
  Caelus.signedElongation(lonA,lonB) → (-180..180]   Caelus.separation(lonA,lonB) → [0,180]
  Caelus.solarElongation(e,"mercury",jd) → 日距(度)
  engine.heliocentric("mars",jdUt) → {lon,lat,dist}  (Engine实例, 不是Caelus.)
  Caelus.solarPhase(e,"mercury",jd) → "cazimi"|"combust"|"under_beams"|null
  Caelus.planetaryHour(e,jd,lat,lon) → {ruler,kind,hour,start,end}
  Caelus.chartBrief(ctx,{limit?,kinds?,minSalience?}) → {jdUt,zodiac,facts:[{id,kind,text,salience}],prompt}
  ── 推运(7种,18个) ──
  法达:  Caelus.firdariaAt(e,natalJd,targetJd,lat,lon) → {day,major,sub}
         ⚠️ 必须传targetJd; 75年外返回{null,null}
         Caelus.firdaria(day,natalJd) → 完整周期表
  ZR:    Caelus.zrAt(e,natalJd,targetJd,lat,lon) → {lot,lot_sign,day,l1?,l2?,l3?,l4?}
  主限:  Caelus.primaryDirections(e,jd,lat,lon,bodies?,key?,maxYears?,yearLength?)
         → [{body,angle:"MC"|"IC"|"ASC"|"DSC",arc,years,jd}]
         时间键 KEYS:{naibod:0.9856,ptolemy:1.0,brahe:0.986,cardan:0.985,simmonite:0.985}
  世俗:  Caelus.mundaneDirections(e,natalJd,lat,lonEast,bodies?,key?,maxYears?,yearLength?)
         → [{promissor,significator,arc,years,jd}]  Placidus半弧, 行星到行星
  太阳弧:Caelus.solarArc(e,natalJd,targetJd,yearLength?,zodiac?) → 度数值
         等价: Caelus.directedLongitude(e,body,natalJd,targetJd,key?,zodiac?)
  次限:  Caelus.progressedLongitude(e,"sun",natalJd,targetJd,yearLength?,zodiac?) → 经度
         Caelus.progressedJd(natalJd,targetJd,yearLength?) → 数值,传chartAt得整盘次限
  小限:  Caelus.profectionAt(e,natalJd,targetJd,lat,lon)
         → {age_years,month,annual:{sign,sign_index,house,lord},monthly:{sign,sign_index,house,lord}}
  回归:  Caelus.solarReturn(e,natalJd,start,end,zodiac?)/lunarReturn → [jd,...]
         Caelus.returns(e,body,natalJd,start,end,zodiac?,maxHits?) → [jd,...]
  Caelus.stations(e,"saturn",jdStart,jdEnd) → [[jd,"retrograde"|"direct"],...]
  ── 合盘(3种,5个) ──
  比较:   Caelus.synastryAspects(chartA,chartB,maxOrb?,orbs?) → [{a,b,aspect,orb,strength}]
          Caelus.synastryOverlays(chartA,chartB) → {aInB:{body:house},bInA:{body:house}}
  组合:   Caelus.compositeLongitudes(e,jdA,jdB,bodies,zodiac?) ← 不是(chartA,chartB)
          Caelus.compositePlacements(e,jdA,jdB,bodies?,zodiac?) → [{body,lon,sign,signDeg},...]
  戴维森: Caelus.davisonParams(jdA,latA,lonA,jdB,latB,lonB) → [midJd,midLat,midLon]
  增强:   Caelus.enrichSynastryOptions(e,chartA,chartB,{orb?,zodiac?}) → {synastry,composite}
          合并到 ctx: interpretationContext(chartA,{...enrichSynastryOptions(...)})
  ── 行运(12个) ──
  Caelus.transitAspects(natalChart,e,transitJd,{maxOrb?,zodiac?,orbs?,bodies?})
    → [{transit,natal,aspect,orb,phase,strength,natalHouse}]
  Caelus.scan({start,end,step,onProgress?,progressEvery?},fn) → 批次扫描
  Caelus.rankMoments({start,end,step,limit?,minScore?},scoreFn) → [{jd,score}]
  Caelus.when(e,predicate,jdStart,jdEnd,{step?,maxIntervals?}) → [[start,end],...]
    Caelus.aspect(body,kind,target,orb?,zodiac?) → Predicate   (与定点/另一星成指定相位)
    Caelus.inSign(body,sign,zodiac?) → Predicate              (在指定星座)
    Caelus.retrograde(body,zodiac?) → Predicate               (逆行)
    Caelus.notRetrograde(body,zodiac?) → Predicate            (顺行/停)
    Caelus.allOf(...preds) → Predicate   Caelus.anyOf(...preds) → Predicate
    Caelus.notOf(pred) → Predicate        组合任意条件
  Caelus.crossings(e,body,targetLon,jdStart,jdEnd,zodiac?,maxHits?) → [jd,...]
    ⚠️ 逆行体可穿3次同经度, 全部返回, 按时间排序
  ── 恒星(2个) ──
  e.starConjunctions(chart,{orb}) → [{body,star,orb},...]
  Caelus.starParans(e,jd,lat,stars,bodies?) → [{star,star_angle,body,body_angle,jd,gap_min},...]
  ── 天文事件(5个) ──
  Caelus.lunarEclipses(e,jdStart,jdEnd)/solarEclipses  (Meeus精度)
    需要秒级精确时刻用 Astronomy.SearchLunarEclipse/SearchGlobalSolarEclipse
  Caelus.lunarPhases(e,jdStart,jdEnd,maxHits?) → [[jd,"new"|"first_quarter"|"full"|"last_quarter"],...]
  Caelus.riseSet(e,body,jdStart,lat,lon,"rise"|"set"|"mtransit"|"itransit",
    {altM?,pressure?,tempC?,searchDays?,discCenter?}) → jd|null  极昼/极夜=null
  Caelus.crossings(e,body,targetLon,jdStart,jdEnd,zodiac?,maxHits?) → [jd,...]
  Caelus.stations(e,"saturn",jdStart,jdEnd) → [[jd,"retrograde"|"direct"],...]
  ── 宫位(12种制式) ──
  e.chartAt(jd,lat,lon,{houseSystem:"koch"}) 切换制式
  Caelus.normalizeHouseSystem("whole sign") → "whole_sign"  容错输入
  有效值: placidus/koch/regiomontanus/campanus/porphyry/equal/
          whole_sign/alcabitius/morinus/meridian/polich_page/vehlow
  Caelus.houseOf(lon,cusps)/Caelus.houseLord(ascSign,n)  (ascSign=热带0-11)
  ── 上下文增强 ──
  Caelus.enrichContextOptions(e,chart,{jd,lat,lonEast,zodiac?},
    {transits?,timelords?,vedic?,transitOrb?})
  → {transits,timelords,vedic}  合并到 interpretationContext(chart,{...base,...extras})
  ── 其他常用 ──
  Caelus.harmonicChart(e,jd,bodies,n) → 调和盘
  Caelus.astrocartography(e,jd,bodies,latMin?,latMax?,latStep?) → ACG行星线
  Caelus.parans(e,jd,lat,bodies?,toleranceMin?) → 四轴共升共落
  Caelus.antiscion(lon)/contraAntiscion(lon) → 映点/反映点
  Caelus.midpointLon(lon1,lon2) → 中点
  Caelus.ephemeris(e,bodies,{start,end,step,value?,zodiac?}) → {body:[{jd,value},...]}
    value默认"longitude", 可选"latitude"/"declination"/"rightAscension"/"speed"
  Caelus.sampleCount(start,end,step) → 样本数
  Caelus.element(sign)→"fire"|...  Caelus.modality(sign)→"cardinal"|...
  Caelus.quadrant(house)→1-4  Caelus.angularity(house)→"angular"|"succedent"|"cadent"
  Caelus.unitVector(lonDeg,latDeg)→[x,y,z]  Caelus.angularSeparation3d(lonA,latA,lonB,latB)→度
  常量: DEFAULT_BODIES/SIGNS/BODIES/EXTRA_BODIES/ASPECTS/DEFAULT_ORBS/DOMICILE/EXALTATION/
        HOUSE_SYSTEMS/TROPICAL_YEAR/DEG/ARCSEC/J2000/LIGHT_TIME_AU
── ④ Astronomy（精度备份+仲裁） ──
  调它只有两种情况:
    ① Caelus和NatalEngine对同一行星输出不同星座时，以它为准(仲裁)
    ② 问日食月食精确到秒的时刻时，用它拿秒级时间(Caelus返回类型+时间范围)
  其余不调。CAELUS_VSOP87 != ASTRONOMY_VSOP87 (6弧秒算法差<400弧秒位置模糊, 调了等于没调)。
  调用: var t=new Astronomy.MakeTime(new Date(Date.UTC(y,m-1,d,h,m)));
        Astronomy.EclipticLongitude(Astronomy.Body.Mercury,t) → 黄经
        Sun用Astronomy.SunPosition(t).elon, Moon用new Astronomy.Ecliptic(Astronomy.GeoMoon(t)).elon
        Astronomy.SearchLunarEclipse(new Date(...)) / SearchGlobalSolarEclipse(...)
        Astronomy.SearchRiseSet(Astronomy.Body.Sun,new Astronomy.Observer(lat,lon,0),1,new Date(...),1)
        Astronomy.Seasons(2026) / Astronomy.MoonPhase(new Date(...))
── ⑤ HoroscopeJS（已被Caelus完全覆盖，不再推荐） ──
  ⚠️ 日期参数是date不是day: {year,month,date,hour,minute}
╔══════════════════ 参数坑 ══════════════════╗
║ vargaAt(e,jd,9)      ← 数字,不是"D9"       ║
║ hasAspect({})(ctx)    ← 柯里化,不是(chart)  ║
║ lots(e,jd,lat,lon)   ← 不是hermeticLots    ║
║ firdariaAt 必须传 targetJd                 ║
║ compositeLongitudes(e,jdA,jdB,bodies)      ║
║ dignities("sun",2)   ← sign是0-11索引      ║
║ almuten(84.13)       ← 裸经度不是body名     ║
║ outOfBounds(e,body,jd)← 不是(body,decl)    ║
╚═════════════════════════════════════════════╝
其余 200+ 函数用 dir(Caelus) 自探索: 底层天文(sunApparent/nutation/precessEcliptic),
尊贵原子(dignityScore/faceRuler/termRuler/signRuler), 组合器(matchAll/matchAny),
特殊点(meanNode→弧度/57.2958转度/meanLilith/trueLilith/vertexEastPoint),
lotFortune/lotSpirit/hermeticLots, 探测(houseCusp/angles/gmst/gast/normalizeHouseSystem)等。
【印度/吠陀】 (Python+JS)

╔══════════════════ 速览 ══════════════════╗
║ PyJHora 4.8.7 → Python 吠陀占星 (新, 替代JS) ║
║ NatalEngine → Rasi + 27宿 + Dasha + 文本 ║
║ Caelus     → 26种Yoga + 7分盘            ║
║ Caelus     → Ashtottari + Yogini 大运    ║
║ Caelus     → Kemadruma + Parivartana     ║
╚══════════════════════════════════════════╝
── NatalEngine (主力, 字段全) ──
NatalEngine.calculateVedic("1990-06-15", hour, tz, lat, lon)
→ system: "Vedic (Jyotish)"
→ ayanamsa: {value:23.7236, formatted:"23°43'24\"", system:"Lahiri (Chitrapaksha)"}
→ moonSign: {rashi:{name, westernName, symbol, ruler, element, quality, index, degreeInSign},
nakshatra:{number, name, lord, deity, symbol, pada, degreeInNakshatra, startDegree, endDegree},
summary:"Moon in Kumbha (Aquarius), Shatabhisha Nakshatra"}
→ positions: {sun,moon,mercury,venus,mars,jupiter,saturn,rahu,ketu,ascendant,midheaven}
每行星: {longitude, tropicalLongitude, degree, rashi:{name,westernName,symbol,ruler,element,quality,index,degreeInSign},
nakshatra:{number,name,lord,deity,symbol,pada,degreeInNakshatra,startDegree,endDegree}}
→ dasha: {birthLord, proportionElapsed, yearsRemaining,
current:{lord,startDate,endDate,years,isPartial},
dashas:[{lord,startDate,endDate,years,isPartial}, ...9段]}
→ houses: {1..12}  每宫: {rashi, degree}
初始化: var e=new Caelus.Engine(Caelus.embeddedData);
var jd=Caelus.isoToJd("1990-06-15T12:00:00+08:00");  // 本命JD: +08:00是示例, 实际换成用户真实时区偏移
var natalJd=jd;
var targetJd=Caelus.julianDay(2026,6,22,12,0,0); // 推运目标JD
var moonLon=e.longitude("moon",jd,{zodiac:"sidereal:lahiri"});  // 月亮恒星经度
var chart=e.chartAt(jd,lat,lon,{});             // ⚠️ angles 是热带坐标, 吠陀需 ascSidereal=(asc-ayanamsa+360)%360
var ascSign=Math.floor(chart.angles.asc/30);    // asc→给houseSign/houseLord
# 需恒星经度的: 用 engine.longitude(body, jd, {zodiac:"sidereal:lahiri"})
# 需tropical盘数据的: 用 chart.bodies.xxx
【大运 — 3 种体系 (7个)】
Vimshottari  Caelus.vimshottariDashas(moonLon, natalJd)   ← 不是(e,...)!  返回完整理论周期, 需balance_years截实际出生点
→ {start_lord, balance_years, dashas:[{level,lord,start,end,sub:[...]}]}
Caelus.vimshottariAt(e, natalJd, targetJd)
→ {moon_nakshatra, moon_pada, start_lord, maha?, antar?, pratyantar?}
Caelus.vimshottariActive(moonLon, natalJd, targetJd)
Ashtottari   Caelus.ashtottariDashas(moonLon, natalJd)
Caelus.ashtottariAt(e, natalJd, targetJd) → {moon_nakshatra, start_lord, maha?, antar?}
Caelus.ashtottariActive(moonLon, natalJd, targetJd)
Yogini       Caelus.yoginiDashas(moonLon, natalJd)
Caelus.yoginiAt(e, natalJd, targetJd) → {moon_nakshatra, start_yogini, maha?, antar?}
Caelus.yoginiActive(moonLon, natalJd, targetJd)
【Yoga 检测 — 4 类 (4个)】
Caelus.yogasAt(e,natalJd,lat,lon)    → [{yoga:"Budha-Aditya",planets:["sun","mercury"]},...]
Caelus.rajaYogasAt(e,natalJd,lat,lon) → {raja:[{lords:[...],via:"conjunction"}], yogakarakas:[...]}
Caelus.dhanaYogasAt(e,natalJd,lat,lon)→ [{lords:[...],via:"conjunction"},...]
Caelus.kemadrumaAt(e,natalJd,lat,lon) → {present:bool, planets_checked:[...]}
Caelus.associationType(planetA,signA,planetB,signB) → "conjunction"|"exchange"|"aspect"|null
Caelus.houseSign(ascSign,house) → 星座索引  (ascSign=floor(asc/30))
Caelus.houseFromAsc(ascSign,sign) → 宫号  星座在第几宫
【分盘 — 7 种 (1核心+整盘)】
Caelus.vargaAt(e, jd, n)   ← n∈{1,2,3,9,10,12,30}, 不是 "D9"!  body默认"moon", 节点用"mean_node"非"rahu"
→ {varga:n, rasi:"Aquarius", rasi_index:10, sign:"Pisces", sign_index:11, division:6}
Caelus.vargaChart(e, jd, n) → {"sun":{varga,rasi,division}, ...}  每星体一分盘
D1 Rasi        D2 Hora        D3 Drekkana   D9 Navamsa
D10 Dasamsa    D12 Dvadasamsa  D30 Trimsamsa
【27 宿 — (2个)】
Caelus.nakshatra(siderealLon)        → {index, name, pada, lord, pos}
Caelus.nakshatraAt(e, jd, body, zodiac) → 指定星体的宿度
【岁差 — (1个)】
Caelus.ayanamsa(jd, "lahiri")  → 23.72°
可选: "fagan_bradley" / "krishnamurti" / "raman" / "yukteshwar"
【恒星黄道经度 (必用)】
engine.longitude("moon", jd, {zodiac:"sidereal:lahiri"})
→ 任何函数需要 sidereal lon 时用这个取值
【尊贵 (吠陀也用)】
Caelus.dignities("sun", 2)    ← sign 是 0-11 索引
Caelus.dignityScore("sun", 84.13, "day") → {rulership,exaltation,triplicity,term,face,total}
Caelus.yogakarakas(ascSign) → 命主星列表  (⚠️ 热带和恒星结果不同; Caelus算法含H4/7/10+H5/9, 不含H1, 与BPHS有差异; 也可从rajaYogasAt结果取)
【Vedic 原子查询 (按需)】
Caelus.vimshottariDashas(moonLon, natalJd).start_lord → 出生大运主星
Caelus.ashtottariLord(nakIndex)   → Ashtottari 起始主星  (nakIndex=nakshatra(moonLon).index)
Caelus.parivartana(planetA,signA,planetB,signB) → true/false  互容检测
Caelus.aspectsSign(planet,planetSign,targetSign) → true/false  行星特殊相位(Mars→4/8,Jupiter→5/9,Saturn→3/10,全→7)
Caelus.startingYogini(nakIndex)   → Yogini 起始  (nakIndex=nakshatra(moonLon).index)
Caelus.isDayChart(e,jd,lat,lon)  → 昼夜盘
⚡ Astronomy（择时/食相专用）:
调它只有两种情况——
① 问日食月食精确到秒的时刻（吠陀 muhurta 择时需要）
② 问行星精确赤经/赤纬/出没时刻
其余不调。nakshatra 宽度 13°20'，弧秒级精度无意义。
调用: Astronomy.SearchLunarEclipse(jd) / SearchGlobalSolarEclipse(jd)
Astronomy.SearchRiseSet(Astronomy.Body.Sun, new Astronomy.Observer(lat, lon, 0), 1, jd, 1)
╔══════════════════ 参数坑 ══════════════════╗
║ vargaAt(e,jd,9)              ← 数字 9     ║
║ vimshottariDashas(moonLon,jd) ← 不是(e,..)║
║ nakshatra(siderealLon)       ← 恒星经度   ║
║ dignities("sun",2)           ← sign索引   ║
║ ayanamsa(jd,"lahiri")        ← 必须传mode ║
╚═════════════════════════════════════════════╝
其余用 dir(Caelus) 自探索: 常量(VIMSHOTTARI_ORDER/YOGA_PLANETS/DHANA_HOUSES/
KENDRAS/TRIKONAS/DRISHTI/NAKSHATRAS等), yogasAt/dhanaYogasAt 单项查询,
kemadrumaAt 带日期, varga 裸经度版, 各种 lord/active 原子函数。
【印度占星深度版 · NodeJhora — JPL DE440 星历, 纯 JS】

  引擎: eval_javascript(action='load', library='node-jhora-engine')
  已自包含 JPL DE440 星历 (1849–2150, 32MB), 加载即用, 零 init。
  ⚠️ 所有函数/类/常量挂在 NodeJhora 命名空间, 必须加 NodeJhora. 前缀。
  ✅ Intl API 已在引擎入口 polyfill, DateTime.fromISO() 在 QuickJS 下可用。
     凭据: jhora_entry_quickjs.mjs 顶部藏了轻量 Intl 垫片, 覆盖
     luxon 的 systemLocale() 调用 (~15行, 无ICU数据)。
     例: NodeJhora.EphemerisEngine.getInstance()
         NodeJhora.calculateShadbala({...})
         NodeJhora.Ashtakavarga.calculateSAV(planets)
     日期: NodeJhora.DateTime.fromISO("1990-06-15T12:00:00+08:00")
     位置: {latitude: 28.6, longitude: 77.2}
     经度全为恒星黄道 (sidereal Lahiri), 引擎默认 NodeJhora.AYANAMSA.LAHIRI=1
     行星 id: 0=Sun 1=Moon 2=Mercury 3=Venus 4=Mars 5=Jupiter 6=Saturn 10=Rahu 99=Ketu
     坐标: 全部为某星座 0-360° 恒星黄经, 用 Math.floor(lon/30) 取星座索引 0-11

  ⚠️ 日期必须带时区偏移, 否则按本地时间算 → 排盘全偏。
    正确: NodeJhora.DateTime.fromISO("1990-06-15T12:00:00+08:00")
    错误: NodeJhora.DateTime.fromISO("1990-06-15T12:00:00")
  ⚠️ 日出/日落: NodeJhora 自身不算。特殊Lagna和TimeUpagraha需要时:
     — 已加载 Caelus 时用它算日出/日落
     — 否则让用户提供: "请输入出生当天日出时刻 (HH:MM 格式)"
     — 示例: NodeJhora.DateTime.fromISO("1990-06-15T05:30:00+05:30")
⚠️ YogaEngine.findYogas 首次调用慢(遍历数百条规则)。一次 eval_javascript 里和前几个API一起调，不要单独开一次调用等它。
⚠️ generateVimshottari: 默认 depth=2 (Maha+Antar)。用户问"某月/某天运势"时用 depth=3 (Maha+Antar+Pratyantar)。depth=1 太粗没用。

╔═══════════════════ 调用骨架 ════════════════════╗
║ dt=NodeJhora.DateTime.fromISO("1990-06-15T12:00:00+08:00") ║
║ nj=NodeJhora.EphemerisEngine.getInstance()        ║
║ p=nj.getPlanets(dt,{lat,lon},{ayanamsaOrder:1}) ║
║ jd=nj.julday(dt)                                 ║
║ h=nj.getHouses(jd,lat,lon,"W",true)              ║
║ moonLon=p.find(x=>x.id===1).longitude            ║
║ ascSign=Math.floor(h.ascendant/30)               ║
╚══════════════════════════════════════════════════╝
── 跨库调用需 Caelus (Ashtottari/Kemadruma/Nakshatra等) ──
eval_javascript(library="caelus-engine", code="var ce=new Caelus.Engine(Caelus.embeddedData)")
var siderealLon = ce.longitude("moon", jd, {zodiac:"sidereal:lahiri"})
// ce 已可用: ce.nakshatra(siderealLon) / Caelus.ashtottariDashas(...) 等

  ━━━ 一、本命盘 (Rasi / D1) ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

  NodeJhora.EphemerisEngine.getInstance()
    .getPlanets(dt, {lat,lon}, {ayanamsaOrder:1, topocentric:false})
    → [{id,name,longitude,latitude,distance,speed,declination}×10]
    .getHouses(jd, lat, lon, "W", true)
    → {cusps:[12], ascendant, mc, armc, vertex}
    .julday(dt) → 儒略日
    .getAyanamsa(jd) → 岁差 (度)
    .setAyanamsa(NodeJhora.AYANAMSA.KRISHNAMURTI)  // 切换岁差体系
    .getSiderealTime(jd) → 恒星时(小时)
    .getEclipticObliquity(jd) → {eps, dpsi, deps}

  NodeJhora.calculateHouseCusps(dt, lat, lon, "WholeSign", e)
    → {cusps, ascendant, mc, armc, vertex}
  NodeJhora.calculateBhavaSandhi(cusps) → [12] 宫位交界点

便捷类 (内部调 EphemerisEngine, 一步拿全):
NodeJhora.NodeJHora.calculate(new Date("1990-06-15T12:00:00+08:00"),
  {latitude:lat, longitude:lon}, "Lahiri")
  → Promise<{planets, houses, ascendant, ayanamsa, panchanga}>
  ⚠️ 返回 Promise, 用 .then(r=>{...}); panchanga 结构见上
var j=new NodeJhora.NodeJHora({lat,lon}); j.getPlanets(dt); j.getHouses(dt)

━━━ 二、五支 / Panchanga (印历要素) ━━━━━━━━━━━━━━━━━━━━━━━━━━

NodeJhora.calculatePanchanga(sunLon, moonLon, dt, sunriseHour=6.0)
  → {tithi:{index, name, percent},
     nakshatra:{index, name, pada, percent},
     yoga:{index, name}, karana:{index, name}, vara:{index, name}}
  ⚠️ nakshatra 不含 lord/deity — 需自行查表匹配

━━━ 三、分盘 / Vargas (D1–D60) ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

NodeJhora.calculateVarga(lon, division)  // division: 1=D1..60=D60
  → {longitude, sign, degree, deity?}    // sign: 1-12 (Aries=1), degree: 0-30
// 无 D1~D60 别名。统一用 calculateVarga(lon, division):
//   D1=1 D4=4 D7=7 D9=9 D10=10 D12=12 D60=60
//   例: calculateVarga(moonLon, 9) = D9  calculateVarga(sunLon, 60) = D60
NodeJhora.calculateShashtyamsa(lon) → 同 D60 (便捷别名)
支持全部分盘: D1 D2 D3 D4 D7 D9 D10 D12 D16 D20 D24 D27 D30 D40 D45 D60

━━━ 四、大运 / Dasha (时间维度) ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

NodeJhora.generateVimshottari(dt, moonLon, depth=2)
  → [{planet, level, start, end, durationYears, subPeriods:[{...}]}]
  depth: 1=仅 Maha, 2=Maha+Antar, 3..5=更深
NodeJhora.calculateDashaBalance(moonLon)
  → {lord, yearsRemaining, totalDuration, fractionTraversed, nakshatraIndex}

NodeJhora.YoginiDasha.calculate(moonLon, dt, 50)   // 36年周期, 8 Yoginis
  → [{planet, level, start, end, durationYears, subPeriods}]

NodeJhora.NarayanaDasha.calculate(chart, dt, 80)   // Rasi 序列大运
  → [{signIndex, startYear, endYear, durationYears, isForward}]
  chart 需含 {planets:[{id,longitude}], houses:{ascendant}}

╔══════════════════ Dasha 对比 ═══════════════════╗
║ Vimshottari: 120年, 9段, 最主流                  ║
║ Yogini:      36年,  8段, 快速审视                ║
║ Narayana:    Rasi进阶, Jaimini体系大运             ║
╚══════════════════════════════════════════════════╝

  ━━━ 五、力量体系 · Shadbala (六力) ━━━━━━━━━━━━━━━━━━━━━━━━━━

NodeJhora.calculateShadbala({
  planet:         {id, longitude},        // 单星体
  allPlanets:     [{id, longitude}, ...], // 全七曜+节点
  houses:         {ascendant, mc, cusps},
  sun:            {id:0, longitude},
  moon:           {id:1, longitude},
  timeDetails:    {birthHour, sunrise, sunset},
  vargaPositions: [{vargaName:"D9", sign, lordId, lordRashiSign}]
})
→ {total, sthana, dig, kaala, chesta, naisargika, drig,
   ishtaPhala, kashtaPhala,           // 吉凶分数
   breakdown:{uchcha,saptavargaja,kendra,ojayugma,dig,natonata,paksha,tribhaga,ayana,chesta,naisargika,drig}}
各行星比 total → 力量排行

单算组件:
NodeJhora.calculateUchchaBala(planetId, lon)       → 庙旺力量
NodeJhora.calculateKendraBala(houseNum)            → 四正宫力量
NodeJhora.calculateOjayugmarasyamsaBala(planetId, rashiSign, navamsaSign)
NodeJhora.calculateSaptavargajaBala(planetId, planetRashiSign, vargaPositions)
NodeJhora.calculateDrigBala(targetPlanet, allPlanets) → 相位力量总和
时间六力 (shadbala_time):
NodeJhora.calculateDigBala(planet, ascendant, mc)  → 方向力量
NodeJhora.calculateNatonataBala(planetId, sunLon, mcLon)
NodeJhora.calculatePakshaBala(planetId, sunLon, moonLon)
NodeJhora.calculateTribhagaBala(planetId, birthHour, sunrise, sunset)
NodeJhora.calculateAyanabala(planetId, declination)
NodeJhora.calculateChestaBala(planet)              → 视运动力量

━━━ 六、力量体系 · Ashtakavarga (八分力) ━━━━━━━━━━━━━━━━━━━━━

NodeJhora.Ashtakavarga.calculateBAV(planets, targetId)
  → [12] 单星 Bhinnashtakavarga, targetId: 0=Sun..6=Saturn
NodeJhora.Ashtakavarga.calculateSAV(planets)
  → {bav:{0:[12],1:[12],...}, sav:[12]}  BAV+SAV一起返回
SAV 每宫总分越高 → 该宫越有力; BAV 看单星在12宫的分布

╔══════════════ 力量体系对比 ══════════════════╗
║ Shadbala:    行星本身有多强 (6维度+吉凶分数)  ║
║ Ashtakavarga: 行星在12宫各有多强+宫位总分     ║
║ 先用 Shadbala 排行, 再用 Ashtakavarga 看分布  ║
╚══════════════════════════════════════════════╝

  ━━━ 七、Jaimini 系统 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

  NodeJhora.JaiminiCore.calculateCharaKarakas(planets)
    → [{id,name,longitude}×7]
    排序依据 p.longitude%30 (星座内度数)。7个传统星体(Sun-Saturn):
    Atmakaraka(灵魂之星)→Amatyakaraka→Bhratrukaraka→Matrukaraka→Pitrukaraka→Putrakaraka→Gnatikaraka
  NodeJhora.JaiminiCore.getRashiDrishti(signIndex)
    → [星座索引...]  Rashi 星座相位 (固定→本位, 变动除邻宫全投)
    固定座(2,5,8,11)投变动座; 变动座(3,6,9,12)投固定座
    本位座(1,4,7,10)投固定座外全部
  NodeJhora.JaiminiCore.calculateArudha(houseNum, houseSignIndex, lordSignIndex)
    → {arudhaSignIndex, arudhaHouse}
    houseNum: 1-12
NodeJhora.JaiminiDashas.calculateCharaDasha(ascSignIndex, planets)
  → [{signIndex, startYear, endYear, durationYears}]
  ascSignIndex: 上升星座索引 0-11
NodeJhora.JaiminiDashas.calculateSignDuration(signIndex, planets) → 年数
NodeJhora.JaiminiDashas.getSignRulerId(signIndex) → 主宰星id

  ━━━ 八、KP 克利希那穆提 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

  NodeJhora.KPSubLord.calculateKPSignificators(lon)
    → {starLord, subLord, subSubLord, cuspStar, cuspSub}
    传入某点恒星经度, 返回该点的星宿/亚主星/次亚主星

  NodeJhora.KPEngine.getAllPlanetSignificators(planets)
    → [{planetName, significators:{starLord, subLord, subSubLord}}]
    全盘9星每颗的KP主星

  NodeJhora.KPEngine.getAllHouseSignificators(houses)
    → [{houseIndex, significators:{...}}]
    12宫每宫起始点的KP主星

  NodeJhora.KPRuling.calculateRulingPlanets(ascLon, moonLon, dayLordId)
    → {lagnaSignLord, lagnaStarLord, moonSignLord, moonStarLord, dayLord}
    dayLordId = Math.floor(jd) % 7  // 0=Sun..6=Sat

  ━━━ 九、Yoga 格局检测 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

  NodeJhora.YogaEngine.findYogas(chart, NodeJhora.YOGA_LIBRARY)
    → [{yoga, triggeringPlanets:[...]}]
    chart: {planets:[{name:"Sun",longitude}], houses:{ascendant}}
    从 YOGA_LIBRARY (内置数百条Yoga规则) 中匹配命盘

  ━━━ 十、行运 / Transit ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

  var t=new NodeJhora.TransitEngine(NodeJhora.EphemerisEngine.getInstance())
  t.findTransits(planetId, startDt, endDt, stepHours=24)
    → [{planetId, type:"Sign"/"Nakshatra", prevValue, newValue, time}]
    扫指定行星在时间段内的换座/换宿事件
t.findExactAspect(p1Id, p2Id, angle, startDt, endDt, 0.01)
  → DateTime | null  精确入相位时刻 (单个值, 不是数组)
  angle: 0/60/90/120/180
⚠️ 两个都是 async — 用 .then(r=>{...})

━━━ 十一、特殊 Lagna (8种) ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

⚠️ 全部返回裸恒星黄经(number), 需自行 Math.floor(lon/30) 取星座索引
NodeJhora.calculatePranapada(dt, sunriseDt, sunLon) → number  气息上升点
NodeJhora.calculateInduLagna(ascSign, moonSign, planets) → number  ascSign/moonSign:1-12
NodeJhora.calculateShreeLagna(dt, sunriseDt, moonLon) → number
NodeJhora.calculateHoraLagna(dt, sunriseDt, ascLon) → number
NodeJhora.calculateGhatiLagna(dt, sunriseDt, sunLon) → number
NodeJhora.calculateBhavaLagna(dt, sunriseDt, sunLon) → number
NodeJhora.calculateVarnadaLagna(ascLon, horaLagnaLon, ascSign, horaLagnaSign) → number
均需 sunriseDt: Luxon DateTime, 出生当天日出时刻

━━━ 十二、Upagraha (虚星/影星) ━━━━━━━━━━━━━━━━━━━━━━━━━━━━

NodeJhora.calculateTimeUpagrahas(dt, sunriseDt, sunsetDt, sunLon, moonLon, isDay)
  → {kaala, paridhi, mrityu, ardhaprahara, yamakantaka, kodanda, mandi, gulika}
  8个时间虚星, 每个值=恒星经度; isDay: Caelus.isDayChart() 或 (sunLon>ascLon)
NodeJhora.calculateDhumadiUpagrahas(sunLon)
  → {dhuma, vyatipata, parivesha, indrachapa, upaketu}
  5个日度虚星 (链式推导: dhuma→vyatipata→parivesha→indrachapa→upaketu)
  每个值=恒星经度

  ━━━ 十三、行星关系 / Drishti ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

  NodeJhora.getRelationship(planetAId, lonA, planetBId, lonB)
    → {natural:"Friend"/"Neutral"/"Enemy",
       temporary:"Friend"/"Neutral"/"Enemy",
       compound:"GreatFriend"/"Friend"/"Neutral"/"Enemy"/"GreatEnemy"}
    综合自然关系 + 临时关系 = 复合关系
  NodeJhora.getTatkalikaMaitri(lonA, lonB)  → 临时关系 (基于当前星座位置)

  NodeJhora.calculateDrishtiValue(angle, aspectingPlanetId)
    → 该角度上某星的相位强度 (0-1)
    全相位: 所有星投7宫; Mars→4/8, Jupiter→5/9, Saturn→3/10
  NodeJhora.calculateDrigBala(targetPlanet, allPlanets)
    → 所有星对该星的相位力量总和

━━━ 常量 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

NodeJhora.AYANAMSA: {LAHIRI:1, DELUCE:2, RAMAN:3, KRISHNAMURTI:5,
           YUKTESHWAR:7, JN_BHASIN:8, TRUE_CITRA:27, TRUE_PUSHYA:29}
NodeJhora.D / NodeJhora.toNum / NodeJhora.normalize360D  (Decimal精确数学)
NodeJhora.NAKSHATRA_SPAN_D: 13.3333...° (Decimal) / NAKSHATRA_SPAN_N: 13.3333 (number)
NodeJhora.DASHA_YEAR_DAYS: 365.242189623 (tropical year, Decimal)
NodeJhora.YOGA_LIBRARY: 传给 YogaEngine.findYogas()
NodeJhora.PLANET_IDS: [0,1,4,2,5,3,6]  七曜
NodeJhora.Relationship: {GreatFriend,Friend,Neutral,Enemy,GreatEnemy}
NodeJhora.DASHA_DURATIONS: {Ketu:7,Venus:20,Sun:6,...}
NodeJhora.DASHA_ORDER: ["Ketu",...]
── 数学工具 (core/math) ──
NodeJhora.normalize360(angle) → [0,360)
NodeJhora.getShortestDistance(a,b) → [0,180]
NodeJhora.dmsToDecimal(d,m,s) → 十进制度
NodeJhora.decimalToDms(deg) → {d,m,s}
NodeJhora.midpoint(a,b) → 中点 (走短弧)

  ⚠️ 宫位制: whole-sign 默认; 可选 Porphyry。Placidus 此处不可用。
     NodeJhora.calculateHouseCusps(dt,lat,lon,"WholeSign",e) 或 "Porphyry"

⚠️ 自探索: load 后用以下 JS 看未列出部分 —
  Object.keys(NodeJhora)  // 全部导出: EphemerisEngine, NodeJHora, calculateShadbala,
                          //   Ashtakavarga, YogaEngine, KPEngine, JaiminiCore,
                          //   JaiminiDashas, KPRuling, KPSubLord, TransitEngine,
                          //   YoginiDasha, NarayanaDasha, generateVimshottari, ...
  Object.getOwnPropertyNames(NodeJhora.EphemerisEngine.prototype)  // 引擎方法
  Object.keys(NodeJhora.Ashtakavarga)  // 子模块: calculateBAV, calculateSAV, calculate

  ━━━ 常用速算 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

  ascSign=Math.floor(h.ascendant/30)         // 上升星座索引 0-11
  moonLon=p.find(x=>x.id===1).longitude      // 月亮恒星经度
  sunLon =p.find(x=>x.id===0).longitude      // 太阳恒星经度
  nakIndex=Math.floor(moonLon/13.3333)       // 月亮宿度索引 0-26
  houseSign=(planetSign-ascSign+12)%12       // 星体在第几宫 (0=1宫)
  sunriseDt=NodeJhora.DateTime.fromISO("1990-06-15T05:30:00+05:30")
  dayLordId=Math.floor(jd)%7                 // 当日主宰星 0=Sun..6=Sat

它能做什么 — 对照印度占星完整体系:
  星历:      JPL DE440 (NASA, 1849–2150), 精度同级 Swiss Ephemeris
  九曜:      7曜+Rahu/Ketu, 恒星黄经+赤纬+速度+距离 (getPlanets)
  二十七宿:   Panchanga 内嵌完整27宿(名/主宰星/神祇), 每月站13°20′
  大运:       Vimshottari(120年/9段, 最主流) + Yogini(36年/8段) + Narayana(Rasi进阶)
             ⚠️ Ashtottari(108年) NodeJhora不支持 — 用 Caelus.ashtottariDashas()
  力量:       Shadbala(六力量化) + Ashtakavarga(八分力/BAV+SAV宫位评分)
  Jaimini:    CharaKaraka(7灵魂星/Atmakaraka为首) + CharaDasha + Arudha + RashiDrishti
  KP:         SubLord亚主星 + 全盘主星分析 + Ruling Planets(择时)
  Yoga:       内置数百条规则, YOGA_LIBRARY → YogaEngine.findYogas
  分盘:       16种全(D1-D60), calculateVarga/calculateD9/calculateD10/calculateD60
  Panchanga:  Tithi+Nakshatra+Yoga+Karana+Vaara — 五支印历
  行运:       TransitEngine — 换座/换宿扫描 + 精确入相位时刻
  特殊Lagna:  8种(Pranapada/Indu/Shree/Hora/Ghati/Bhava/Varnada)
  虚星:       5个Upagraha(Dhooma→Vyatipata→Parivesha→Indrachapa→Upaketu)
  Drishti:    行星特殊相位强度 + DrigBala相位力量总和
  行星关系:   自然关系+临时关系→复合关系(GreatFriend..GreatEnemy)
  Caelus/NatalEngine 未覆盖的深度吠陀分析全在这。

什么时候调 — 用户这样问时:
  「排个印度盘/吠陀盘」→ 骨架: p+h → moonLon/sunLon/ascSign → Panchanga + Vimshottari
  「哪个星最强/最弱」→ NodeJhora.calculateShadbala → 按 total 排行
  「八分力/Ashtakavarga/宫位力量」→ NodeJhora.Ashtakavarga.calculateSAV
  「Atmakaraka/灵魂之星/Jaimini」→ NodeJhora.JaiminiCore.calculateCharaKarakas
  「CharaDasha/Jaimini大运」→ NodeJhora.JaiminiDashas.calculateCharaDasha
  「Arudha/投射盘」→ NodeJhora.JaiminiCore.calculateArudha
  「KP/SubLord/亚主星」→ NodeJhora.KPSubLord.calculateKPSignificators
  「KP主宰星/择时」→ NodeJhora.KPRuling.calculateRulingPlanets
  「Ashtottari大运/108年」→ Caelus.ashtottariDashas(moonLon, natalJd)  ← NodeJhora没有!
  「Kemadruma/月亮孤独格」→ Caelus.kemadrumaAt(e, natalJd, lat, lon)  ← NodeJhora没有!
  「Yogini大运/36年」→ NodeJhora.YoginiDasha.calculate  (Caelus.yoginiDashas 也可)
  「Narayana大运/Rasi大运」→ NodeJhora.NarayanaDasha.calculate
  「Yoga/格局/富贵贫贱」→ NodeJhora.YogaEngine.findYogas
  「某星何时换座/换宿」→ NodeJhora.TransitEngine.findTransits
  「某星何时入相位」→ NodeJhora.TransitEngine.findExactAspect
  「Pranapada/InduLagna/特殊上升」→ NodeJhora.calculatePranapada 等8种
  「虚星/Dhooma/Vyatipata」→ NodeJhora.calculateTimeUpagrahas
  「行星关系/敌友」→ NodeJhora.getRelationship
  「互容/Parivartana」→ Caelus.parivartana(planetA,signA,planetB,signB)  ← NodeJhora没有!
  「Yogakaraka/命主星」→ Caelus.yogakarakas(ascSign)  ← NodeJhora没有!
  「行星关联类型(合相/互容/相位)」→ Caelus.associationType(planetA,signA,planetB,signB)  ← NodeJhora没有!
  「Drishti/相位强度」→ NodeJhora.calculateDrishtiValue / NodeJhora.calculateDrigBala
  「Drishti/某星是否投相位到某宫」→ Caelus.aspectsSign(planet,planetSign,targetSign)
  「宫位/星座映射(houselord等)」→ Caelus.houseLord(ascSign,n) / houseSign / houseFromAsc / signLord
  「印历/今天什么日子/Panchanga」→ NodeJhora.calculatePanchanga
  「Nakshatra/星宿宿度(含主宰星)」→ Caelus.nakshatra(siderealLon) ← 含lord字段!
  「Nakshatra/任意星体的宿度」→ Caelus.nakshatraAt(e, jd, body, zodiac) ← NodeJhora的Panchanga只针对月亮!
  「星宿宿度(仅索引)」→ NodeJhora Panchanga.nakshatra 或 Math.floor(moonLon/13.3333)
  「Vimshottari某时刻激活的大运」→ Caelus.vimshottariAt(e, natalJd, targetJd) ← 带moon_nakshatra!
  「吠陀本命解读文本」→ NatalEngine.calculateVedic("1990-06-15",hour,tz,lat,lon) ← 含dasha/summary!
  「Ashtottari大运/108年周期」→ Caelus.ashtottariDashas(moonLon, natalJd)


【人类图/Human Design】

人类图  →  NatalEngine.calculateHumanDesign("1990-06-15", hour, tz_offset)
→ {
type: {name:"Projector", strategy:"Wait for the Invitation",
notSelf:"Bitterness", signature:"Success",
description:"Guides and managers who see others deeply",
percentage:"20%"},
authority: {name:"Self-Projected Authority",
description:"Hear truth in your own voice"},
profile: {numbers:"2/4", name:"Hermit/Opportunist",
theme:"Natural talent shared with others"},
definition: "Single Definition" | "Split Definition" | ...,
incarnationCross: {angle:"right", angleName:"Right Angle",
name:"Eden", fullName:"Right Angle Cross of Eden (12/11 | 36/6)",
gates:[12,11,36,6], gateNames:["Caution","Ideas","Crisis","Friction"]},
centers: {defined:[{name,theme,biological,definedMeaning,...}],

undefined:[{name,status:"undefined",activatedGates:[...]}],
open:[{name,status:"open",activatedGates:[]}]},
channels: [{gates:[13,33], name:"The Prodigal", centers:["g","throat"],
theme:"A witness", circuit:"collective", subcircuit:"sensing"}],
gates: {personality:{sun,earth,moon,northNode,southNode},
design:{sun,earth,moon,northNode,southNode}},
circuitAnalysis: {individual:{channels,names}, tribal:{...},
collective:{...}, integration:{...},
dominant:{name,theme,keywords,channelCount}},
summary: "Projector with Self-Projected Authority, 2/4 Profile",
note: "Calculated with astronomy-engine (VSOP87)"
}
生日必填（无需经纬度）
基因钥匙 →  NatalEngine.calculateGeneKeys(humanDesignResult)  ← 参数是HD结果,不是日期!
→ {
activationSequence: {
lifeWork:  {key:"12.2", gift:"Discrimination", siddhi:"Purity", shadow:"Vanity"},
evolution: {key:"11.2", gift:"Idealism",     siddhi:"Light"},
radiance:  {key:"36.4", gift:"Humanity",     siddhi:"Compassion"},
purpose:   {key:"6.4",  gift:"Diplomacy",    siddhi:"Peace"}
},
venusSequence: {attraction:{key:"43.6"}, iq:{key:"2.6"}, eq:{key:"21.2"}, sq:{key:"19.3"}},
pearlSequence: {vocation:{key:"41.2"}, culture:{key:"15.4"}, pearl:{key:"53.1"}},
pathways: {challenge:"12→11", breakthrough:"11→36", coreStability:"36→6"},
primeGifts: ["Discrimination","Idealism","Humanity","Diplomacy"],

summary: "Life's Work: 12.2 (Discrimination), Evolution: 11.2 (Idealism)..."
}
HD行运   →  NatalEngine.calculateTransitGates() → {date, gates, activeGates, activeGateCount}
(当前时刻的行运闸门)
  【塔罗/雷诺曼/其他】

                       【统一规则】
                         1.先结论，后解释
                         2.永远故事优先，不解释数据
                         3.所有牌必须串联，不可孤立解释
                         4.数据只用于"增强语气"，不能罗列
                         5.塔罗和雷诺曼各自有独立的输出模板，禁止混用。抽到雷诺曼牌时必须使用雷诺曼输出格式，不得带入塔罗的字段。

                       ╔══════════════════ 塔罗 ══════════════════╗
 塔罗/韦特           →  arcanite(Python,78张+牌阵+正逆位), 规则见下
                        【抽牌即含9层数据, 勿只给简单解读, 按用户场景取对应层】
                        1.core_meanings      正位(upright)+逆位(reversed)核心含义(各6组关键词+详细解读, 调时传 reversed=bool 匹配正逆位)
                        2.position_interpretations 7种牌位(调时传 rag_mapping="类别.子路径"+reversed=bool): 类别=temporal_positions(时间) / challenge_and_growth(挑战) / guidance_and_action(行动) / emotional_and_internal(情感) / external_influences(外部) / outcome_and_result(结果) / relationships(关系); 子路径如 .past/.present/.future/.advice/.challenge/.outcome等。示例: rag_mapping="temporal_positions.past"
                        3.question_contexts  5种场景(调时传 question_type+reversed=bool): love(爱情) | career(事业) | spiritual(灵性) | financial(财务) | health(健康) — 每个含3种解读(关键词/详细/建议)
                        4.elemental_correspondences 10项: element元素 | zodiac星座 | hebrew_letter希伯来字母 | numerology灵数 | planet行星 | season季节 | time_of_day时辰 | colors颜色 | crystals水晶 | herbs草药
                        5.symbols            牌面符号逐个解读(每牌5-8个符号)
                        6.affirmations       4条肯定语
                        7.journaling_prompts 4条日记提示
                        8.meditation_focus   冥想指引
                        9.card_relationships 6种牌间关系: amplifies(增幅) | challenges(挑战) | clarifies(澄清) | similar_energy(同类) | opposite_energy(对立) | learning_sequence(学习序列)
                        搭配: 深度→查777表→Kaabalah.buildKaabalisticMapData()(JS,全映射:源质+字母+路径+行星)

 arcanite            →  塔罗: from arcanite.core import TarotDeck; d=TarotDeck.load(system="tarot"); cards=d.draw(N); [print(c.card_id,c.card_name,c.orientation.value) for c in cards]
                       ⚠️ print仅取数据。解读正文必须写在回复里，不准在Python里print解读
                       深度: DrawnCard已代理全部TarotCard方法: cards[i].get_core_meaning(reversed=False) / get_interpretation(rag_mapping, reversed=False) / get_question_context(question_type, reversed=False) / get_elemental_correspondences() / get_symbols()→遍历.items()(返回dict非list) / get_affirmations() / get_journaling_prompts() / get_relationships() / .raw_data (含meditation_focus等全部原始字段) / .card_number / .suit

                       ┌─ 互补模式（arcanite + TarotKit 强强联合）──────────────┐
                       │ 标准步骤,根据数据需要取对应引擎的字段:                │
                       │                                                      │
                       │ STEP 0: 导入                                         │
                       │   from arcanite.core import TarotDeck                │
                       │   from arcanite.core.spread import load_spread       │
                       │   from tarot_elemental_engine import ElementalDignityEngine as EE  │
                       │                                                      │
                       │ STEP 1: arcanite 抽牌 + 加载牌阵                      │
                       │   deck = TarotDeck.load(system="tarot")              │
                       │   drawn = deck.draw(N, seed=42)   # seed可换任意int, 同seed复现  │
                       │   spread = load_spread("牌阵ID")                      │
                       │   # 牌阵位置→rag_mapping 按语义匹配:                   │
                       │   #   "Past" → temporal_positions.past               │
                       │   #   "Present" → temporal_positions.present         │
                       │   #   "Future" → temporal_positions.future           │
                       │   #   "Challenge" → challenge_and_growth.challenge   │
                       │   #   "Advice/Your Approach" → guidance_and_action   │
                       │   #   "Outcome" → outcome_and_result                 │
                       │   #   "External" → external_influences               │
                       │   #   "Hopes and Fears" → emotional_and_internal     │
                       │   #   "Relationship" → relationships                 │
                       │   #   无匹配 → 只用 get_core_meaning                  │
                       │                                                      │
                       │ STEP 2: EE 全量分析（不分级，一次出全）                │
                       │   ee = EE.full_analysis(drawn)                       │
                       │   → spread_dignity / statistics / composition        │
                       │     (major_arcana_ratio/court_card_ratio             │
                       │      /repeated_numbers/repeated_suits)               │
                       │   → numerolog / absence / doubling / reversal        │
                       │                                                      │
                       │ STEP 3: arcanite 逐牌全字段                           │
                       │   for i, dc in enumerate(drawn):                     │
                       │     rag = 按位置语义匹配rag_mapping                    │
                       │     dc.card_number / dc.suit                        │
                       │     cm = dc.get_core_meaning(reversed=...)           │
                       │     # cm 键: essence / keywords / waite_meaning      │
                       │     #    psychological / spiritual / practical / shadow      │
                       │     #    tk_meaning_en/zh / tk_coreKeyword_en/zh     │
                       │     # ⚠️ tk_coreKeyword_* 只有正位有                 │
                       │     dc.get_interpretation(rag, reversed=...)         │
                       │     dc.get_question_context(type, ...)               │
                       │     dc.get_elemental_correspondences()               │
                       │     dc.get_symbols() → for k,v in .items()          │
                       │     dc.get_affirmations()                            │
                       │     dc.get_journaling_prompts()                      │
                       │     dc.get_relationships()                           │
                       │     dc.archetype                                     │
                       │     dc.raw_data["meditation_focus"]                 │
                       │                                                      │
                       │ STEP 4: 统一引擎新增字段(调用示例)              │
                       │     dc.description["waite"]  # 韦特原版画面描述(英文)   │
                       │     dc.description["tk_en"]  # 现代画面描述(英文)       │
                       │     dc.description["tk_zh"]  # 现代画面描述(中文)       │
                       │     dc.get_waite_meaning("upright")  # 韦特正位意义    │
                       │     dc.get_waite_meaning("reversed") # 韦特逆位意义    │
                       │     dc.get_tk_meaning("upright","en")  # 现代正位英文  │
                       │     dc.get_tk_meaning("upright","zh")  # 现代正位中文  │
                       │     dc.get_tk_meaning("reversed","en") # 现代逆位英文  │
                       │     dc.get_tk_meaning("reversed","zh") # 现代逆位中文  │
                       │     dc.get_core_meaning(False)["tk_core_keyword_en"] # 核心词英│
                       │     dc.get_core_meaning(False)["tk_core_keyword_zh"] # 核心词中│
                       │     dc.reading_aspects  # 5层阅读                     │
                       │     dc.contextual_meanings  # 4语境                    │
                       │                                                      │
                       │ STEP 6: Kaabalah 卡巴拉映射（秘传时调用）              │
                       │   全量: buildKaabalisticMapData({numerology: ee.numerology})│
                       │   查表: SPHERES/HEBREW_LETTERS/LURIANIC_PATHS        │
                       └──────────────────────────────────────────────────────┘

                       【塔罗输出】塔罗=生命故事生成器
                         融合权威来源:
                           Eden Gray → Fool's Journey (大牌22站旅程)
                           Rachel Pollack → 每牌=故事角色, 大牌=灵魂archetype
                           Joan Bunning → 能量状态模型(逆位基础), 牌间互动阅读(位置与牌组关系)
                           Mary K. Greer → 花色叙事流(小牌逐卡讲故事), 逆位系统全盘研究
                           Multi-Layer Reading Framework → 观察→多层面解读→整合叙事
                         重要: 以下7层是框架不是铁律。
                           Rachel Pollack《Seventy-Eight Degrees of Wisdom》:在掌握牌义基础后,允许牌与牌之间形成自然叙事——牌阵是活的结构,不是死记硬背关键词
                           AI 先感受牌阵整体，再用7层结构组织语言
                           不是每层都必须填——有的阅读一张牌就说明了一切
                           叙事比结构更重要——宁缺一节，不凑一段

                         ── 1. 总体基调──
                         【问题】用户原问
                         【牌阵】名称 + 位置含义列表
                         【画面定调】第一张牌的 Waite desc → 定下整局阅读的色调和氛围
                         【总体印象】牌阵的第一眼直觉
                           大牌 vs 小牌比例(大牌与小牌并非重要程度之分,而是不同层级的信息):
                             大牌(原型/课题/转折/成长/命题/人生章节)——回答为什么发生？真正要学习什么？深层变化在哪里？正在经历什么阶段？
                               特点:长期影响/结构性变化/心理成长/命运转折/身份转变——大牌描述「故事本身」而非故事细节
                             小牌(事件/关系/行动/情绪/选择/过程)——回答如何发生？具体发生什么？谁参与其中？现实如何推进？
                               特点:现实层面/短中期发展/可调整空间大/与具体行动直接相关——小牌描述「故事如何展开」
                             协同原则:大牌说明「为什么」,小牌说明「如何」;大牌=核心课题,小牌=现实表现;大牌=深层变化,小牌=具体过程
                               禁止只看大牌忽略小牌,也禁止只看小牌忽略大牌
                               大牌不能自动压制小牌,小牌不能推翻大牌主题——大牌定方向,小牌定过程
                             比例判断(按大牌张数):
                               大牌0张=日常事务主导,可塑性较高 | 大牌1张=存在核心课题,该位置需重点关注
                               大牌2-3张=出现明显人生议题,当前事件具有成长意义,深层能量开始介入
                               大牌4张以上=人生转折期,重大课题正在展开,优先解读大牌之间的关系
                               大牌占比超过50%=深层变化大于现实操作,优先解释成长/转变/课题/人生方向
                               小牌占比超过75%=现实操作大于命运课题,优先解释行动/关系/决策/执行方案
                             强制规则:大牌出现时必须回答核心课题是什么/正在经历什么成长阶段/深层变化在哪里
                               小牌出现时必须回答现实如何运作/具体影响来自哪里/可以采取什么行动
                             最终公式:大牌=Why(为什么)/人生章节/核心命题 | 小牌=How(如何)/剧情细节/现实展开
                           大牌出现时标注Fool's Journey阶段
                           元素分布(EE statistics) → 火土风水谁为主宰
                           正逆位信号 → 能量流畅 or 有阻塞（见逆位心理学）
                           重复数字/花色 → 核心主题在强调什么
                         【一句话答案】从卡片中提炼的核心里程碑
                         话术示例(总体基调·第一印象——在进入详细解读前快速识别最突出的能量模式,不是结论而是对整体结构的第一印象):
                           优先观察:①大牌比例(是否明显偏多/存在主导性大牌) ②元素分布(火水土风谁最突出/谁明显缺失)
                             ③宫廷牌密度(多张暗示人物关系议题) ④重复数字(是否形成数字主题)
                             ⑤中心牌(是否存在统领全局的核心能量) ⑥牌间关系(共振/张力/补完/桥接/修正)
                             ⑦正逆位分布(整体能量是流动/受阻/内化/还是正在转化)
                           输出要求:用1-3句话说明当前最突出的主题/当前最强能量/整体氛围与发展方向
                             禁止逐张解释牌义/提前预测结果/提前进入建议
                           参考表达(非固定):
                             "最先吸引注意的是【XX】。"
                             "有一个主题正在反复出现。"
                             "多张牌共同指向【XX】。"
                             "整副牌似乎围绕【XX】展开。"
                             "最值得关注的并非某张牌,而是【XX】与【XX】之间的关系。"
                             "当前最强能量来自【XX】。"
                             "整体氛围更偏向【行动/情感/思考/现实】层面。"
                             "这副牌给人的第一感觉是【XX】。"
                           最终目标:先指出模式再进入细节——让读者先看见森林,再看见树木

                         ── 2. 多角度解读（Multi-Layer Reading Framework — Pollack/Greer/现代叙事塔罗）──
                         【解读角度】按以下流程执行:

                           First Pass — 直觉扫描（不看手册，先感受）:
                             看牌阵整体氛围：颜色/情绪/第一直觉
                             用 Waite desc 感受画面冲击，TarotKit description 互补
                             记下第一句浮现在脑海的话——那是潜意识在说话

                           Second Pass — 分析穿透（4层深度，从浅到深）:
                             ① 外部事件层（实际会发生什么）:
                                用 position_interpretations + contextualMeanings.work/love
                                回答"What will happen?"
                             ② 心理内省层（内心在经历什么）:
                                用 core_meanings.psychological + readingAspects.innerState
                                回答"What is my subconscious telling me?"
                             ③ 灵性成长层（灵魂在学什么课）:
                                用 Fool's Journey 阶段（大牌时）+ archetype + meditation_focus
                                回答"What is my soul's lesson here?"
                             ④ 行动决策层（下一步该怎么做）:
                                用 readingAspects.advice + guidance_and_action
                                回答"What should I do?"

                           Third Pass — 综合叙事（把所有碎片串成故事）:
                             进入【故事线】输出
                             每个解读角度之间用"但更重要的是……"自然过渡
                             话术示例(多角度解读·整合叙事——不同角度不是多个答案,而是同一议题的不同侧面):
                               整合层级:①事件层(现实在发生什么/如何推进) ②心理层(内心感受/真正驱动行为的因素)
                                 ③关系层(人与人如何互动/环境如何影响) ④成长层(当前课题/正在教会什么)
                                 ⑤行动层(下一步如何回应/哪些方向更符合牌阵能量)
                               整合要求:必须回答表面发生了什么/深层原因是什么/二者如何互相影响/当前核心课题/最适合的回应方式
                               叙事要求:形成现象→原因→影响→课题→回应的完整链条,禁止各层独立堆叠
                               参考表达(非固定):
                                 "表面上看这是关于【事件】,更深层则涉及【课题】。"
                                 "当前发生的事情正在推动你面对【XX】。"
                                 "你的行动受到【XX】影响,而这一影响又来自【XX】。"
                                 "外部环境呈现【XX】,内心则正在经历【XX】。"
                                 "理智希望【XX】,情感却倾向于【XX】。"
                                 "现实问题与成长课题其实指向同一个核心主题。"
                                 "当我们把所有层面放在一起看,牌阵真正讨论的是【XX】。"
                                 "这不仅是一段经历,也是一种学习过程。"
                               最终目标:将事件/心理/关系/成长/行动层整合为一个核心主题/一条完整故事线/一个清晰的发展方向
                                 避免多层信息并列堆叠,让整副牌最终只讲述一个完整故事

                         ── 3. 故事线（英雄之旅叙事 + 原型阅读法）──
                         【故事线】连续叙事，把牌阵当作一部电影
                           Rachel Pollack 核心理念: 每张牌是故事里的"角色"
                           大牌=原型级角色（灵魂的 archetype 在舞台上演出）
                           小牌=日常角色（你生活中具体的人在扮演什么）
                           宫廷牌优先代表「人」,其次为人格面具/行为模式/原型能量/事件信号(详见参考字典三)

                           开篇（画面入境）:
                             优先用 Waite desc 展开画面，辅以 TarotKit description 互补
                             第一张牌的 colors/tone 定下整个阅读的情绪基调
                             话术示例(故事线·开篇——建立画面感,引出核心主题,先建立场景/氛围/人物状态/核心情绪,非解释全部牌义):
                               优先观察:①牌面主体(人物/动物/象征物/主要动作) ②视觉焦点(最醒目的元素/最容易吸引注意的位置)
                                 ③情绪氛围(期待/压抑/迷茫/坚定/紧张/平静/喜悦/悲伤) ④当前位置(背景/现状/挑战/建议/结果)
                                 ⑤与问题的连接(牌面如何映射提问者当前处境)
                               输出要求:先描述画面感→再连接现实→最后引出主题。禁止直接背诵牌义
                               参考表达(非固定):
                                 "这张牌首先让我注意到的是【XX】。"
                                 "当我看到这张牌时,我最先感受到的是【XX】。"
                                 "牌面呈现出一种【XX】的氛围。"
                                 "画面中的【XX】似乎正在诉说【XX】。"
                                 "如果把这张牌看成一个场景,那么此刻最重要的是【XX】。"
                                 "它很贴近你目前正在经历的【XX】。"
                                 "这张牌像是在为整个故事打开序幕。"
                               最终目标:让牌面从符号变成场景,让场景自然过渡到现实议题

                           第一幕·启程（前段位置 — 交代背景）:
                             用 currentSituation/temporal_positions.past 铺陈背景
                             大牌出现→插入Fool's Journey注释
                             话术示例(故事线·第一幕启程——建立背景,说明如何发展到当前阶段,过去经验/模式/选择如何塑造现状):
                               优先观察:①起点(问题从哪里开始) ②已形成因素(哪些能量已存在/哪些影响已累积)
                                 ③旧模式(是否存在重复行为/关系/思维方式) ④过去如何影响现在(当前局面从何而来)
                               输出要求:回答过去发生了什么/哪些因素延续至今/为什么会来到当前阶段。禁止直接跳到结果
                               参考表达(非固定):
                                 "故事似乎从【XX】开始。"
                                 "当前局面的种子早在【XX】时就已经埋下。"
                                 "你并不是突然来到这里的。"
                                 "有一股能量已经持续影响你一段时间。"
                                 "过去的经历正在塑造现在的选择。"
                                 "某种模式正在重复出现。"
                                 "这张牌揭示了整个故事的起点。"
                               最终目标:建立背景,让现状变得有因可循

                           第二幕·试炼（中段位置 — 制造张力）:
                             用 challenge_and_growth / rootCause 制造张力和冲突
                             成组阅读(牌间关系规则):AI禁止逐张翻译后简单拼接,必须优先寻找牌间关系并整合为完整故事线
                               ①共振(Reinforcement):多位置相同主题/元素/数字/方向——该议题被强化,重复越多重要性越高
                                 例:多张权杖=行动力被强化,多张圣杯=情感议题被强化
                               ②张力(Tension):两张牌出现明显冲突(理智vs情感/行动vs犹豫/控制vs释放/现实vs理想)
                                 张力揭示当前最核心的矛盾,AI必须说明冲突在哪/为何形成/如何整合
                               ③补完(Complement):两张牌从不同角度描述同一件事,彼此补充信息——表层与深层/原因与结果/动机与行动/内在与外在
                               ④修正(Modification):一张牌改变另一张牌的表达方式——正面被限制/负面被缓和/行动被延迟/机会被放大,修正优先于单牌义
                               ⑤桥接(Transition):两张牌形成发展过程(原因→结果/事件→反应/选择→后果/冲突→调整),用于构建故事线
                                  AI必须解释前者如何发展为后者
                               优先级:张力>桥接>共振>补完>修正,同时出现时优先最强关系
                               输出要求:AI必须回答这些牌之间是什么关系/共同说明什么议题/共同构成怎样的发展过程
                                 禁止逐张解释/牌义堆叠/组合词典式查表
                               最终目标:牌义来自互动,故事来自关系——解读应呈现为背景→发展→冲突→调整→结果,而非多个独立牌义的拼接
                             元素尊贵冲突(spread_dignity)在此爆发
                             话术示例(故事线·第二幕试炼——呈现核心冲突,揭示阻碍/张力/矛盾/转折点,试炼不是坏事而是推动发展的力量):
                               优先观察:①挑战来源(外部环境还是内心状态) ②张力关系(共振/张力/补完/修正/桥接)
                                 ③正逆位变化(能量是否出现不足/阻塞/过度/内化) ④冲突核心(真正的问题/表面与深层是否一致)
                                 ⑤转折契机(冲突正在推动什么改变)
                               输出要求:说明冲突是什么/为何出现/如何影响发展/正在推动什么成长。禁止只描述困难,必须指出冲突的意义
                               参考表达(非固定):
                                 "事情开始出现拉扯。"
                                 "两股力量正在同时发挥作用。"
                                 "一部分的你希望【XX】,另一部分却倾向于【XX】。"
                                 "真正的挑战并非【表面问题】,而是【深层问题】。"
                                 "这里出现了一种明显的张力。"
                                 "当前最大的阻碍来自【XX】。"
                                 "逆位并不一定意味着失败,而是能量正在以不同方式表达。"
                                 "这段经历正在迫使你重新思考【XX】。"
                                 "冲突本身,正是转变开始的地方。"
                               最终目标:让冲突推动故事,让试炼揭示课题,让张力自然引向后续发展

                           第三幕·转化（转折点 — 能量翻转）:
                             关键牌的逆位/正位翻转 → 心境或命运的转变
                             大牌在这里特别重要——灵魂级别的转折
                             话术示例(故事线·第三幕转化——呈现能量如何改变,认知/模式/方向/课题如何推进,转化不是突然变好而是开始出现新选择):
                               优先观察:①能量变化(什么在结束/什么在形成) ②认知变化(是否出现新理解/新角度)
                                 ③旧模式松动(旧习惯/旧信念/旧关系模式已无法继续维持) ④逆位转化(优先说明能量如何失衡/如何重新流动/正在学习什么,禁止直接解释为坏结果)
                                 ⑤大牌转折(优先解释成长课题如何升级/人生章节如何变化)
                               输出要求:回答什么在改变/为什么需要改变/改变后将出现什么新可能。禁止只说结束/只说放下/强行正能量,必须指出旧模式→新方向的连接
                               参考表达(非固定):
                                 "有些东西正在发生变化。"
                                 "你过去依赖的方式已经开始失去作用。"
                                 "牌阵似乎在邀请你尝试另一种回应方式。"
                                 "真正的转折不在外部,而在认知层面。"
                                 "当你开始理解【XX】时,局面就开始改变。"
                                 "结束并不是重点,重点是新的空间正在出现。"
                                 "某个阶段正在走向完成,而新的阶段正在形成。"
                                 "这张牌更像是一次重新调整,而非彻底推翻。"
                                 "你未必要放弃一切,但可能需要改变处理问题的方法。"
                               特殊规则:
                                 死神→优先解释转化/更新/阶段结束/结构重组,禁止默认解释失去/灾难/终结
                                 高塔→优先解释真相显现/旧结构崩解/认知重建,禁止默认解释毁灭/坏事发生
                                 审判→优先解释觉察/回应召唤/阶段总结,禁止默认解释命运裁决
                               最终目标:让读者看见什么在改变/为什么改变/下一阶段将如何展开

                           第四幕·归来（结尾位置 — 收束）:
                             用 development / contextualMeanings 铺向未来
                             最末一对牌收束全局
                           收尾:
                             话术示例(故事线·第四幕归来——将观察/冲突/转化整合为现实可用的信息,不是预测结局而是带着新理解回到现实):
                               优先整合:①核心主题(整副牌真正讨论什么) ②核心课题(当前最需要面对什么)
                                 ③当前趋势(如果继续当前方向最可能出现什么结果) ④可执行回应(现在可以做什么)
                                 ⑤现实落点(如何把领悟转化为行动)
                               输出要求:回答整副牌最终想表达什么/最重要的信息是什么/下一步可以做什么。禁止空泛鸡汤/神秘宣言/强行励志/绝对化预测
                               参考表达(非固定):
                                 "当我们把整副牌放在一起看,最重要的信息是【XX】。"
                                 "你未必需要立刻解决所有问题。"
                                 "当前最值得采取的行动是【XX】。"
                                 "这副牌真正希望你看见的是【XX】。"
                                 "答案并不一定在改变环境,而可能在改变回应方式。"
                                 "有些事情需要行动,有些事情需要等待分辨。"
                                 "你已经知道问题在哪里,下一步是决定如何回应它。"
                                 "未来并非固定结果,而是当前趋势的发展方向。"
                                 "如果继续沿着现在的路径前进,【XX】将更有机会发生。"
                               收尾规则:优先主题总结→课题总结→行动建议,避免牌义重复总结
                               最终目标:让故事完成闭环——从问题出发,经过冲突与转化,最终回到现实行动;帮助问卜者带着更清晰的理解离开牌桌

                         ── 4. 人物志（逐牌深度解读 — 每张一个角色速写）──
                         【逐牌】
                         【位置名｜牌名】
                           镜头拉开: 画面描绘（STEP 4 description）
                           角色速写: coreKeyword + archetype（一词原型）
                           大牌补充: Fool's Journey 阶段
                           内心独白: readingAspects.innerState（正/逆位）
                           当前处境: 位置含义 + position_interpretations(rag)
                           心理挖掘: core_meanings.psychological + practical
                           象征点缀: get_symbols 选一个最有张力的符号展开
                           元素印记: elemental_correspondences 取元素/星座/行星/希伯来字母/灵数/季节/时间/颜色/水晶/草药共10项增强语气
                           宫廷牌(代表人物特征/态度/成熟度, 有时是行动信号)
                           暗线关联: card_relationships 与前后牌的增幅/挑战
                           每张 3-5 句，像速写一个角色，不是罗列数据
                           话术示例(人物志·逐牌深度解读——将单张牌展开为完整角色速写,探索画面/象征/心理/行为/现实映射,非仅复述关键词):
                             优先观察:①画面主体(人物/动作/姿态/表情/视线方向/位置关系) ②核心象征(颜色/数字/动物/植物/器物/建筑/自然元素/重复符号)
                               ③能量状态(主动/被动/等待/行动/扩张/收缩/稳定/失衡) ④心理层(正在相信什么/害怕什么/追求什么/回避什么)
                               ⑤行为层(会如何行动/回应压力/做决定) ⑥现实映射(可能表现为人物/关系/处境/事件/选择)
                             正逆位规则:正位=能量按其典型方式表达;逆位=表达方式发生变化(阻塞/内化/延迟/过度/缺失/固着/转化),禁止直接解释为反义词
                             输出要求:依次回答牌面正在发生什么/反映了什么心理状态/现实中可能如何表现/最想提醒什么
                             参考表达(非固定):
                               "这张牌首先呈现的是【XX】。"
                               "画面中的【XX】特别值得注意。"
                               "这里最强烈的感觉是【XX】。"
                               "这张牌反映出一种【XX】状态。"
                               "如果把这张牌当作一个角色,它正在经历【XX】。"
                               "现实中,这可能对应【XX】。"
                               "逆位并非失去这股能量,而是能量正在以不同方式表达。"
                               "这张牌最值得思考的问题是【XX】。"
                             最终目标:从牌面进入象征,从象征进入心理,从心理进入现实
                             "这属于【元素】的范畴——说明这件事在【元素领域】层面运作"

                         ── 5. 棋局 — 牌阵关系学(Spread Dynamics):
                           目的:牌义不仅来自单张牌,更来自位置关系/牌间互动/结构布局/元素流动/叙事发展。AI禁止逐张翻译后简单拼接,必须优先阅读牌阵内部关系
                           一、成组阅读(Group Reading):
                             寻找最显眼的一对牌（如赛尔特十字的1-2位置）,分析关系后再找下一对,逐步构建关系网
                             ①共振(Reinforcement):相同主题/元素/数字/方向重复出现→强化主题,提高权重
                             ②张力(Tension):两股力量冲突(行动vs等待/理智vs情感/控制vs释放/现实vs理想)→揭示核心矛盾
                             ③补完(Complement):两张牌共同描述同一件事,分别提供不同角度→形成完整图景
                             ④修正(Modification):一张牌改变另一张牌的表达方式(放大/削弱/延迟/缓和/转向)→修正解读方向
                             ⑤桥接(Transition):两张牌形成发展过程(原因→过程→结果)→建立故事线
                             此外还有特定对子类型:宫廷对(人物关系) / Ace-Ace对(起始能量)
                           二、三牌连读(Three-Card Flow):三张以上禁止拆开独立解读,必须建立连续叙事
                            过去→现在→未来:背景↓现状↓趋势 / 问题→过程→结果:起因↓发展↓落点 / 挑战→建议→结果:阻碍↓回应↓发展
                            相邻三张也可看作:左牌=背景,中牌=当前状态,右牌=方向
                            AI必须说明前一张如何发展为后一张,禁止三段独立解读
                           三、展开技巧(Narrative Expansion):适用于四张以上牌阵
                             优先寻找起点牌/转折牌/高潮牌/落点牌;构建背景→发展→冲突→转化→结果的完整叙事
                             若存在明显大牌→优先视为故事关键节点;若存在明显逆位集中→优先视为故事阻滞点
                             若牌阵>5张,拆成多个重叠sandwich,每个sandwich是完整句子,多句组成段落
                           四、对角牌张力(Diagonal Tension):适用于十字阵/凯尔特十字/九宫格/大型牌阵
                             优先检查过去vs未来/意识vs潜意识/理想vs现实/行动vs恐惧
                             对角线通常揭示隐藏冲突/深层课题/未被察觉的矛盾,优先级高于普通相邻牌关系
                             例:左上(外部期望)vs右下(真实渴望)→角色冲突
                           五、镜像反射(Mirror Reflection):适用于左右/上下/内外镜像
                             优先观察相同/相反元素/数字/主题,镜像揭示重复模式/潜意识投射/内外失衡/关系映照/未完成课题
                             镜像牌常用于回答:问题真正的根源是什么?
                           六、元素尊贵(Golden Dawn→Crowley→Greer):
                             互助:火+风,水+土→能量流动顺畅;支持:火+土,水+风→能量稳定发展
                             冲突:火+水,风+土→能量受阻,形成张力;相同元素→强化,主题被放大
                             元素尊贵用于修正牌义强弱,不能取代原始牌义
                             传统规则:Friendly(Fire+Air / Air+Fire · Water+Earth / Earth+Water)互相强化
                               Neutral(Fire+Earth / Earth+Fire · Water+Air / Air+Water)中性
                               Enemy(Fire+Water / Water+Fire · Air+Earth / Earth+Air)互相削弱
                             AI解读扩展(现代教学解释,非传统规则):
                               Fire+Fire:强化/集中/升温——行动力增强,热情升级;风险:急躁/冲动/过热
                               Fire+Air:激发/传播/推动——想法变行动,灵感扩张;风险:过度兴奋/缺乏落实
                               Fire+Water:冲突/转化/矛盾——行动与情感冲突;高阶表现:情绪驱动重大改变
                               Fire+Earth:落实/建设/约束——热情获得现实渠道;风险:现实限制热情
                               Air+Air:思考/分析/交流——信息交换增加,理性加强;风险:过度思考/分析瘫痪
                               Air+Water:联想/感知/想象——直觉增强,创造力提升;风险:情绪化推理/胡思乱想
                               Air+Earth:理论与现实——计划落地,知识实践;风险:理想与现实冲突
                               Water+Water:共鸣/疗愈/沉浸——情感连接加深;风险:情绪泛滥/过度敏感
                               Water+Earth:滋养/成长/扎根——情感获得现实承载,最稳定的成长组合之一
                               Earth+Earth:稳定/积累/固守——基础巩固,长期建设;风险:保守/停滞/僵化
                             使用规则:
                               1.元素尊贵(Friendly/Neutral/Enemy)优先级高于解读扩展
                               2.AI解读扩展仅辅助理解,非Golden Dawn传统规则
                               3.敌对组合不代表负面结果,仅表示存在张力或需协调
                               4.亲和组合不代表一定积极,仅表示力量更容易表达
                               5.最终解释始终以具体牌义与牌阵位置为准
                           七、主导牌(Dominant Card):优先寻找中心牌/唯一大牌/重复主题核心牌/全局张力焦点牌
                             主导牌决定整副牌最重要的议题;若同时为大牌,优先解释其课题意义
                           八、能量流动(Energy Flow):推进型=持续向前/停滞型=能量被卡住/循环型=相同模式反复/升级型=议题深化/回溯型=回到旧问题
                           九、主题提炼(Theme Extraction):优先来源=重复元素/数字/人物/大牌主题+主导牌+最强张力+最强共振
                             最终提炼为一句话核心主题;整副牌只能有一个核心议题,所有解读必须回归该主题
                           输出要求:AI必须回答哪些牌形成关系/哪些关系最重要/是否存在隐藏张力/是否存在重复模式/能量如何流动/当前核心主题是什么
                           最终原则:单张牌提供信息,牌间关系创造意义,牌阵结构塑造故事——最终解读应呈现主题→冲突→转化→发展方向,而非多个独立牌义的堆叠
                           牌间关系（get_relationships 交叉检查）:
                             本局哪些牌之间有增幅/挑战/澄清/同频/对冲/学习序列(learning_sequence)关系
                             学习序列 = 能量从低到高的自然进化路径(如宝剑3→5→8: 心碎→冲突→困境升级)
                           数字序列:
                             连续数字 → 进展信号
                             重复数字(EE.doubling) → 执念/强调
                           花色对话（Mary K. Greer: 花色叙事流——同花色逐卡讲故事）:
                             同花色→ 同一个生活领域被强调
                             元素冲突→ 内心/外界矛盾
                             同一花色的数字序列(如宝剑3→4→5)→ 这个领域的故事在推进
                           缺席元素（EE.absence）:
                             完全没出现的花色 → 被忽略的领域
                           话术示例(关系阅读——描述牌与牌之间的共振/张力/补完/修正/桥接/成长路径):
                             常见表达方向:指出两股力量之间的互动/说明一个主题如何被强化/揭示表面现象与深层动机的矛盾/描述两张牌如何共同构成一个完整故事/解释一种能量如何转化为另一种/说明当前阶段如何发展到下一阶段
                             推荐表达(非固定):
                               "当这两张牌被放在同一个画面里时,它们讨论的是同一个议题,但角度完全不同。"
                               "这两股力量并不是简单对立,更像是在争夺主导权。"
                               "其中一张牌强调前进,而另一张牌提醒你看见代价。"
                               "这两张牌共同补全了同一个故事,因此需要放在一起理解。"
                               "前一张牌所开启的过程,在后一张牌中获得了进一步的发展。"
                               "这里最值得注意的不是单张牌义,而是它们之间形成的关系。"
                               "表面上看是两个主题,实际上它们正在指向同一个核心问题。"
                               "这组牌揭示了你当前最重要的内在拉扯。"
                               "当这些牌连起来看时,可以清楚看到能量的发展轨迹。"
                               "牌阵真正的重点不在单张牌,而在这些牌彼此如何回应对方。"
                             禁止:仅罗列单张牌义/固定套用同一句模板/强行制造冲突或共振——所有关系必须来自实际牌面、位置与牌阵结构

                         ── 6. 秘传（按需展开，不预设隐藏）──
                         【进阶数据】
                           Fool's Journey 总览:
                             本局出现的大牌按旅程排序 → 灵魂当前在哪个阶段
                          卡巴拉映射:
                             大牌→希伯来字母→生命之树路径
                             数字牌→源质(1=Ace=Kether ... 10=Malkuth)
                             牌组→四世界(Wands=Atziluth ...)
                          数字学（Pythagorean + 塔罗数字序列）:
                             EE.numerology 加总 → 核心数字
                             数字含义:
                               1=开始/独立(魔术师/王牌), 2=对立/平衡(女祭司/恋人),
                               3=创造/表达(皇后/三牌), 4=稳定/秩序(皇帝/四牌),
                               5=变化/冲突(教皇/五牌), 6=和谐/选择(恋人/六牌),
                               7=内省/智慧(战车/七牌), 8=力量/因果(力量/八牌),
                               9=完成/转化(隐士/九牌), 10=循环/命运(命运之轮/10牌)
                               11(22)=大师数(直觉/灵性), 33=大师数(慈悲/服务),
                               44=大师数(物质显化)
                             重复数字意义:
                               加总结果=某牌的编号 → 那张牌是本局的核心密钥
                               Master Number(11/22/33/44)保留不约分 → 灵性级课题

                         ── 7. 落幕与回响 ──
                         【结论】一句话核心洞见
                         【建议】≤3条，优先用 readingAspects.advice（正/逆位对应）；若牌阵有 Advice/Your Approach 位置则用 position_interpretations.guidance_and_action；辅以 Waite meaning_up/meaning_rev 作参考；affirmations 融合润色。每条建议要具体可执行，不空泛
                         【肯定语】1条 affirmations 鼓舞收尾
                         【反思问题】1条切中阅读主题的问题。从 journaling_prompts 中选与【主题定性】最相关的一条，或根据 readingAspects.innerState 自己拟一句。问题要开放、不自问自答，让问卜者带着这句话离开牌桌
                         【箴言】从 coreKeyword / essence / affirmations 中提炼成一句隐喻式收尾——不直接重复牌义，用牌面符号做画面类比——让问卜者带走一个能反复回味的意象



                       ╔══════════════════ 塔罗数据 ═════════════════╗
                       【塔罗数据使用规则】
                         必须使用：get_core_meaning(reversed=) / get_interpretation(rag_mapping, reversed=) / get_question_context(question_type, reversed=) / get_relationships() / get_affirmations() / get_journaling_prompts() / meditation_focus / .raw_data(全部原始字段)
                         用于润色：get_symbols()→for k,v in .items()(返回dict) / get_elemental_correspondences() (共10项: element/zodiac/planet/hebrew_letter/numerology/season/time_of_day/colors/crystals/herbs)
                         结构分析(仅【牌阵结构】): statistics + composition.major_arcana_ratio + composition.court_card_ratio + composition.repeated_numbers + composition.repeated_suits + reversal.blocked_energy_signal
                         秘传附录(Kaabalah JS引擎按需调用:hebrew_letters/tree_of_life/777/four_worlds/sephiroth，不在正文展开，仅当NNL确认与解读相关时取用)
                       ╔══════════════════ 塔罗核心参考字典 ═══════════════╗
                         本字典为LLM内部参考,不直接输出。花色/数字/宫廷牌/逆位/元素/叙事补充规则:
                         一、花色人格与领域(Suit Personalities,基于Tarot.com):
                           Wands=火→行动与激情(权杖多=行动驱动阶段)
                           Cups=水→情感与直觉(圣杯多=情感主导期)
                           Swords=风→理智与思考(宝剑多=脑内博弈期)
                           Pentacles=土→物质与现实(钱币多=物质聚焦期)
                         二、数字成长链(Ace→10每条花色通用的叙事逻辑):
                           Ace=Potential(潜力) | 2=Polarization(极化/对立)
                           3=Expansion(扩张) | 4=Stabilization(稳定)
                           5=Disruption(瓦解) | 6=Adjustment(调整)
                           7=Testing(考验) | 8=Development(发展)
                           9=Culmination(顶点) | 10=Completion(完成)
                           用法:同一花色连续数字=这个故事在推进;重复数字=该主题被强烈强调
                          三、宫廷牌判定(强制执行):
                             原则:宫廷牌优先代表「人」,若无法合理对应具体人物再依次降级,禁止一上来就解释成事件
                             ①真人(最高优先级):代表真实存在的人——问卜者/对象/家人/朋友/同事/上司/客户/陌生人等
                               优先条件:问题涉及人物关系/牌阵存在人物互动/出现多个宫廷牌
                             ②人格面具:代表问卜者当前表现出来的角色——此刻正在成为谁
                               如权杖国王=领导者模式,圣杯皇后=照顾者模式,宝剑骑士=进攻者模式,金币侍从=学习者模式
                             ③行为模式:代表事情正在通过何种方式推进——重点不是谁而是事情怎么运作
                               如宝剑骑士=快速推进,圣杯皇后=感受优先,金币国王=务实规划,权杖侍从=探索尝试
                             ④原型能量:代表一种心理原型,适用于成长课题/心理分析/自我探索/灵性问题
                               如圣杯国王=成熟情感掌控者,权杖皇后=生命力与魅力原型,宝剑国王=理性秩序原型
                             ⑤事件/信号(最低优先级):仅当前四项均不成立时使用
                               Page=消息/邀请/通知/学习机会 | Knight=行动/出发/追求/冲突/推进
                               Queen=培育/积累/孕育/稳定发展 | King=决策/授权/管理/定案
                               禁止默认解释为事件,事件解释永远最后启用
                             多张宫廷牌规则:2张以上优先解释人物互动,3张以上优先解释关系网络,4张以上通常表示问题核心与人际关系有关
                             最终优先级:真人>人格面具>行为模式>原型能量>事件信号
                         四、宫廷牌层级(Page→Knight→Queen→King为同一元素能量的四个成长阶段):
                            Page(侍从):学习/探索/接收/观察/消息/可能性——刚接触该元素,愿意学习,尚未成熟
                              核心问题:"这是什么?" 核心动力:好奇
                            Knight(骑士):行动/追求/推进/冒险/执行/证明自己——开始实践和测试能力,追逐目标
                              核心问题:"我要如何做到?" 核心动力:行动
                            Queen(皇后):内化/成熟/理解/滋养/培育/稳定——已掌握该元素,不急于证明,开始培养与维持
                              核心问题:"如何长期发展?" 核心动力:整合
                            King(国王):掌控/领导/决策/责任/权威/治理——能稳定运用该元素,影响环境,带领他人
                              核心问题:"如何有效运用?" 核心动力:管理
                           成长链:Page↓学习→Knight↓实践→Queen↓内化→King↓运用
                           心理成长链:Page="我不知道"→Knight="我去试试"→Queen="我理解了"→King="我能驾驭了"
                           核心公式:Page=潜力 / Knight=动能 / Queen=成熟 / King=主导
                           AI解读时必须同时结合阶级+花色元素,禁止只读阶级
                             例:权杖骑士≠骑士,而是行动中的火元素;圣杯皇后≠皇后,而是成熟的水元素
                         五、逆位体系(Bunning×Greer):
                           原则:逆位≠负面,≠正位反义——逆位是能量表达方式的变化
                           A.Bunning能量水位(基础层)—先判断能量状态:
                             不足:能量弱化,无法正常发挥
                             阻塞:能量存在但受限制
                             过度:能量失控,走向极端
                             内化:能量向内运作,体现在心理层面
                           B.Greer逆位12视角(解释层)—按问题背景选最匹配视角,非固定牌义:
                             阻塞(Blocked) | 投射(Projected) | 内化(Internalized)
                             延迟(Delayed) | 缺失(Lacking) | 否定(No/Not)
                             过度(Excessive) | 误用(Misdirected) | 释放(Release)
                             退化(Regression) | 突破(Breaking Through) | 暗月期(Dark Moon/孕育中)
                           C.选择规则:每张逆位最多1个主机制+1个辅助,禁止同时套用12种
                             输出须说明倾向性,如"此处更接近阻塞而非缺失"
                           D.大牌逆位:优先用Greer体系,解释顺序为课题→阻碍→转化
                           E.小牌逆位:优先用Bunning体系,先判断不足/阻塞/过度/内化
                           F.宫廷牌逆位:优先解释人格失衡,顺序为元素失衡→人格表现→关系互动
                           G.全盘逆位比例:0-25%局部阻碍 / 25-50%明显卡点 / 50-75%核心议题未解 / 75%+深层调整期
                             注意:逆位多≠坏结果,通常代表内在工作/调整/成长压力增加
                           H.转化原则(强制):每张逆位必须回答"这股能量如何恢复流动?"
                             禁止只描述问题,必须给出阻塞点+转化方向
                           I.冲突优先级:若牌义正面但逆位负面,先判断阻塞还是内化,禁止直接翻转牌义
                             仅Greer No/Not明确成立时才允许接近反义解释
                           J.最终结论:逆位本质不是坏运,而是能量失衡/转向/转化/重组——解读目标是找到能量卡在哪里,以及如何重新流动
                         六、主题识别(从牌阵中识别核心主线):
                           关注:大牌(深层能量) | 中心/指示位 | 重复数字/花色 | 宫廷牌(人物主题)
                           综合以上因素判断哪张牌最可能是全阵核心,而非固定权重公式
                         七、元素过载规则:
                           某元素明显占主导时=该领域过度专注,需要补充相反元素
                           Wands过载=行动过度/急躁 | Cups过载=情绪过度/沉溺
                           Swords过载=过度思考/焦虑 | Pentacles过载=物质导向/僵化
                           补法:找全阵中缺失的元素对应的牌作为建议方向
                         八、多牌叙事读取(Narrative Reading Engine):
                           核心原则:位置意义优先于张数意义。任何牌必须先依据牌阵位置解释,再参与整体叙事
                             若牌阵已定义位置含义(如过去/现在/未来/障碍/建议/结果等),优先遵循牌阵定义
                             仅当牌阵没有明确位置定义时,才使用以下叙事规则
                           三牌结构(Three Card Flow):
                             Card1=Origin/Foundation/Past:起因/背景/基础条件
                             ↓ Card2=Core Dynamic/Present:当前状态/核心动力/主要课题
                             ↓ Card3=Direction/Outcome/Future:发展方向/趋势/可能结果
                             叙事逻辑:起源→核心→发展
                           四牌结构(Four Card Flow):
                             Card1=Foundation:基础条件
                             ↓ Card2=Development:事态发展
                             ↓ Card3=Shift:变化点/转折点/关键调整
                             ↓ Card4=Outcome:结果/落点/后续方向
                             叙事逻辑:基础→发展→变化→结果
                           五牌结构(Five Card Flow):
                             Card1=Foundation:基础条件
                             ↓ Card2=Development:发展过程
                             ↓ Card3=Core Theme:核心主题/关键问题/中心能量(非固定高潮)
                             ↓ Card4=Shift:变化点/突破口/调整方向
                             ↓ Card5=Outcome:结果/落点/未来趋势
                             叙事逻辑:基础→发展→核心→变化→结果
                           六张以上结构(Extended Narrative Flow):
                             优先依据牌阵本身定义读取;若牌阵无明确位置定义,按叙事聚类读取:
                               Beginning Group=开端层:问题起源/背景因素/历史条件
                               Middle Group=当前层:核心课题/现实状态/正在运作的力量
                               Ending Group=发展层:未来趋势/可能结果/最终落点
                           叙事强化规则(Narrative Amplifiers)——以下信号出现时提升权重:
                             大牌连出→人生课题/关键转折/长期影响
                             宫廷牌连出→人物关系/人格动力/社会互动
                             同花色连出→对应领域能量集中(权杖=行动/意志,圣杯=情感/关系,宝剑=思想/冲突,钱币=现实/资源)
                             重复数字→对应数字主题被强化(如多张4=稳定/结构,多张5=挑战/变化,多张9=成熟/完成)
                             同元素重复→该元素主导整体局势(火=行动/水=情感/风=思想/土=现实)
                             大量逆位→可能暗示能量内化/延迟/阻滞/重新调整(不自动视为负面)
                           牌与牌的动态关系:
                             ①共振(Reinforcement):多位置相同主题/元素/数字/方向→该议题被强化,重复越多重要性越高
                             ②张力(Tension):两张牌出现明显冲突(理智vs情感/行动vs犹豫/控制vs释放/现实vs理想)→揭示当前最核心的矛盾
                             ③补完(Complement):两张牌从不同角度描述同一件事,彼此补充信息(表层与深层/原因与结果/动机与行动/内在与外在)
                             ④修正(Modification):一张牌改变另一张牌的表达方式——正面被限制/负面被缓和/行动被延迟/机会被放大,修正优先于单牌义
                             ⑤桥接(Transition):两张牌形成发展过程(原因→结果/事件→反应/选择→后果/冲突→调整),用于构建故事线
                             优先级:张力>桥接>共振>补完>修正,同时出现时优先最强关系
                             输出要求:必须回答牌之间是什么关系/共同说明什么议题/共同构成怎样的发展过程,禁止逐张解释/牌义堆叠
                           最终综合原则:先读位置→再读单牌→再读牌间关系→最后构建整体叙事
                             任何叙事结论必须同时得到牌义+位置+牌间关系至少两项以上验证方可作为主线结论
                             避免仅凭单张牌或单一象征做最终判断
                         九、位置互动规则:
                           原则:位置决定牌义落点,互动决定故事线。单牌先看位置,多牌必须看互动
                           一、时间线(过去→现在→未来):
                             过去=背景/根源/已发生影响 | 现在=当前能量/现实状态 | 未来=若趋势持续的发展方向
                             必须说明"过去如何导致现在,现在如何走向未来",禁止拆成独立解读
                           二、相邻桥接:相邻位置优先形成故事线,每对须回答"前者如何影响后者?后者如何回应前者?"
                             若无法建立逻辑联系,优先寻找情绪/事件/认知/关系/动机等连接,禁止解释成孤立段落
                           三、挑战→建议:必须成对解读。挑战位=问题/阻碍/盲点;建议位=调整方向/行动路径
                             建议必须直接回应挑战,禁止各讲一套。正确结构:问题→解法
                           四、显意识→潜意识:显意识=已知想法/当前认知/主动策略;潜意识=隐藏动机/情绪根源/深层需求
                             若一致=内外认知统一;若矛盾=潜意识通常是根因,显意识是当前应对方式
                             优先寻找真正驱动力来自哪里
                           五、外部→内部:外部=环境/他人/条件/压力;内部=信念/情绪/主观/心理
                             若一致=能量顺畅;若矛盾=矛盾处即核心议题,优先解释为什么外在现实与内在感受不同
                           六、位置呼应:不同位置出现相同数字/元素/花色/宫廷阶级/主题=被强化,重复越多重要性越高
                             必须指出哪些主题在重复出现
                           七、位置冲突:两位置出现明显相反含义时,禁止分别解读。优先解释冲突点是什么、为何出现、如何整合
                             冲突代表内外/理智情感/目标现实/需求责任的矛盾,冲突本身即为信息
                           八、因果链:多张牌时优先寻找因果关系(事件→反应/选择→结果/信念→行动等),禁止只做牌义堆叠
                           九、中心牌优先:奇数牌阵中心位置(三牌第2/五牌第3/七牌第4)优先级最高
                             中心牌=核心议题/关键转折/隐藏重点,其他位置围绕中心牌展开
                           十、边缘牌修正:边缘位置=背景/条件/补充/外围影响,可修正但通常不推翻中心牌结论
                           十一、整体叙事:所有位置最终整合为背景→起因→发展→冲突→调整→结果,禁止逐张翻译/堆叠/流水账
                           十二、优先级:中心牌>位置定义>位置互动>数字呼应>元素呼应>单牌义。冲突时优先高优先级规则
                           最终目标:位置不是独立信息栏——位置之间互相解释、强化、修正、冲突,AI必须整合为一条完整连贯的叙事链
                       ╚════════════════════════════════════════════╝

                       ╔══════════════════ 逆位解读（详见参考字典五·逆位体系）══════════════════╗
                       逆位非独立于参考字典,完整规则见【五、逆位体系(Bunning×Greer)】
                       核心提醒:
                         • 正位:能量以该牌经典方式向外表达
                         • 逆位:能量表达方式发生变化——可能表现为不足/阻塞/过度/内化
                         • 逆位不自动等于负面,不自动等于正位反义
                         • 同一张逆位可对应不同机制,AI需结合问题背景判断
                       解读顺序:
                         ①先判断Bunning能量状态(不足/阻塞/过度/内化)
                         ②再选择Greer视角(最多1主机制+1辅助)
                         ③输出阻塞点
                         ④输出转化方向
                       强制规则:
                         ✓每张逆位必须说明"能量卡在哪里"+"如何恢复流动"
                         ✓优先描述能量变化,禁止直接翻译成吉凶
                         ✓禁止机械套用"延迟""阻塞""缺失"等标签
                         ✓禁止将逆位直接解释为正位反义
                       最终目标:找到能量如何失衡、如何转向、以及如何重新流动

                       ╔══════════════════ 塔罗牌阵 ═════════════════╗
                       from tarot_elemental_engine import ElementalDignityEngine as EE; from arcanite.core.spread import list_spreads, load_spread
                         list_spreads() → 塔罗11牌阵: single-focus / past-present-future / mind-body-spirit / situation-action-outcome / five-card-cross / four-card-decision / relationship-spread / horseshoe-traditional / horseshoe-apex / celtic-cross / year-ahead
                       ╚════════════════════════════════════════════╝

【塔罗卡巴拉全对应】arcanite抽牌→查本表→Kaabalah.buildKaabalisticMapData()一键拿全映射(源质+字母+路径+行星对应). 来自Crowley 777/黄金黎明.
 大牌(22): 序号=KeyScale, 字母=希伯来字母, 路径=生命之树路径, Fool's Journey阶段(Eden Gray创始)
    0=Fool(Aleph,11,出发) 1=Magician(Beth,12,创造) 2=HighPriestess(Gimel,13,直觉)
    3=Empress(Daleth,14,丰饶) 4=Emperor(Heh,15,秩序) 5=Hierophant(Vau,16,导师)
    6=Lovers(Zain,17,结合) 7=Chariot(Cheth,18,掌控) 8=Strength(Teth,19,勇气)
    9=Hermit(Yod,20,内省) 10=WheelOfFortune(Kaph,21,命运)
    11=Justice(Lamed,22,因果) 12=HangedMan(Mem,23,顺服)
    13=Death(Nun,24,结束) 14=Temperance(Samekh,25,平衡)
    15=Devil(Ayin,26,阴影) 16=Tower(Peh,27,崩塌)
    17=Star(Tzaddi,28,希望) 18=Moon(Qoph,29,恐惧)
    19=Sun(Resh,30,喜悦) 20=Judgement(Shin,31,觉醒)
    21=World(Tau,32,圆满)
    查法: Kaabalah.HEBREW_LETTERS_DATA[letter.upper()] 又 Kaabalah.SPHERES_DATA[name.upper()]
    ⚠️ LURIANIC_PATHS 是名称→编号映射, 不能直接按编号查。拿完整路径数据用:
    buildKaabalisticMapData({}).paths → 数组,每条含 from/to/meaning
    Kaabalah编号1-22 = Crowley路径11-32 (减10)
 数字牌(40): Ace=1=Kether,2=Chokmah,3=Binah,4=Chesed,5=Geburah,6=Tiphareth,7=Netzach,8=Hod,9=Yesod,10=Malkuth
    牌组→世界: Wands=Atziluth, Cups=Briah, Swords=Yetzirah, Pentacles=Assiah
    查法: Kaabalah.SPHERES_DATA["KETHER"] 又 Kaabalah.FOUR_WORLDS["ATZILUTH"]
 宫廷牌(16): King→Chokmah, Queen→Binah, Knight→Tiphareth, Page→Malkuth
    牌组→世界同上, 查法: Kaabalah.SPHERES_DATA["CHOKMAH"] + Kaabalah.FOUR_WORLDS["ATZILUTH"]

  • 塔罗: arcanite(Python)78张+牌阵+正逆位,洗牌抽牌解读 | 深度→查777表→Kaabalah(JS,SPHERES_DATA/FOUR_WORLDS/HEBREW_LETTERS)取卡巴拉对应 | 都硬件真随机
                       ╚══════════════════ 塔罗 ══════════════════╝

                       ╔══════════════════ 雷诺曼 ═════════════════╗
 雷诺曼         →  arcanite(system="lenormand") 36张; 数据层:
                        core(keywords/charge/category/topics) | timing(thematic/duration/season/speed(fast/moderate/slow/instant/glacial/variable/None)/direction)
                        as_person(牌的人物性格描述) | modifier_behavior(type(descriptor描述/intensifier放大/negator反转/pivot转折)/as_modifier/as_modified)
                        playing_card(对应扑克牌,如"10 of Hearts"/"Ace of Diamonds") | topic_contexts(love/career/health/finances/spiritual)
                        line_reading(as_first/as_middle/as_last) | combination_grammar(7种配牌语法)
                        combinations(16组固定组合,含with/with_number/category/as_first/as_second)
                        grand_tableau(as_house/near_significator/far_from_significator/diagonal_or_corner)
                        访问: d.get_card(c.card_id).get_core() / get_timing() / get_as_person() / get_modifier_behavior() / get_playing_card() / get_topic_contexts() / get_line_reading() / get_combination_grammar() / get_combinations() / get_grand_tableau() — 语义getter, 禁止 raw_data 裸访问
                        组合: card.get_combination_with("the_clover", position="left") → 自动含方向+语法回退
                        无需出生

                        ╔══════════════════ 雷诺曼 ═════════════════╗
                        雷诺曼: from arcanite.core import LenormandDeck; d=LenormandDeck.load(); items=d.draw_with_data(N, seed=42)
                        # ⚠️ 抽牌机制: draw_with_data() 每次从完整36张重新洗牌, 不消耗牌堆
                        #   不加 seed: secrets.SystemRandom 真随机
                        #   加 seed(int): random.Random(seed) 可复现, 同seed同牌序+同正逆位
                        #   推荐: 第一次抽牌时记录 seed, 崩了重试用同 seed
                        [print(item.card_id,item.card_name) for item in items]
                       ⚠️ print仅取数据。解读正文必须写在回复里，不准在Python里print解读
                        深度: [item.get_core() for item in items] — 一步直接调语义getter
                        组合链: item_A.get_combination_with(item_B.card_id, position="left")
                        统计: d.analyze_draw(items) → {count, upright_count, reversed_count, all_upright, all_reversed, pattern, cards}; 需自行从cards统计: 电荷分布(positive/neutral/negative) / 速度分布(fast/moderate/slow等) / 人物卡(category=person的牌)

                        【雷诺曼输出】雷诺曼=现实事件模拟器
                         来源说明:
                           本模板的解读方法取自 Mary K. Greer 博客文章：
                           「Linda Marson Interviews Mary on Using Lenormand Cards」
                           「Ex Machina – Lenormand and Artificial Intelligence」
                           「Learn Lenormand Webinar」
                           以下书籍经 OpenLibrary 验证存在但内容未直接引用:
                           Rana George《The Essential Lenormand》(2014)
                           Caitlín Matthews《The Complete Lenormand Oracle Handbook》(2014)
                           Andy Boroveshengra《Lenormand: Thirty-Six Cards》(2014)
                           Sylvie Steinbach《The Secrets of the Lenormand Oracle》(2007)

                         核心原则:
                           ① 牌从不单独解读——每张牌都在组合中形成含义
                           ② 指示牌(Man/Woman)锚定全盘，其他牌以它为参照（Greer传统法: Man=男问卜者，女问卜者则Man=她的重要他人）
                           ③ 首牌=主题，距指示牌越近=影响越大越直接
                           ④ 牌面人物视线方向=能量流向
                           ⑤ Greer: 传统雷诺曼=先懂牌义再凭直觉串——"传统读者非常直觉，他们看一眼就知道牌在说什么，再用原义核实"

                         ── 开读 ──
                           确定牌阵类型: 线型(line-3/5/7/9) 还是 Grand Tableau?
                           线型→ 从左到右读成一句话
                           GT→ 先定位指示牌(Man/Woman), 以它为原点展开
                           Greer两步法（来自Ex Machina实际解读）:
                             第一步: 每张牌翻译为关键词 → 形成一句基本意思
                             第二步: 将这句话展开为与问卜者情境相关的完整叙事

                         ── A. 总体印象 ──
                           【问题】用户原问
                           【牌阵】名称
                           【一眼直觉】
                             指示牌在哪里？什么牌在它旁边？（首牌=主题）
                             正负电荷比例→ 整体能量偏向
                             速度牌分布→ 事件节奏快/慢
                             人物卡出现→ 谁登场了
                           话术:
                             "【女人】旁紧贴【心】——感情是核心议题"

                         ── B. 逐牌解读（Greer两步法）──
                           第一步·关键词翻译:
                             每张牌先给出它的核心关键词
                             Greer实际示例: "Coffin means illness, financial loss, endings"
                             按topic_context取具体含义（同牌不同义）
                           第二步·串成句子:
                             把每张牌的关键词串成一句基本意思
                             Greer示例: "With the arrival of a guest (Rider) comes a theft (Mice) of success (Sun) and an obstacle (Mountain) to something new (Child)"
                           然后展开为完整叙事:
                             "What the spread points to is the arrival of [人物] at/in [场景]. They must overcome [障碍] to [目标]"
                           话术:
                             "【骑手+老鼠+太阳+山+小孩】→ 一位客人的到来，带来了对成功的窃取，以及对新事物的阻碍"

                         ── C. 组合链（Greer: 线型牌阵读成一句话）──
                           线型牌阵:
                             每对相邻牌形成"名词+修饰语"组合
                             A+B→含义, B+C→推进, 整条链形成句子
                             相邻牌=B修饰A的属性
                             固定组合: 引擎预置16组固定组合数据
                           话术:
                             "【花园+船】—社交引向旅行"
                             "【棺材+花束】—结束中带希望"

                         ── D. 牌阵互动（Greer: 传统法 vs 现代法）──
                           传统法（来自Greer采访原文）:
                             "The first card on the left is the subject"
                             "The nearer Coffin is to the person (Man) the more serious the situation"
                             左=主题, 右=发展: "Cards to the left of Coffin show what is lost, while cards to the right show future"
                           现代传统法:
                             在传统基础上增加灵活度，"core meanings should always show through"
                           线型补充:
                             首牌=主题, 末牌=结果
                             三牌一组: 开始→发展→结果
                           Grand Tableau (Greer课程内容):
                             先找指示牌 → 读它周围的牌 → 行读(每行一个故事) → 列读(每列一个主题)
                             镜像(Mirror): 对称位置的牌互为提示
                             骑士跳(Knight's Move): 马步跳跃产生隐藏关联
                             内九宫格(Inner Ring): 任意牌周围3×3局部叙事
                             级联链(House Chaining): 落宫叠加含义

                         ── E. 事件故事（Greer: 把组合链展开为叙事）──
                           Greer两步法第三步: 将关键词句子展开为现实事件
                             从"Rider+Mice+Sun+Mountain+Child"
                             → "一位年轻人来到孤山别墅，必须跨越一切障碍去偷一个全新的存在"
                           按时间: 起因→发展→转折→结果
                           按人物: 谁→对谁→做什么→结果
                           话术:
                             "这5张牌的故事: 收到消息【骑手】→对话【花园】→犹豫【云】→决定【百合】→达成【锚】"

                         ── F. 落幕与回响 ──
                           【结论】一句话现实结果
                           【建议】≤3条
                           【反思问题】1条

                        ╚════════════════════════════════════════════╝

                        ╔══════════════════ 雷诺曼数据 ═══════════════╗
                        【雷诺曼数据使用规则】
                          必须使用：core / keywords / combination_rules / modifier_behavior / line_reading
                          用于润色：timing
                          playing_cards → 每张牌对应扑克牌(如"9 of Hearts")。详见【雷诺曼扑克插片参考字典】——权重/四花色吉凶/宫廷牌用神/数字含义/三步推演法
                          as_person → 抽到人物类卡(骑手/男人/女人/小孩/熊/狗等)时激活，在该牌解读中展开角色描写
                        ╚════════════════════════════════════════════╝

                        ╔══════════════════ 雷诺曼牌阵 ═══════════════╗
                        from arcanite.core.spread import list_spreads, load_spread
                          list_spreads(system="lenormand") → 雷诺曼: line-3(3张) / line-5(5张) / line-7(7张) / line-9(9张) / grand-tableau(36张全盘) / box-3x3(9张) / cross(5张) / astrological-houses(12张) / relationship(5张关系)
                          load_spread(spread_id, system="lenormand") → SpreadDefinition(positions=...) 按位置数决定draw(N)
                          Grand Tableau: 4×9网格,36宫role=house,sig=false(男人/女人牌游走) | 坐标计算一律调用FE方法,不在此处理:骑士跳→FE.calculate_knights_move 反射→FE.get_reflection 镜像→FE.get_gt_mirrors 内九宫格→FE.get_inner_9_ring 交叉→FE.get_intersection | 镜像位: pos.mirror_target | 指示牌: pos.is_significator
                          牌阵位置名对应输出的【位置｜牌名】，rag_mapping对应牌位解读层
                        ╚════════════════════════════════════════════════╝
                        ╔══════════════════ 雷诺曼扑克插片参考字典 ═══════════════╗
                          本字典为LLM内部参考,不直接输出。花色/数字/宫廷牌用法:
                          动态权重(根据问题类型取用,不可死板):
                            【看整体趋势】主图80%+花色20%
                              主图定吉凶→花色定累不累→完全无视JQK和数字
                            【找人定性】主图50%+宫廷牌50%
                              宫廷牌=核心,JQK画像叠加花色气场(如♥K温和/♣K高压)+主图性格
                            【数字】仅36张大阵抓同频共振,小阵权重极低可忽略
                          四花色(源自18世纪德国《希望游戏》社会阶层,非塔罗四元素):
                            ♥红心=神职/家庭 → 大吉。感情融洽/有贵人/人情味,过程顺心舒服
                            ♦方块=铃铛/贵族 → 偏吉。动态/快节奏/金钱/现实利益
                            ♠黑桃=树叶/地主 → 中性。官方/规矩/契约/社交/讲理智
                            ♣梅花=橡果/劳工 → 大凶。绝对阻力/烂摊子/巨大心理负担
                          实战切入:红心多=这局稳了舒服;梅花多=就算事能成也心力交瘁
                          宫廷牌(人头标签J/Q/K仅找特定人时启用,否则只看雷诺曼主图):
                            K=掌权者/老板/父亲/有话语权的成熟男性
                            Q=成熟女性/母亲/女上司/女竞争者
                            J=年轻人/下属/晚辈/小孩/来传话的人
                          数字含义(仅含6-10与A无2345,仅36张大阵中3-4张同数扎堆时启用,否则彻底无视):
                            A=绝对开端/大洗牌 | 6=宿命感/深根蒂固(如十字架/塔) | 7=琐碎/口舌是非(如老鼠/鸟)
                            8=群体瞩目/社会活动(如花园/月亮) | 9=极端动静/大变局(如骑士/锚/棺材) | 10=宏大格局/大体量(非结局,如熊/狗/船)
                          注:①不摆大阵雷达关机;②散落1-2张或距离太远权重归零,不解读;③28男人(♥A)与29女人(♠A)作为核心指示牌时,A的数字属性豁免
                          三步推演法(LLM内部推理顺序,非输出section):
                            ①蒙花色直读大图(定主线)——只看雷诺曼图像讲核心故事
                            ②清点花色定气场(看环境)——数花色比例定吉凶基调
                            ③查触发提用神(抓细节)——问人提取JQK(叠加花色气场),否则到此为止
                          Anti-Tarot Guard(最高纪律):雷诺曼是事件语言,不是塔罗灵修
                            优先回答:谁→什么事→在哪里→为什么发生→最终结果
                            禁止回答:潜意识/灵性成长/内在小孩/宇宙讯息/疗愈创伤/能量升级
                            除非问题本身询问心理状态,否则优先现实事件解释
                          Charge动词映射:
                            正电荷→促进/支持/顺流/获得; 中电荷→描述/背景/信息/状态
                            负电荷→损耗/延迟/阻碍/终止; 负牌有较强支配力但不绝对否决
                            最终结果由位置+顺序+组合+上下文共同决定
                          Functional Role(语义角色,与modifier_behavior.type互补):
                            启动器:Rider,Child | 信息载体:Letter,Birds
                            放大器:Sun,Bear,Stars | 侵蚀器:Mice | 阻断器:Mountain
                            终止器:Coffin | 转化器:Stork | 切割器:Scythe | 选择器:Crossroads
                            连接器:Ring | 固定器:Anchor | 资源:Fish,Tree,Bouquet
                            权威:Bear,Tower | 人物:Man,Woman,Child,Rider,Dog
                            地点:House,Garden,Tower,Ship | 障碍:Mountain,Cross,Clouds
                        ╔══════════════════ 雷诺曼核心数据参考字典 ═══════════════╗
                          本字典解释引擎数据字段的实战含义,供LLM推理时参考:
                          charge(电荷):正=顺利/吉,中=中性/待定,负=阻力/凶
                          modifier_behavior.type(修饰类型):
                            descriptor描述=赋予属性 | intensifier放大=加强程度
                            negator反转=削弱/损耗/破坏 | pivot转折=改变方向
                            注:terminator终止(如Coffin+Ring)由negator覆盖
                          combination_grammar(7种语法):
                            ①名词+形容词=左牌主语被右牌修饰 | ②主体+动作=谁做什么
                            ③因果=左因右果 | ④状态变化=…之后转变 | ⑤障碍路径=阻力下的事件
                            ⑥叙事链=A→B→C→D完整事件 | ⑦按语境自由组合
                          line_reading(行位角色):
                            as_first=主题/问题起点/核心议题
                            as_middle=过程/摩擦/推动/发展
                            as_last=结果/落点/最终趋势(权重大但不绝对,须结合全链)
                          timing.speed(节奏尺):
                            instant=数小时~数天 | fast=数天~数周 | moderate=数周~数月
                            slow=数月~一年 | glacial=长期停滞 | variable=环境决定
                            只作节奏参考,禁止断言精确日期
                        ╚═══════════════════════════════════════════════════╝
                        ╔══════════════════ 雷诺曼输出模板(权威版) ═══════════════╗
                          输出(不分层,所有牌阵通用,引擎数据全开):
                          原则:永远先识别问题领域(财运/感情/事业/健康…)再解释牌义,同一个牌在不同领域讲不同故事
                          【问题】— 问卜原句
                          【牌阵】— 牌阵名称+张数
                          【一句话答案】— 核心结论,开门见山
                          【主题定性】— 先定基调(Greer:"先判断整体能量走向,再展开细节"),让问卜者立刻抓住解读的重点方向
                          【能量色调】— 全局电荷正/中/负占比,定性整体能量是上升/下降/混合/矛盾; 同时检测"包围否定"效应:若某牌被周围两张相反电荷的牌夹击,其基础含义可能被削弱甚至反转(德传Kartenlegen:umliegende Karten negieren)
                          【整体叙事】— 按照"故事的情节"构建(Greer原话:They best address what has/is/will happen, like the plot of a story):
                            每张牌优先映射为:Person人/Event事件/Location地点/Resource资源/Obstacle障碍/Outcome结果
                            然后自动生成:谁→在哪里→遇见什么→发生什么→最终怎样
                            禁止只罗列关键词,必须形成完整事件叙事
                            步骤1(Greer关键词法):先扫每张牌的核心含义——牌不单独读,以对和组形成意义
                            步骤2(Greer叙事展开):把关键词串成与问卜者情境相关的完整故事段落
                            序列规则(Greer语法):第一张左牌=主语/主题,后续牌=修饰语按"左→右"推进剧情
                            整条牌链=一个故事,从左到右/从第一位置到最后一位置依次展开
                          【逐牌解读】— 每张牌2~4句,按Greer体系:"card keywords integrated into fresh concepts according to a syntax or structure",包含:
                            ①位置名+位置short_description(语境定调该牌的"叙事角色")
                            ②核心含义(core/keywords)——重点是functional而非symbolic(Greer:the pictures are not read symbolically)
                            ③modifier_behavior修饰(每张牌都被邻牌修饰,距离越近影响越大)
                            ④与左右邻牌关系——用get_combination_with,注意方向语法:A左B右时A被B修饰
                            ⑤德传Sach/Person区分——部分牌(Bär/Storch/Hund)可兼人物两性,标注"此牌在此处读作[人/物]"
                            ⑥as_person激活时:角色出场描写(性格/在叙事中的角色/与邻牌人物的关系)
                          【组合链】— 按Greer体系:"cards modify other cards according to explicit rules; look at the cards both as a sequence(in terms of what modifies what) and also as pairs"
                            优先级:①固定组合→②功能角色→③语法→④关键词
                            序列读法: A→B→C→D(左到右)=因果链/时间线推进,B修饰A,C修饰B
                            配对读法: 每对相邻牌形成"修饰关系"(A+B读作"被B修饰的A")
                            三对交叉(Greer案例): Coffin+Bear / Bear+Man / Coffin+Man 三对交叉验证,不是线性罗列
                            核心:每对都要推动剧情/提供新信息,不是重复说同一件事
                          【跨位关系网】— mirror_target跨位共鸣(因果链对应位置)+行间/列间/对角关联(非GT牌阵仍用首尾呼应概念)
                            注意:镜像≠重复——镜像位揭示的是同一议题的"另一面",而非重复确认
                          【人物视线方向(Blickrichtung)】— 德传Große Tafel核心技法:
                            人物牌(女人29/男人28/小孩13/骑手1)的视线方向=能量流向
                            两人物相向(面对面)=好感/开放交流; 背对背=拒绝/沟通断裂
                            两人物之间的牌=这段关系的实质内容
                            GT典型格局:Herr→Herz Park←Dame=情感开放公开场合; ←Dame Ruten Herr→=冲突争执
                            非GT牌阵同样适用:首牌人物视线朝右=面向未来,朝左=回望过去
                          【牌阵结构总结】— 电荷分布(正/中/负张数+占比)+速度牌分布(fast/neutral/slow张数)+人物卡激活清单(牌名+角色)+重复花色/重复数字(若有则标注:同花色=该领域被强调;吉凶大方向:♥大吉/♦偏吉/♠中性/♣大凶,详见【雷诺曼扑克插片参考字典】;完全缺失的花色=被回避/未触及的领域;同数字=该数值主题被强调)
                          【领域标识(Signifikatoren)】— 德传按特定牌定位人生领域:Anker(35)=职业,Ring(25)=关系,Kind(13)=子女,Schiff(3)=旅行,Haus(4)=家庭,Hund(18)=友谊,Brief(27)=消息
                            解读时先看这些Signifikatorkarte出现在牌阵的哪个位置以及它们周围的牌,判断该领域的状态
                          【时间框架】— 按牌阵位置划分时间:
                            GT用四象限(行1=近未来天/周,行2=短期月,行3=中期季度,行4=长期年,Matthews法)
                            或德传日历法:36格对应月份(1-31日+5补位)或星期(1-7×3周+15补位)
                            非GT按牌序前半=过去/背景,后半=未来/发展
                            各牌speed系数修正事件节奏:fast=日/周内显现,neutral=月尺度,slow=季度/年尺度(Boroveshengra)
                          【结论】— 综合全盘后的最终判断,提炼出最核心的一条信息
                          【时间确认】— 结合时间框架的定位,用一句话告诉问卜者事态的大概节奏:牌离指示牌近=数天/周内显现,远=数月后;speed=fast=进展快,slow=要等;GT可用日历法定位到月份或星期
                          【末牌收束】— 回到牌面上来收束——用最后一对组合(C+D)或最后一张牌收束整个叙事,让回答回归卡牌本身,不飘到抽象道理上。注意:末牌权重大但不是绝对裁决,须结合全链判断
                          【建议≤3】— 不超过3条可操作建议。从 modifier_behavior 判断行动方向（negator=建议停止/释放, descriptor/amplifier=建议加强）,从组合链中友好组合=建议推进的路径,冲突组合=建议回避的领域,charge=建议的能量基调,每条要具体可执行
                          【反思问题】— 1条让问卜者自省的问题。盯着全牌阵中最矛盾的组合（冲突组合或 mirror_target 跨位张力）或 Blickrichtung 中人物背对的方向——那里藏着问卜者最该面对但还没面对的事
                          【箴言】— 一句收尾格言(源自Hechtel原版《Das Spiel der Hoffnung》每牌配一句人生箴言/格言的基因,提炼全盘最核心的教义,用牌面符号隐喻收束)
                          GT追加模块(36张时自动激活):
                            优先顺序:①指示牌→②近远距离→③落宫→④镜像→⑤骑士跳→⑥行列→⑦四角
                            四角框架: {左上=起点/初衷,右上=远景期望,左下=隐藏根基,右下=最终结算}
                            四角组合: 1+36和9+28两对角交叉验证整体叙事边界(德传Große Tafel: Eckkarten in Kombination)
                            牌阵变体: 除标准4×9外,德国传统还使用4×8+4(下方4张=当前局势主陈述,Hauptaussage zur gegenwärtigen Situation)
                            人物视线(Blickrichtung): 详见【人物视线方向】段,GT中人物卡的看向方向是关系解读的第一手线索
                            Step1内九宫格: 指示牌3×3邻接按row/col/diag分组两两组句定调
                            Step2 MOD近远法: Heart/Fish/Anchor/Cross/Tree按final_weight排序,最小=最快最强,direction(past/future)
                            Step3深挖: 仅指示牌骑士步暗线+三维镜像(horizontal=表面映像/vertical=深层真相/diagonal=命运对称)+反射(35-idx隐藏本质)
                            Step4宫位背景: 落宫改变牌义(Anchor落Rider宫≠Anchor落Child宫,牌义因宫位而变)+级联链追底层原因
                            注意 Eigenes Haus(自家): 牌落在与自己编号相同的位置时,该牌性质无法施展——如Reiter(1)落1号位=不动/无消息(Häusersystem: Karte im eigenen Haus kommt nicht zur Geltung)
                            交叉法: 指示牌所在整行+整列同轴叙事主线
                            扑克牌(详见【雷诺曼扑克插片参考字典】):GT特有—36张全局花色分布统计,哪些花色占比过高/过低,结合宫位判断各领域能量强弱;同数字多张出现可抓同频共振(A/6/7/8/9/10含义见上)
                          数据使用: 必须(core/keywords/combination_rules/modifier_behavior/line_reading) | 语气润色(timing) | 激活(as_person抽到人物卡时展开角色描写,不激活则隐藏) | 附录(playing_cards全牌阵可用,GT单独展开更详细) | 禁止(_data裸访问)
                          引擎输出=硬骨架,LLM只在其上叙事不篡改索引/权重/方向等事实字段
                          核心原则:
                            ① 先整体后局部:先给一句话结论,再展开逐牌细节
                            ② 语法法则:相邻牌=名词+形容词组合(Greer课程原话:interpretative nouns and adjectives in card combinations)——左牌=名词/主语(谁/什么),右牌=形容词/修饰语(怎么样/结果);整条牌链从左到右读成一句话
                            ③ 配对法则:牌不单独读,以对和组形成意义(Greer:interpreting Lenormand through pairs and combinations);每对都要推进剧情不重复
                            ④ 语境法则:同一张牌在不同牌阵位置讲不同故事——位置=场景,牌=角色;Anchor在职业位vs感情位含义不同;落宫改变牌的"叙事角色"
                            ⑤ 线索法则(GT):行=叙事的章节(第1行:开场,第2行:发展,第3行:转折,第4行:结局);列=贯穿同一主题;对角线=隐藏暗线
                            ⑥ 速度法则:牌距指示牌越近=影响越直接越快,越远=越长期;speed系数修正节奏(fast=日/周,slow=季度/年)
                            ⑦ Greer:每张牌都是故事的一个角色/事件,功能含义优先于象征含义(the pictures are not read symbolically)
                            ⑧ 引擎输出=硬骨架,LLM负责叙事
                        ╚══════════════════════════════════════════════════════════╝

                        【雷诺曼引擎调度】from lenormand_engine import LenormandFateEngine as FE
                          🟢必开(牌阵触发即用):
                            FE.parse_karmic_mirrors(spread.positions,items) — 所有有mirror_target的牌阵: line-3/5/7/9/cross/relationship/box-3x3/astrological-houses
                            FE.parse_portrait_3x3_cage(items, spread_id) — box-3x3/GT 钉四角(十字心仅box-3x3)
                          🔵GT专属(Grand Tableau):
                            master=FE.parse_grand_tableau_master_mode(items,spread.positions,gender)
                            ← 返回Step1-4结构: step1_inner_ring(内九宫格定调) → step2_mod_ranking(MOD权重排序,含speed+direction) → step3_deep_dive(骑士步/镜像/反射,仅指示牌) → step4_house_background(落宫+级联链)。LLM必须按此顺序使用数据。
                          🟣工具箱(AI按需取):
                            FE.get_gt_mirrors(idx) — GT三维镜像(水平/垂直/对角), 返回{方向: 索引}用items[索引].card_name取牌解读
                            FE.get_reflection(idx) — GT反射(编号对调35-idx),独立调用,数值同get_gt_mirrors的diagonal
                            FE.get_inner_9_ring(idx) — 任意牌的3×3邻接(截断,角落少于8张),返回{ring/row/col/diag:[索引]}
                            FE.get_intersection(idx) — 任意牌所在整行+整列(不含自身),返回{row/col:[索引]}
                            FE.calculate_mod(sig_idx,topic_indices,items) — 主题牌权重排序,含speed权重+direction(past/future)
                            FE.calculate_knights_move(sig_idx) — 任意牌的骑士跳暗线扫描, 返回[索引列表]用items[索引].card_name取牌解读

                            FE.calculate_house_chaining(items,card_id) — 宫位级联(场景:追问原因)

                            FE.calculate_counting_pulse(items,start_idx,step=9) — 古法步进(场景:年运)
                          规则: 引擎输出是硬骨架,LLM只在其上叙事不篡改
                       ╚══════════════════ 雷诺曼 ═════════════════╝

【灵数学/卡巴拉/数秘】 (JS Kaabalah引擎, 1.3MB, 零随机)

⚠️ 读日期用的是 local calendar getter，构造时用 local noon: new Date(1990, 5, 15, 12) 避免时区跳日。不可传 {year,month,day}
╔══════════════════ 速览 ══════════════════╗
║ 生命灵数 + 流年 + 挑战 + 斐波那契        ║
║ 希伯来 Gematria (字母数值)               ║
║ Ifá 非洲占卜 (Odu)                      ║
║ 生命之树 (11球体 + 22路径 + 777全对应)    ║
╚══════════════════════════════════════════╝
var d = new Date(1990, 5, 15, 12);  // 6月=5, local noon
【灵数 — 6 个核心】
Kaabalah.calculateKaabalisticLifePath(d)
→ {parts:{day:"15",month:"06",year1:"19",year2:"90"},
reducedParts:{reducedDay:6,reducedMonth:6,reducedYear1:1,reducedYear2:9},
syntheses:{dayMonthSynthesis:66,yearSynthesis:19,
reducedDayMonthSynthesis:3,reducedYearSynthesis:1,finalSynthesis:31},
lifePath:{reducedValue:4,reductionSteps:[31,4]},
personalMythologyNumbers:[6619,31,4]}
Kaabalah.calculateStraightAcrossReductionLifePath(d)
→ {dayEnergy:{reducedValue:6,reductionSteps:[15,6]},
monthEnergy:{reducedValue:6,reductionSteps:[6]},
yearEnergy:{reducedValue:1,reductionSteps:[1990,19,10,1]},
lifePath:{reducedValue:4,reductionSteps:[15061990,31,4]}}
Kaabalah.calculatePersonalYear(d, new Date()) → {reducedValue, reductionSteps}
Kaabalah.calculateChallenges(d)
→ {day, month, year, mainChallenge, subChallenge1, subChallenge2}
Kaabalah.calculateFibonacciCycle(d, new Date())
→ {currentAge, cycle1~7: {reducedValue, reductionSteps}}
Kaabalah.getDateEnergies(d) → {dayEnergy:{reducedValue,reductionSteps}, monthEnergy:{reducedValue,reductionSteps}, yearEnergy:{reducedValue,reductionSteps}}
辅助: Kaabalah.isMasterNumber(11)→true (22)→true (33)→true (44)→true (5)→false
Kaabalah.reduceToSingleWithSteps(31)  → {reducedValue, reductionSteps}
Kaabalah.reduceToSingle(31)           → 直接返回数字
Kaabalah.calculatePersonalMonths(d, personalYear, new Date())  → {personalMonths:[13个月],currentPersonalMonthIndex}  ⚠️ personalYear需先由calculatePersonalYear得到
Kaabalah.calculatePersonalCycles(d, today, firstName)  → {personalYear,personalPeriods,personalMonths,currentAge,lifePath,soulNumber?}  ⚠️ 需传firstName(如"John")
【Gematria — 2 个核心】
Kaabalah.calculateGematria("chiron")
→ {vowels:{originalSum:16, reductionSteps:[16,7], finalValue:7},
consonants:{originalSum:1200, reductionSteps:[1200,3], finalValue:3},
synthesis:{originalSum:1216, reductionSteps:[19,10,1], finalValue:1},
includedLetters:[{latinLetterId, value, hebrewCharacter, hebrewLetterId, isVowel}, ...]}
// chiron → Ch=ש=300, I=י=10, R=ר=200, O=ו=6, N=ן=700  元音I+O=16→7  辅音Ch+R+N=1200→3
Kaabalah.calculateGematria("love")
→ vowels:11→2  consonants:36→9  synthesis:47→20→2
L=ל=30, O=ו=6, V=ו=6, E=ה=5
Kaabalah.calculateGematria("aries")
→ vowels:16→7  consonants:260→8  synthesis:276→24→6
A=א=1, R=ר=200, I=י=10, E=ה=5, S=ס=60
Kaabalah.reverseGematria(111) → {results:[], hasMore, totalFound}
(字典可能未加载单词表, 结果可能为空)
支持: 英文单词/希伯来音译/星座名/行星名 均可传入 calculateGematria
【Ifá — 1 个】
Kaabalah.calculateOdu(d)
→ {leftNumbers:[1,0,1,9], rightNumbers:[5,6,9,0],
north:11, south:2, east:13, west:8, center:7}
【生命之树 — 4 个核心】
Kaabalah.buildKaabalisticMapData({numerology: d})
→ {spheres:[{id,name,hebrew,number,meaning,position} ×11],
paths:[{id,name,from,to,hebrew} ×22],
markers:[], sphereMarkers:{}, pathMarkers:{},
countsById:{}, itemConnections:{}}
Kaabalah.buildKaabalisticMapData({astrology: {
planets: [{name:"Sun", zodiacPosition:{sign:{name:"Gemini"}}}, ...],
nodes: [{name:"North Node", sign:"Aquarius"}, ...],
houses: {ascendant:{sign:{name:"Virgo"}}, mc:{sign:{name:"Gemini"}},
ascmc:{vertex:{sign:{name:"Leo"}}}}
}})   ⚠️ sign 必须是对象 {name:"Gemini"} 不是字符串
数据查询 (按需):
Kaabalah.SPHERES_DATA["KETHER"]   → {name,hebrew,number,meaning,colors,...}
Kaabalah.LURIANIC_PATHS["11"]     → {from:"Kether",to:"Chokhmah",letter:"Aleph",...}
Kaabalah.HEBREW_LETTERS_DATA["Aleph"] → {value:1,symbol:"א",meaning:"Ox",...}
Kaabalah.FOUR_WORLDS → ["ATZILUTH","BRIAH","YETZIRAH","ASSIAH"]
Kaabalah.FOUR_WORLDS_DATA["ATZILUTH"] → {name,meaning,...}
Kaabalah.SPHERES["KETHER"] → {id,name,number,...}
Kaabalah.GematriaData → {hebrewLetters:{}, latinLetters:{}, ...}
11球体: Kether→Chokhmah→Binah→Daath→Chesed→Geburah→
Tiphareth→Netzach→Hod→Yesod→Malkuth
【塔罗→卡巴拉 777 全对应】
大牌(22): 序号→路径→字母
0=Fool(11,Aleph) 1=Magician(12,Beth) 2=HighPriestess(13,Gimel)
3=Empress(14,Daleth) 4=Emperor(15,Heh) 5=Hierophant(16,Vau)
6=Lovers(17,Zain) 7=Chariot(18,Cheth) 8=Strength(19,Teth)
9=Hermit(20,Yod) 10=Wheel(21,Kaph) 11=Justice(22,Lamed)
12=HangedMan(23,Mem) 13=Death(24,Nun) 14=Temperance(25,Samekh)
15=Devil(26,Ayin) 16=Tower(27,Peh) 17=Star(28,Tzaddi)
18=Moon(29,Qoph) 19=Sun(30,Resh) 20=Judgement(31,Shin)
21=World(32,Tau)
数字牌(40): Ace=1(Kether) ... 10(Malkuth)
牌组→四世界: Wands=Atziluth, Cups=Briah, Swords=Yetzirah, Pentacles=Assiah
宫廷牌(16): King→Chokmah, Queen→Binah, Knight→Tiphareth, Page→Malkuth
查法: Kaabalah.SPHERES[name] + Kaabalah.FOUR_WORLDS[world]
+ HEBREW_LETTERS_DATA[letter] + LURIANIC_PATHS[pathNum]
╔══════════════════ 参数坑 ══════════════════╗
║ 日期: local noon构造 new Date(y,m-1,d,12) ║
║ chart映射: sign是{name}对象 不是字符串      ║
║ planets: 数组 不是对象                      ║
║ calculatePersonalMonths 需先有personalYear  ║
║ calculatePersonalCycles 需传firstName       ║
║ reverseGematria 单词库可能空                 ║
╚═════════════════════════════════════════════╝
其余用 dir(Kaabalah) 自探索: getCanonicalTree / getTreeLayout / getTreeTopology /
getAstrologyTreeMarkers / getGematriaTreeMarkers / getNumerologyTreeMarkers /
getKaabalisticCorrespondenceTargets / TreeOfLife / TreeTopology 类,
常量: MASTER_NUMBERS / TREE_SPHERE_IDS / TREE_PATH_IDS 等。
  【农历/干支/天文】
  农历/黄历/择日      →  cnlunar(Python)            ← lunar_python, Lunar(JS引擎)  日期即可
  公历农历转换/八字     →  lunar_python(Python)       ← Lunar(JS引擎,可离线算Solar/Lunar/EightChar/DaYun/JieQi)  日期即可
  二十八宿/宿曜       →  Lunar.getTwentyEightMans()  ← cnlunar                  日期/生日均可
  建除十二神/黄道黑道  →  cnlunar                    ← lunar_python            日期即可
  吉神凶神/彭祖百忌    →  cnlunar                                               日期即可
  值年太岁/本命太岁    →  cnlunar/lunar_python        ←                         日期即可
  生肖/干支/合婚/神煞   →  bazi_china                ← lunar_python            生日可选
  bazi_china 是纯 Python 静态库(无pip,源码在app/src/main/python/bazi_china/)。调法:
    import sys; sys.path.insert(0, 'app/src/main/python')
    from bazi_china import ganzhi, datas, shengxiao, sizi, yue
    ganzhi.Gan[:10]          → ['甲','乙','丙','丁','戊','己','庚','辛','壬','癸']
    ganzhi.Zhi[:12]          → ['子','丑','寅','卯','辰','巳','午','未','申','酉','戌','亥']
    datas.shengxiaos[zhi]    → 该地支的生肖名 (如datas.shengxiaos['子']→'鼠')
    shengxiao.output(des,zhi,key)→ 打印生肖合/冲/刑/害关系 (shengxiao.py CLI)
    生肖合婚/配对查询 → 单独调用 shengxiao.output，不需要先排八字
      用户问\"属X和什么合/冲\"时调，入参: zhi=生肖对应地支(鼠→子 牛→丑…), key=合/六/会/冲/刑/害/破
      例: shengxiao.output('', '子', '合') → '猴龙'   (子与申猴辰龙三合)
          shengxiao.output('', '子', '冲') → '马'     (子午冲)
    【luohou — 择日/风水/罗猴/九宫飞星】
      它能做什么:
        luohou.yearly_nine_stars(year) → 年九宫飞星: 返回JiuFeiXing对象, 用属性名取方位 jfx.东 .南 .西 .北 .中 .东北 .东南 .西南 .西北
        luohou.monthly_nine_stars(年支) → 月九星: 返回{月份:星名}
        luohou.daily_nine_stars(lunar对象) → 日九星
        luohou.get_hou(d, xiazhi, dongzhi) → 每日择日(三参都是datetime.date)
        luohou.get_jizhu(年干,年支) → 太岁压祭主
        luohou.jiuxings_dsp → 九星吉凶说明文字
      什么时候调它:
        "今天日子怎么样"/"搬家/动土/嫁娶/开工选日子"
          → from datetime import date; from bazi_china import luohou; from lunar_python import Lunar
          → table=Lunar.fromYmd(2024,1,1).getJieQiTable()
          → xz=date(table['夏至'].getYear(),table['夏至'].getMonth(),table['夏至'].getDay())
          → dz=date(table['DONG_ZHI'].getYear(),table['DONG_ZHI'].getMonth(),table['DONG_ZHI'].getDay())
          → luohou.get_hou(date(2024,6,21), xz, dz)  # 直接print输出
        "今年什么方位吉利"/"财位在哪"/"病符在哪"
          → jfx=luohou.yearly_nine_stars(2024); jfx.东 / jfx.南 / jfx.西 / jfx.北 / jfx.中 / jfx.东北 / jfx.东南 / jfx.西南 / jfx.西北
        "这个月飞星到哪" → luohou.monthly_nine_stars('子')
        "能动土吗/能开工吗" → luohou.get_jizhu(年干,年支) + get_hou()查岁破
    sizi.summarys            → 120项四柱解盘字典 (ai自己探索sizi.summarys.keys()查看可用键)
    yue.months[月柱]         → 流月详解 (键为月柱干支如'甲寅', 从lunar_python EightChar.getMonth()取值)
    神煞/纳音/空亡/命宫/日主/调候/建禄: datas.day_shens/month_shens/year_shens/g_shens/nayins/empties/minggongs/rizhus/jinbuhuan/jianlus
    天干地支/藏干十神/干支关系: ganzhi.gan_desc/zhi_desc/ten_deities/gan_hes/zhi_6hes/zhi_3hes/zhi_chongs/zhi_xings/zhi_haies/zhi_poes
    注: bazi_china 里只有 bazi.py(2549行)是CLI工具, 其余模块(ganzhi/datas/sizi/yue/shengxiao/luohou)全是库函数可以直接 import 调
  节气和天文          →  lunar_python               ← cnlunar                  日期即可

【查询路由】只查单项数据不排盘时用。复杂库(ichingshifa/kinliuren/taixuanshifa等)必须先用 dir() 探索全部方法，不得盲调试错：
  lunar_python (215+) →  l = Lunar.fromYmd(2026,6,16); print(dir(l))
  cnlunar             →  import cnlunar; print(dir(cnlunar.LunarDate))
                        注: cnlunar.Lunar() 构造必须传 datetime 对象(含hour)，不能传 date — 传date报 'date' object has no attribute 'hour'
  ichingshifa         →  from ichingshifa import Iching; i = Iching()
      i.qigua_now()                          当前时间起卦
      i.qigua_time(y,m,d,h,minute)           指定时间起卦
      i.qigua_manual(y,m,d,h,minute,gua)     手动爻值起卦(gua="697887")
      i.bookgua_details(yao=None)            兼断详细解
      i.decode_gua(gua, daygangzhi=None)     解本卦
      i.decode_two_gua(bengua,ggua,daygangzhi=None)  解本变卦
      ⚠️ 全部是 Iching() 实例方法，不是模块级函数
  meihua_yi           →  import meihua_yi
      meihua_yi.qigua_coin(coin_results=None)          摇钱起卦, 返回 (主爻,动爻,爻详)
      meihua_yi.qigua_time(dt=None)                    时间起卦, 返回同上
      meihua_yi.compute_hexagrams(main_lines, moving_indices)
         返回 {main,mutual,changed,ti,yong,moving_indices}
         ti/yong 体用已内建: result['ti']={name,symbol,element}
         ⚠️ 不存在 analyze_ti_yong 函数,体用由 compute_hexagrams 直接返回
      meihua_yi.format_hexagram_text(lines, moving_indices)  格式化卦象文本(供解卦用)
      meihua_yi.get_gua_name(lines)                    查64卦名
      GUA_NAMES                                        64卦字典
      BAGUA         →  {(1,1,1):{name:'乾',symbol:'☰',element:'金'}, ...}
      XIAN_TIAN     →  {1:(1,1,1), 2:(1,1,0), 3:(1,0,1), ...}
      用户说"梅花起卦""数字起卦""时间起卦"时调, 无需出生

  kinliuren           →  kinliuren.Liuren(节气, 农历月, 日干支如'甲子', 时干支如'甲子')
      构造后调 .result(0) 排盘(返回课体/三传/神将等) .sike_dict()查四课
      .moongeneral()月将 .dayhorse()驿马
      参数从 lunar_python 取: EightChar.getDayGan()+getDayZhi()=日干支, 时干支同理
  taixuanshifa        →  from taixuanshifa import Taixuan; t = Taixuan(y,m,d,h)
      t.pan_from_code(zhou)              按code排盘(如 "2312")
      t.pan()                            排当前盘
      t.qigua_number()                   起玄数
  jingjue             →  import jingjue; jingjue.qigua() 无参, 返回[卦辞] (先秦占卜, 无需出生)
      gua_dict(16卦)可探索, secrets含内部数据
      用户说"卜一卦""荆诀起卦"时调
  ⚠️ qigua() 是模块级函数，jingjue.jingjue 不存在
  ziwei_paipan        →  ziwei_paipan.by_solar("1990-6-15", 7, "male") 返回 AstrolabeResult
      参数: solar_date(公历日期), time_index(时辰0-12), gender("male"/"female"), fix_leap=True
      返回值(astrolabe):
        基础: .five_elements_class(五行局) .sign(星座) .zodiac(生肖)
              .soul_master(命主) .body_master(身主)
              .lunar_date(农历) .chinese_date(干支纪年) .time_range(时辰)
        年柱: .heavenly_stem_of_year .earthly_branch_of_year
        命身宫: .heavenly_stem_of_soul .earthly_branch_of_soul
              .soul_index .body_index
              .earthly_branch_of_soul_palace .earthly_branch_of_body_palace
        紫府: .ziwei_index .tianfu_index
        十二宫: .palaces[12] ← 每个: {index,name,heavenly_stem,earthly_branch,
                    is_soul,is_body,is_original_palace,decadal,ages}
        主星: .major_stars[14] ← 每个: {name,index,type,system,brightness,mutagen}
        辅星: .minor_stars[14] ← 每个: {name,index,type,brightness,mutagen}
        杂星: .adjective_stars[38] ← 每个: {name,index,type}
        四化: .mutagens ← [{name,index,mutagen}]
        大限: .horoscopes ← [{index,range:[24,33],heavenly_stem,earthly_branch}]
        12神: .changsheng12 .boshi12 .suiqian12 .jiangqian12
      映射: 星在几宫 → star['index'] → palaces[star['index']]['name']
            例: {name:'紫微',index:10} → palaces[10]['name']='命宫' → 紫微在命宫
      配置: iztro_configure(day_divide='forward', year_divide='normal', algorithm='default')
      其他: by_lunar("1990-5-23",7,"male",is_leap_month=False)  农历排盘
            rearrange_astrolable(result,天干,地支,timeIndex)    天盘/人盘/地盘重排

【输入说明】不是所有排盘都需要生日：
  • 需生日(含时辰) — 八字/紫微
  • 需生日(不含时辰也可) — 生肖/大六壬/二十八宿
  • 仅需日期(不需出生) — 黄历/择日/建除/太岁/节气/农历转换
  • 无需任何出生 — 六爻(需起卦数)/梅花(需数字)/太玄/荆诀/塔罗

【双引擎对照规则】⚠️ 易经"初筮告，再三渎"——同一问题只能起一卦。调用前先 dir() 确认函数存在。
  六爻对照: AI 先调 JS IchingShifa.dayan() 取一次随机得爻值如"697887",
            再调 Python from ichingshifa import Iching; i=Iching(); i.bookgua_details() 或用 i.qigua_manual(y,m,d,h,minute,"697887") 同爻值排盘,
            两引擎同一卦各自解盘，AI 对比两套解读。异数起两卦 = 违章。
  太玄对照: AI 先调 JS TaixuanLib.generate() 得 {code:"2312",gua:{...}},
            再调 Python Taixuan(y,m,d,h).pan_from_code("2312") 同首排盘。
  紫微对照: 纯确定性算法，同一输入→同一天干地支=同一命盘。AI 可同时调
            Iztro.astro.bySolar(date,timeIndex,gender) + ziwei_paipan.by_solar(date,timeIndex,gender)
            两引擎各自排盘（无需随机连线），对比命宫/身宫/五行局/主星位置是否一致，
            不一致处即为日历层差异（闰月/节气/干支计算）。ZiweiNihai 也用 iztro 排盘数据一致，仅亮度/地支/四化字段命名不同。
  不影响效率: 仍调两次引擎，第一次随机+排盘，第二次仅排盘(无随机开销)，总耗时几乎不变。

【输出】排盘结果直接用 print() 输出文字，模型基于真实数据解读。


【引擎区别速查】AI 回答用户"哪个好/有什么区别"时用:
  • 紫微: ziwei_paipan(Python,iztro port) vs Iztro(JS,⭐3841原版) vs ZiweiNihai(JS,倪海厦+古籍)
  • 奇门: QimenEngine(JS,7局法×4流派+断语) — Python侧C扩展已删,仅JS
  • 六爻: ichingshifa(Python,大衍1种) vs IchingShifa(JS,6种起卦)
  • 太玄: taixuanshifa(Python,蓍法1种) vs TaixuanLib(JS,4种起卦)
  • 西洋占星: NatalEngine(解读+文本,唯一输出) → Caelus(格局+尊贵+推运+12宫位+赤纬+7点)

NatalEngine 星历精度与 Astronomy 同级 (Moon:0.00″ vs VSOP87)
Astronomy 仅需要 NASA 级精度时选配
  • 印度吠陀: NatalEngine(Rasi+27宿+Dasha+文本) → Caelus(26Yoga+7分盘+Ashtottari+Yogini+Kemadruma)
  • 人类图+基因钥匙: NatalEngine 唯一
  • 卡巴拉/灵数/Gematria/Ifá: Kaabalah 唯一
【JS 引擎调用】首次使用需 eval_javascript(action='load', library='xxx') 加载库，后续直接 eval。对照模式→JS先随机→提取关键值→Python同值排盘。库名: qimen-engine | ziwei-nihai | iching-shifa-engine | taixuan-engine | lunar-engine | astronomy-engine | horoscope-engine | kaabalah-engine | caelus-engine | iztro-engine | natalengine-engine(西洋+吠陀+人类图) |  | liuren-engine(大六壬)
  QimenEngine → eval_javascript(library='qimen-engine', code='QimenEngine.generate({...})')
      可用type:
        {type:"rijia", year:2026, month:6, day:19}       → 日家,自包含(推荐)
        {type:"nianjia", year:2026}                       → 年家,自包含
        {type:"yuejia", year:2026, month:5}                → 月家,自包含(节气月)
        {type:"shijia", juMethod:"chaibu", baseChart:日家结果} → 时家,需先调日家拿baseChart
      返回 QimenChart: palaces(9宫数据), zhiFuStar/zhiShiDoor, dun/juNumber/yuan, fourPillars, kongWang
  ZiweiNihai  → eval_javascript(library='ziwei-nihai', code='ZiweiNihai.generateChart({year:1990,month:6,day:15,hour:7,gender:"male"})')
      参数: year(公历年), month(公历月1-12), day(公历日), hour(时辰索引0=子~11=亥),
            gender("male"/"female"), name?, province?, city?, longitude?(真太阳时)
      返回 ZiweiChart — 源码: types.ts 90行:
        .birthInfo          {year,month,day,hour,gender}
        .lunarInfo          {lunarYear,lunarMonth,lunarDay,yearStem,yearBranch,isLeapMonth}
        .mingGongBranch     (命宫地支索引0-11)
        .shenGongBranch     (身宫地支索引0-11)
        .wuxingJu           (五行局数字2-6)
        .wuxingJuName       (五行局名称"水二局")
        .ziweiPos           (紫微星宫位索引)
        .palaces[12]        每个: {branch(地支),stem(天干),name(宫名),stars[](星曜数组),
               daXianAge([start,end]),isCurrentDaXian,isMingGong,isShenGong,
               selfSihua[](宫干自化),oppositeBranch(对宫),isEmpty(空宫),
               borrowedFromBranch,borrowedFromName,borrowedStars[](借星)}
          Star: {name,type:major|minor|lucky|sha,siHua:禄权科忌,brightness:bright|normal|dim}
        .daXians[]          每个: {startAge,endAge,palaceBranch,palaceName}
        .currentAge         (当前年龄)
        .currentDaXianIndex (当前大限索引)
      其他导出(源码 lib/nihai + lib/classics):
        .getLunarInfo(year,month,day)           → 农历转换
        .NI_HAIXIA_BIO                          → 倪海厦传记全文
        .SANJI_CATEGORIES                       → 三纪分类(天/地/人)
        .TIANJI_EPISODES .TIANJI_QUOTES         → 天纪24集+语录
        .HEXAGRAMS                              → 六十四卦详解
        .FENGSHUI_ENTRIES                       → 风水条目
        .RENJI_MODULES .ACU_EXPERIENCES         → 人纪针灸+经方
        .ALL_BOOKS                              → 古籍库(骨随赋/全集/全书)
        .getBookBySlug(slug)                    → 按slug取古籍
        .getChapter(bookSlug, idx)              → 按章节取内容
        .getParagraphById(id)                   → 按段落ID取原文
        .searchKeyword(keyword)                 → 古籍全文搜索
      流派: 倪海夏天纪体系(三合派+象数派+九星派+河洛数理), 盘面数据与 Iztro 一致, 仅亮度(bright/normal/dim)/地支数字/四化(siHua)命名不同
  IchingShifa → eval_javascript(library='iching-shifa-engine', code="IchingShifa.dayan() 又 IchingShifa.lueshifa() 又 IchingShifa.timeQiGua(2026,6,19,14,5,19,'午','午') 又 IchingShifa.manualQiGua('697887') 又 IchingShifa.threeNumberQiGua(123,456,789) 又 IchingShifa.numberArrayQiGua([3,7,2,9,1,5],0); IchingShifa.decodePan(yao,{year,month,day,hour})排盘")
  TaixuanLib  → eval_javascript(library='taixuan-engine', code='TaixuanLib.generate() 又 TaixuanLib.generateByCoins() 又 TaixuanLib.generateByDice() 又 TaixuanLib.generateByShi() 又 TaixuanLib.generateByNumber(5678); 返回{code:"2312",gua:{...}}
  Lunar (JS)  → eval_javascript(library='lunar-engine', code='Lunar.Solar.fromDate(new Date(2026,5,19)) 又 Lunar.Lunar.fromDate(d) 又 Lunar.EightChar.fromLunar(lunar) 又 Lunar.DaYun(...) 又 Lunar.JieQi.getJieQi(2026)
  Astronomy   → eval_javascript(library='astronomy-engine', code='Astronomy.SunPosition(new Date(2026,5,19,14,0,0)) 又 Astronomy.GeoVector(Astronomy.Body.Sun,new Date(2026,5,19,14,0,0),false) 又 Astronomy.SearchRiseSet(Astronomy.Body.Sun,new Astronomy.Observer(39.9,116.4,0),1,new Date(2026,5,19),1) 又 Astronomy.SearchLunarEclipse(new Date(2026,5,19)) 又 Astronomy.Seasons(2026) 又 Astronomy.MoonPhase(new Date(2026,5,19))  (零随机,VSOP87精度)
  HoroscopeJS → eval_javascript(library='horoscope-engine', code='new HoroscopeJS.Horoscope({origin:new HoroscopeJS.Origin({year:2026,month:5,date:19,hour:14,minute:0,latitude:39.9,longitude:116.4}),houseSystem:"placidus",zodiac:"tropical"})  (零随机,Kepler精度+7宫位制)
Kaabalah    → eval_javascript(library='kaabalah-engine', code='Kaabalah.calculateKaabalisticLifePath(new Date(Date.UTC(1990,5,15)))')

又 calculatePersonalYear(birth, new Date())  又 calculateChallenges(birth)
又 calculateFibonacciCycle(birth)  又 getDateEnergies(birth)
又 calculateGematria("word")  又 reverseGematria(111)
又 calculateOdu(birth)  又 buildKaabalisticMapData(birth)
又 isMasterNumber(n)  又 reduceToSingleWithSteps(n)
(零随机,纯JS; 塔罗走arcanite+777表,查SPHERES/FOUR_WORLDS/HEBREW_LETTERS/LURIANIC_PATHS)
Caelus(西洋+吠陀) → eval_javascript(library="caelus-engine", code="var e=new Caelus.Engine(Caelus.embeddedData); var jd=Caelus.isoToJd('1990-06-15T12:00:00+08:00'); e.chartAt(jd,39.9,116.4,{})")
又 lots(e,jd,lat,lon) 又 firdariaAt(e,jd,targetJd,lat,lon) 又 primaryDirections 又 solarArc
又 detectYogas(e,jd,lat,lon) 又 vargaAt(e,jd,9) 又 ashtottariAt 又 yoginiAt
又 declinationAspects(e,bodies,jd,orb) 又 outOfBounds(e,body,jd)
(零依赖VSOP87D,231函数,先new Engine; ⚠️varga用数字9不是"D9"; lots不是hermeticLots)
      ⚠️ chart(y,mo,d,h,mi,s,lat,lonEast,opts) 位置参数,不是getBirthChart({})
      ⚠️ varga/vargaAt/vargaChart 的n是数字不是字符串: vargaAt(e,jd,9) 而非 vargaAt(e,jd,"D9")
      ⚠️ compositeLongitudes(e,jdA,jdB,bodies,zodiac) 需要engine+两个jd,不是chart对象
      ⚠️ hermeticLots(asc,day,sun,...) 需9个裸角度 → 用 lots(e,jd,lat,lonEast,zodiac) 替代
      ⚠️ hasAspect/hasPlacement/hasVarga 等柯里化: hasAspect({a:"sun",b:"mars",kind:"square"})(ctx)
NatalEngine(西洋+吠陀+人类图) → eval_javascript(library='natalengine-engine', code='NatalEngine.calculateAstrology("1990-06-15",12,8,39.9,116.4)') → {bigThree,summary,sun,moon,rising,midheaven,balance,planets,nodes,allAspects}

吠陀: NatalEngine.calculateVedic(date,hour,tz,lat,lng) → {moonSign,planets,dasha}
人类图: NatalEngine.calculateHumanDesign(date,hour,tz) → {type,authority,centers,channels}
基因钥匙: NatalEngine.calculateGeneKeys(hdResult)  ← 参数是HD结果不是日期
合盘: NatalEngine.compareAstrology(chartA,chartB)
(纯JS,VSOP87精度与Astronomy同级Moon误差0.00″)
  Iztro(紫微⭐3841) → eval_javascript(library='iztro-engine', code="Iztro.astro.bySolar('1990-6-15',7,'male')")
      返回 FunctionalAstrolabe — 原版 iztro API v2.5.8 (iztro.com):
        .palaces[12] 或 .palace(i)                         → 十二宫(0命宫~11兄弟宫)
        .surroundedPalaces(i)                               → 三方四正(本宫/对宫/财帛/官禄)
        .star(sName)                                        → 按名称找星曜实例
        .horoscope(date?,timeIndex?)                        → 大限推算(decadals+ages)
        .soul / .body                                       → 命主星/身主星名称
        .fiveElementsClass / .sign / .zodiac                → 五行局/星座/生肖
        .fourPillars / .lunarDate / .chineseDate            → 四柱/农历日/干支日
        .timeRange / .time / .solarDate                     → 时辰/时间/阳历
        .earthlyBranchOfSoulPalace / .earthlyBranchOfBodyPalace → 命身宫地支
      单宫: .palace(i).has(["紫微","天机"])                  → 本宫是否含某星(全含)
            .palace(i).hasOneOf(["紫微","天机"])              → 本宫是否含任一
            .palace(i).isEmpty()                             → 是否空宫
            .palace(i).hasMutagen("禄")                      → 本宫是否有四化
            .palace(i).fliesTo("子女宫","化禄")               → 本宫是否飞化到目标宫
            .palace(i).selfMutaged("化权")                    → 本宫是否自化
            宫位属性: .index .name .isBodyPalace .isOriginalPalace
                     .heavenlyStem .earthlyBranch
                     .majorStars .minorStars .adjectiveStars  (星数组,每个含.name+.brightness+.mutagen)
                     .changsheng12 .boshi12 .jiangqian12 .suiqian12
                     .decadal [{range,heavenlyStem,earthlyBranch}] .ages[]
      三方四正: .surroundedPalaces(i).have(["紫微"])          → 三方四正全含
            .surroundedPalaces(i).haveOneOf(["紫微"])          → 三方四正任一
            .surroundedPalaces(i).haveMutagen("禄")           → 三方四正有化禄
            四宫: .target .opposite .wealth .career
      配置: Iztro.astro.config({dayDivide:"forward",yearDivide:"normal",algorithm:"default"});
      农历盘: Iztro.astro.byLunar("1990-5-23",7,"male",false)
      (零随机,纯确定性算法)
  返回 JSON，AI 基于真实数据解读。


统一引擎 arcanite-unified 已内置全部塔罗数据:
      dc.description          → {waite原文, tk_en, tk_zh} 画面描述
      dc.get_waite_meaning(o) → Waite原版正逆位占卜意义
      dc.get_tk_meaning(o,l)  → TarotKit双语正逆位意义
      dc.core_meanings        → 含 waite_meaning + tk_meaning_{en,zh} + tk_core_keyword_{en,zh}
      dc.reading_aspects      → 5层阅读(currentSituation/innerState/rootCause/development/advice)
      dc.contextual_meanings  → 4语境(love/work/interpersonal/others)
      所有字段正逆位双语完整, 0额外文件 0JS引擎调用
      无内置牌阵 — 搭配 arcanite 牌阵系统确定位置
      画面描述+占卜意义(统一引擎arcanite-unified内置, dc.description / dc.get_waite_meaning):
        cw['desc'] / cw['meaning_up'] / cw['meaning_rev']  | 详见互补模式 STEP 4

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
"""

# ── Chaquopy fix: executor replaces random.Random.__init__ with restored_init
# but doesn't inject random._traced_calls. secrets.SystemRandom() (used by
# arcanite, jingjue, taixuanshifa, ichingshifa, meihua_yi) hits:
#   AttributeError: module 'random' has no attribute '_traced_calls'
# This runs before any imports that touch random/secrets.
import random as _random
if not hasattr(_random, '_traced_calls'):
    _random._traced_calls = []

import sys
import json
import os
from io import StringIO
import traceback

# Bridge to Android services - set from Kotlin via execute() parameter
_bridge = None


# ============================================================
# Bridge wrapper functions
# ============================================================

def query_knowledge_base(query, limit=10):
    if _bridge:
        try:
            return _bridge.queryKnowledgeBase(query, limit)
        except Exception as e:
            return f"Bridge error: {e}"
    return "Bridge not available"

def add_knowledge_entry(title, content, assistant_id=None):
    if _bridge:
        try:
            return _bridge.addKnowledgeEntry(title, content, assistant_id)
        except Exception as e:
            return f"Bridge error: {e}"
    return "Bridge not available"

def list_knowledge_entries(limit=20):
    if _bridge:
        try:
            return _bridge.listKnowledgeEntries(limit)
        except Exception as e:
            return f"Bridge error: {e}"
    return "Bridge not available"

def list_conversations(limit=10):
    if _bridge:
        try:
            return _bridge.listConversations(limit)
        except Exception as e:
            return f"Bridge error: {e}"
    return "Bridge not available"

def get_conversation_messages(conversation_id, limit=50):
    if _bridge:
        try:
            return _bridge.getConversationMessages(conversation_id, limit)
        except Exception as e:
            return f"Bridge error: {e}"
    return "Bridge not available"

def get_app_info():
    if _bridge:
        try:
            return _bridge.getAppInfo()
        except Exception as e:
            return f"Bridge error: {e}"
    return "Bridge not available"

def list_assistants():
    if _bridge:
        try:
            return _bridge.listAssistants()
        except Exception as e:
            return f"Bridge error: {e}"
    return "Bridge not available"

def get_assistant_settings(assistant_id):
    if _bridge:
        try:
            return _bridge.getAssistantSettings(assistant_id)
        except Exception as e:
            return f"Bridge error: {e}"
    return "Bridge not available"

def update_assistant_setting(assistant_id, key, value):
    if _bridge:
        try:
            return _bridge.updateAssistantSetting(assistant_id, key, value)
        except Exception as e:
            return f"Bridge error: {e}"
    return "Bridge not available"

def update_knowledge_entry(entry_id, title=None, content=None):
    if _bridge:
        try:
            return _bridge.updateKnowledgeEntry(entry_id, title, content)
        except Exception as e:
            return f"Bridge error: {e}"
    return "Bridge not available"

def delete_knowledge_entry(entry_id):
    if _bridge:
        try:
            return _bridge.deleteKnowledgeEntry(entry_id)
        except Exception as e:
            return f"Bridge error: {e}"
    return "Bridge not available"

def get_setting(key):
    if _bridge:
        try:
            return _bridge.getSetting(key)
        except Exception as e:
            return f"Bridge error: {e}"
    return "Bridge not available"

def update_setting(key, value):
    if _bridge:
        try:
            return _bridge.updateSetting(key, value)
        except Exception as e:
            return f"Bridge error: {e}"
    return "Bridge not available"


# ============================================================
# Main executor
# ============================================================

def execute(code: str, workdir: str, bridge=None) -> str:
    """Execute Python code, return JSON with results."""
    global _bridge
    _bridge = bridge
    old_stdout = sys.stdout
    old_stderr = sys.stderr
    sys.stdout = StringIO()
    sys.stderr = StringIO()

    # List files before execution
    before = set()
    try:
        before = set(os.listdir(workdir))
    except Exception:
        pass

    result = None
    error = None
    output_files = []

    try:
        os.chdir(workdir)
    except Exception:
        pass

    # Pre-configure matplotlib
    try:
        import matplotlib
        matplotlib.use('Agg')
        import matplotlib.pyplot as plt
        plt.rcParams['figure.facecolor'] = 'white'
        plt.rcParams['axes.facecolor'] = 'white'
        plt.rcParams['savefig.facecolor'] = 'white'
    except ImportError:
        pass

    try:
        try:
            result = eval(code)
        except SyntaxError:
            exec(code)
            result = None

        # Auto-save matplotlib figures
        try:
            import matplotlib.pyplot as plt
            for i, fig_num in enumerate(plt.get_fignums()):
                fig = plt.figure(fig_num)
                fname = "figure_{}.png".format(i+1) if plt.get_fignums() else "figure.png"
                fig.savefig(os.path.join(workdir, fname), dpi=150,
                           bbox_inches='tight', facecolor='white', edgecolor='none')
                output_files.append(fname)
                plt.close(fig)
        except ImportError:
            pass

    except Exception as e:
        error = "{}\n{}".format(e, traceback.format_exc())

    finally:
        stdout = sys.stdout.getvalue()
        stderr = sys.stderr.getvalue()
        sys.stdout = old_stdout
        sys.stderr = old_stderr

        # Find new files
        try:
            after = set(os.listdir(workdir))
            for f in after - before:
                if not f.startswith('.'):
                    fpath = os.path.join(workdir, f)
                    if os.path.isfile(fpath) and os.path.getsize(fpath) > 0:
                        output_files.append(f)
        except Exception:
            pass

    resp = {}
    if error:
        resp["error"] = error
    if stdout:
        resp["stdout"] = stdout
    if stderr:
        resp["stderr"] = stderr
    if result is not None and not error:
        resp["result"] = str(result)
    if output_files:
        resp["files"] = list(set(output_files))
    if not resp:
        resp["result"] = "ok"
    return json.dumps(resp)
