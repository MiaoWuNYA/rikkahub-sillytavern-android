"""
【现代西洋占星】 (仅JS)   四个库: Caelus v0.23.0 + NatalEngine + CaelusBirth + Astronomy

﻿══════════════════════════ 速览 ═══════════════════════════╗ ║ NatalEngine → 日月升+文本+元素平衡+合盘评分+ACG+HD行运 ║ ║ Caelus → 本命(尊贵/格局/互容/7点)+12宫位+7种推运 ║ ║ Caelus → 3种合盘(比较/组合中点/戴维森)+行运+恒星 ║ ║ Caelus → 赤纬相位+日食月食+越界+空亡+映点+调和盘 ║ ║ CaelusBirth→ 时区名→UT(备选, 同功能走NatalEngine.timezone) ║ ║ Astronomy → 星座仲裁(双引擎不一致时)+食相秒级精度 ║ ╚═══════════════════════════════════════════════════════════╝
 
── NatalEngine（主力，一键出盘+解读文本） ── 
── 西洋本命 ──
NatalEngine.calculateAstrology("1990-06-15", hour, tz, lat, lon) → bigThree / summary / sun:{sign:{name,element,modality,ruler,traits,shadow},degree,longitude} → moon / rising / midheaven 各含{sign:{name,element},degree} → balance:{elements,modalities,dominantElement,dominantModality} (仅日/月/升3星) → planets:{mercury,venus,mars,jupiter,saturn,uranus,neptune,pluto} 每行星:{sign:{name,element},degree,longitude} 
⚠️ 无宫位/逆行/尊贵 → nodes:{north,south} → allAspects:[{a,b,aspect,orb}] 精度: Meeus算法, VSOP87 — Moon误差0.00″ 
⚠️ NatalEngine 无宫位/逆行/尊贵 — 快速概览用, 深度分析走 Caelus。 
── 合盘 ── 
NatalEngine.compareAstrology(chartA,chartB) → {overallScore,scoreLabel,aspectSummary,summary} 
NatalEngine.compareHumanDesign(hdA,hdB) → {overallScore,scoreLabel,connections,summary} 
NatalEngine.compareGeneKeys(gkA,gkB) → {overallScore,scoreLabel,pairings,summary} 
NatalEngine.compareCharts(personA,personB,systems?) → 三系统综合对比 
── ACG 行星线 ── 
NatalEngine.calculateAstroCartography("1990-06-15", 12, -8, {latitude:39.9, longitude:116.4}) → {sun:{MC:[{lat,lon}],IC:[...],ASC:[...],DSC:[...]}, moon:{...}, ...} 
NatalEngine.getLinesAtLocation(acgResult, lat, lon, orb?) → [{planet,angle,distanceKm,...}] 
NatalEngine.getLocationReport(acgResult, lat, lon, "Beijing") → 文本报告 常量: 
NatalEngine.ACG_PLANET_INFO / 
NatalEngine.ACG_ANGLE_INFO 
── HD 行运 ── 
NatalEngine.calculateHDTransits(hdResult, "2026-06-22", tz) → {activatedGates,definedCenters,...} 
NatalEngine.calculateTransitGates("2026-06-22", tz) → {sun:{gate,line}, moon:{...},...} 
── 底层天文 ── 
NatalEngine.calculateBirthPositions(y, mo, d, h, tz, lat?, lon?) → {sun:{longitude,...},...} 
NatalEngine.getZodiacSign(longitude) → {name,element,modality,ruler,symbol} 
── 时区/地名(同时被CaelusBirth提供,二选一) ── 
NatalEngine.resolveUtcOffset("1990-06-15", "12:00", "Asia/Shanghai") → -8 (小时数) 
NatalEngine.formatUtcOffset(-8) → "UTC-8" (-5.5 → "UTC+5:30") 
NatalEngine.searchPlaces("Beijing") → [{name,latitude,longitude,timezone,countryCode}] 
⚠️ searchPlaces 是 async, 返回 Promise — 用 .then(r=>{...}) 或 await 
── ③ Caelus（深度分析，231+ 函数） ──
初始化: // 用户报地名→searchPlaces拿时区→resolveUtcOffset拿偏移→拼ISO字符串 var e=new 
Caelus.Engine(Caelus.embeddedData);
var jd=Caelus.isoToJd("1990-06-15T12:00:00+08:00"); // +08:00是示例, isoToJd内部转UT
var natalJd=jd;
var targetJd=Caelus.julianDay(2026,6,22,12,0,0);
var chart=e.chartAt(jd,lat,lon,{}); // 默认Placidus+热带
var ctx=Caelus.interpretationContext(chart); 
── 本命(18个) ──
chart.bodies.sun → {lon,sign,signDeg,house,retrograde,dignities,speed,lat,dist,ra,dec} 可选: sun/moon/mercury/venus/mars/jupiter/saturn/uranus/neptune/pluto/chiron/mean_node/true_node 扩展: mean_lilith/true_lilith (Caelus.EXTRA_BODIES) 
Caelus.isDayChart(e,jd,lat,lon) → 昼夜盘 
Caelus.lots(e,jd,lat,lon) → {day,fortune,spirit,eros,necessity,courage,victory,nemesis} 自算星座: 
Caelus.SIGNS[Math.floor(lon/30)]+" "+(lon%30).toFixed(1)+"°" 
Caelus.chartSignature(chart) → {elements:{},modalities:{},angularity:{},dominant:{element,modality,sign},ruler,hemispheres,quadrants,bodies} 
ctx.atoms→kind:"pattern" → T-square/Kite/Stellium/MysticRectangle/GrandTrine/GrandCross/Yod 
Caelus.detectPatterns(chart) → [{kind,bodies,apex?,orb}] 同上,直接取 
ctx.atoms→kind:"reception"→ 互容(domicile/exaltation/triplicity+mixed) 
ctx.atoms→kind:"dispositor"→ 定位星链 
ctx.atoms→kind:"dignity" → almuten/face/term/triplicity 
Caelus.findAspects(chart.bodies) → 最紧相位排行 
Caelus.aspectBetween(e,"sun","mars",jd) → {aspect,orb,phase,separation} 
Caelus.aspectPhase(lonA,speedA,lonB,speedB,aspectDeg) → "applying"|"separating"|"exact" 
Caelus.declinationAspects(e,Caelus.DEFAULT_BODIES,jd,1) → [{a,b,kind:"parallel"|"contraparallel"},...] 
Caelus.voidOfCourse(e,jd) → {isVoid,sign,signExit,nextAspect|null} 
Caelus.outOfBounds(e,"moon",jd) → bool 
Caelus.outOfBoundsMargin(e,"moon",jd) → 度数 
Caelus.dignityOf(e,"mars",jd) → ["domicile","exaltation",...] 
Caelus.planetarySect("mars") → "diurnal"|"nocturnal"|null 
Caelus.inSect("mars",isDay) → bool 得时/失时 
Caelus.gauquelinSector(e,"mars",jd,lat,lon) → 高奎林扇区 
Caelus.pheno(e,"mars",jd) → {phaseAngle,phase,elongation,diameter,magnitude} 
Caelus.signedElongation(lonA,lonB) → (-180..180] 
Caelus.separation(lonA,lonB) → [0,180] 
Caelus.solarElongation(e,"mercury",jd) → 日距(度) 
engine.heliocentric("mars",jdUt) → {lon,lat,dist} (Engine实例, 不是Caelus.) 
Caelus.solarPhase(e,"mercury",jd) → "cazimi"|"combust"|"under_beams"|null 
Caelus.planetaryHour(e,jd,lat,lon) → {ruler,kind,hour,start,end} 
Caelus.chartBrief(ctx,{limit?,kinds?,minSalience?}) → {jdUt,zodiac,facts:[{id,kind,text,salience}],prompt} 
── 推运(7种,18个) ──
法达: 
Caelus.firdariaAt(e,natalJd,targetJd,lat,lon) → {day,major,sub} 
⚠️ 必须传targetJd; 75年外返回{null,null} 
Caelus.firdaria(day,natalJd) → 完整周期表
ZR: 
Caelus.zrAt(e,natalJd,targetJd,lat,lon) → {lot,lot_sign,day,l1?,l2?,l3?,l4?}
主限: 
Caelus.primaryDirections(e,jd,lat,lon,bodies?,key?,maxYears?,yearLength?) → [{body,angle:"MC"|"IC"|"ASC"|"DSC",arc,years,jd}]
时间键 KEYS:{naibod:0.9856,ptolemy:1.0,brahe:0.986,cardan:0.985,simmonite:0.985}
世俗: 
Caelus.mundaneDirections(e,natalJd,lat,lonEast,bodies?,key?,maxYears?,yearLength?) → [{promissor,significator,arc,years,jd}]
太阳弧:Caelus.solarArc(e,natalJd,targetJd,yearLength?,zodiac?) → 度数值
等价: 
Caelus.directedLongitude(e,body,natalJd,targetJd,key?,zodiac?)
次限: 
Caelus.progressedLongitude(e,"sun",natalJd,targetJd,yearLength?,zodiac?) → 经度 
Caelus.progressedJd(natalJd,targetJd,yearLength?) → 数值,传chartAt得整盘次限
小限: 
Caelus.profectionAt(e,natalJd,targetJd,lat,lon) → {age_years,month,annual:{sign,sign_index,house,lord},monthly:{sign,sign_index,house,lord}}
回归: 
Caelus.solarReturn(e,natalJd,start,end,zodiac?)/lunarReturn → [jd,...] 
Caelus.returns(e,body,natalJd,start,end,zodiac?,maxHits?) → [jd,...] 
Caelus.stations(e,"saturn",jdStart,jdEnd) → [[jd,"retrograde"|"direct"],...] 
── 合盘(3种,5个) ──
比较: 
Caelus.synastryAspects(chartA,chartB,maxOrb?,orbs?) → [{a,b,aspect,orb,strength}] 
Caelus.synastryOverlays(chartA,chartB) → {aInB:{body:house},bInA:{body:house}} 组合: 
Caelus.compositeLongitudes(e,jdA,jdB,bodies,zodiac?) ← 不是(chartA,chartB) 
Caelus.compositePlacements(e,jdA,jdB,bodies?,zodiac?) → [{body,lon,sign,signDeg},...] 戴维森: 
Caelus.davisonParams(jdA,latA,lonA,jdB,latB,lonB) → [midJd,midLat,midLon] 增强: 
Caelus.enrichSynastryOptions(e,chartA,chartB,{orb?,zodiac?}) → {synastry,composite}
合并到 ctx: interpretationContext(chartA,{...enrichSynastryOptions(...)}) 
── 行运(12个) ── 
Caelus.transitAspects(natalChart,e,transitJd,{maxOrb?,zodiac?,orbs?,bodies?}) → [{transit,natal,aspect,orb,phase,strength,natalHouse}] 
Caelus.scan({start,end,step,onProgress?,progressEvery?},fn) → 批次扫描 
Caelus.rankMoments({start,end,step,limit?,minScore?},scoreFn) → [{jd,score}] 
Caelus.when(e,predicate,jdStart,jdEnd,{step?,maxIntervals?}) → [[start,end],...] 
Caelus.aspect(body,kind,target,orb?,zodiac?) → Predicate (与定点/另一星成指定相位) 
Caelus.inSign(body,sign,zodiac?) → Predicate (在指定星座) 
Caelus.retrograde(body,zodiac?) → Predicate (逆行) 
Caelus.notRetrograde(body,zodiac?) → Predicate (顺行/停) 
Caelus.allOf(...preds) → Predicate 
Caelus.anyOf(...preds) → Predicate 
Caelus.notOf(pred) → Predicate 组合任意条件 
Caelus.crossings(e,body,targetLon,jdStart,jdEnd,zodiac?,maxHits?) → [jd,...] 
⚠️ 逆行体可穿3次同经度, 全部返回, 按时间排序 
── 恒星(2个) ── 
e.starConjunctions(chart,{orb}) → [{body,star,orb},...] 
Caelus.starParans(e,jd,lat,stars,bodies?) → [{star,star_angle,body,body_angle,jd,gap_min},...] 
── 天文事件(5个) ── 
Caelus.lunarEclipses(e,jdStart,jdEnd)/solarEclipses (Meeus精度) 需要秒级精确时刻用 Astronomy.SearchLunarEclipse/SearchGlobalSolarEclipse 
Caelus.lunarPhases(e,jdStart,jdEnd,maxHits?) → [[jd,"new"|"first_quarter"|"full"|"last_quarter"],...] 
Caelus.riseSet(e,body,jdStart,lat,lon,"rise"|"set"|"mtransit"|"itransit", {altM?,pressure?,tempC?,searchDays?,discCenter?}) → jd|null 极昼/极夜=null 
Caelus.crossings(e,body,targetLon,jdStart,jdEnd,zodiac?,maxHits?) → [jd,...] 
Caelus.stations(e,"saturn",jdStart,jdEnd) → [[jd,"retrograde"|"direct"],...] 
── 宫位(12种制式) ──
e.chartAt(jd,lat,lon,{houseSystem:"koch"}) 切换制式 
Caelus.normalizeHouseSystem("whole sign") → "whole_sign"
有效值: placidus/koch/regiomontanus/campanus/porphyry/equal/ whole_sign/alcabitius/morinus/meridian/polich_page/vehlow 
Caelus.houseOf(lon,cusps)/Caelus.houseLord(ascSign,n) (ascSign=热带0-11) 
── 上下文增强 ── 
Caelus.enrichContextOptions(e,chart,{jd,lat,lonEast,zodiac?}, {transits?,timelords?,vedic?,transitOrb?}) → {transits,timelords,vedic} 合并到 interpretationContext(chart,{...base,...extras}) 
── 其他常用 ── 
Caelus.harmonicChart(e,jd,bodies,n) → 调和盘 
Caelus.astrocartography(e,jd,bodies,latMin?,latMax?,latStep?) → ACG行星线 
Caelus.parans(e,jd,lat,bodies?,toleranceMin?) → 四轴共升共落 
Caelus.antiscion(lon)/contraAntiscion(lon) → 映点/反映点 
Caelus.midpointLon(lon1,lon2) → 中点 
Caelus.ephemeris(e,bodies,{start,end,step,value?,zodiac?}) → {body:[{jd,value},...]} value默认"longitude", 可选"latitude"/"declination"/"rightAscension"/"speed" 
Caelus.sampleCount(start,end,step) → 样本数 
Caelus.element(sign)→"fire"|... 
Caelus.modality(sign)→"cardinal"|... 
Caelus.quadrant(house)→1-4 
Caelus.angularity(house)→"angular"|"succedent"|"cadent" 
Caelus.unitVector(lonDeg,latDeg)→[x,y,z] 
Caelus.angularSeparation3d(lonA,latA,lonB,latB)→度
常量: DEFAULT_BODIES/SIGNS/BODIES/EXTRA_BODIES/ASPECTS/DEFAULT_ORBS/DOMICILE/EXALTATION/ HOUSE_SYSTEMS/TROPICAL_YEAR/DEG/ARCSEC/J2000/LIGHT_TIME_AU 
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
⚠️ 日期参数是date不是day: {year,month,date,hour,minute} ╔══════════════════ 参数坑 ══════════════════╗ ║ vargaAt(e,jd,9) ← 数字,不是"D9" ║ ║ hasAspect({})(ctx) ← 柯里化,不是(chart) ║ ║ lots(e,jd,lat,lon) ← 不是hermeticLots ║ ║ firdariaAt 必须传 targetJd ║ ║ compositeLongitudes(e,jdA,jdB,bodies) ║ ║ dignities("sun",2) ← sign是0-11索引 ║ ║ almuten(84.13) ← 裸经度不是body名 ║ ║ outOfBounds(e,body,jd)← 不是(body,decl) ║ ╚═════════════════════════════════════════════╝
 其余 200+ 函数用 dir(Caelus) 自探索:
底层天文(sunApparent/nutation/precessEcliptic),
尊贵原子(dignityScore/faceRuler/termRuler/signRuler),
组合器(matchAll/matchAny),
特殊点(meanNode→弧度/57.2958转度/meanLilith/trueLilith/vertexEastPoint),
lotFortune/lotSpirit/hermeticLots,
探测(houseCusp/angles/gmst/gast/normalizeHouseSystem)等。
Caelus(西洋+吠陀) → eval_javascript(library="caelus-engine", code="var e=new Caelus.Engine(Caelus.embeddedData); var jd=Caelus.isoToJd('1990-06-15T12:00:00+08:00'); e.chartAt(jd,39.9,116.4,{})")
   chartAt(jd,lat,lon,opts) → {bodies, cusps:[12], angles:{asc,mc}, aspects, ...}
   bodies[name] → {lon, sign, signDeg, house(1-12), retrograde, lat, speed, dignities}
   cusps[i] 是第i宫头经度(0-359), 不是 houses!
   Caelus.SIGNS → ["Aries","Taurus"…]  索引=Math.floor(lon/30)
行运: Caelus.transitAspects(natal, e, transitJd, {maxOrb:3})
   → [{a,b,kind,orb,strength,exactAt?}]
双星相位: Caelus.aspectBetween(e, bodyA, bodyB, jd, zodiac?, orbs?)
   → {kind, orb, strength} | null
Gauquelin: Caelus.gauquelinSector(e, body, jd, lat, lon) → sector(1-36) | null
又 lots(e,jd,lat,lon)
又 firdariaAt(e,jd,targetJd,lat,lon)
又 primaryDirections
又 solarArc
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

【引擎区别速查】
  • 现代西洋占星: NatalEngine(解读+文本,唯一输出) → Caelus(格局+尊贵+推运+12宫位+赤纬+7点)

NatalEngine 星历精度与 Astronomy 同级 (Moon:0.00″ vs VSOP87)
Astronomy 仅需要 NASA 级精度时选配
"""
