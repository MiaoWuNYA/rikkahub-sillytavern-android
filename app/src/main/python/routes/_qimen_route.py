"""Route:  qimen"""
import json, sys, os
from ._shared import _js, _js_load

# ===== 奇门三式 =====
def _qimen(year,month,day,hour=None,minute=0,feature="all"):
    result={"system":"qimen","engine":""}
    _engs=[]
    # Qimen Dunjia (日家+时家)
    if feature in ("qimen","all"):
        try:
            _js_load("qimen-engine")
        except: pass
        # 日家 — 基础排盘（精度到天）
        try:
            q=json.loads(_js("qimen-engine",f"JSON.stringify(QimenEngine.generateQimenChart({{type:'rijia',year:{year},month:{month},day:{day}}}))"))
            if isinstance(q, dict) and 'error' not in q:
                q["_chartType"]="日家奇门"
                q["_description"]="以日干支定局（拆补法），精度到天，作背景参考。"
                result["qimen"]=q
                _engs.append("QimenEngine(日家)")
        except: pass
        # 时家 — 自研时家引擎 shiJiaGenerate（精度到时辰）
        if result.get("qimen") and hour is not None:
            try:
                qh=json.loads(_js("qimen-engine",
                    "var b=QimenEngine.generateQimenChart({type:'rijia',year:%d,month:%d,day:%d});"
                    "var stems=['甲','乙','丙','丁','戊','己','庚','辛','壬','癸'];"
                    "var branches=['子','丑','寅','卯','辰','巳','午','未','申','酉','戌','亥'];"
                    "var fp=JSON.parse(JSON.stringify(b.fourPillars));"
                    "var dg=fp.day.gan;"
                    "var bi=Math.floor((%d+1)/2)%%12;"
                    "var hi=(stems.indexOf(dg)%%5*2+bi)%%10;"
                    "var hg=stems[hi],hb=branches[bi];"
                    "fp.hour={gan:hg,zhi:hb};"
                    "var result=QimenEngine.shiJiaGenerate(hg,hb,b.juNumber,b.dun,fp,b.solarTerm);"
                    "result.juMethod='chaibu';"
                    "JSON.stringify(result)" % (year, month, day, hour)))
                if isinstance(qh, dict) and 'error' not in qh:
                    qh["_chartType"]="时家奇门"
                    qh["_description"]="以时辰干支排九星八门八神（拆补法），奇门遁甲断事正用。"
                    result["qimen_hourly"]=qh
                    if qh.get("fourPillars") and result["qimen"].get("fourPillars"):
                        result["qimen"]["fourPillars"]["hour"]=qh["fourPillars"].get("hour")
                    _engs[-1]="QimenEngine(日家+时家)"
            except: pass
        # 解读层（独立 try，失败不影响基础数据）
        if result.get("qimen"):
            try:
                qa=json.loads(_js("qimen-engine",
                    "try{"
                    "var b=QimenEngine.generateQimenChart({type:'rijia',year:%d,month:%d,day:%d});"
                    "var fp=b.fourPillars||{};var yg=fp.year?fp.year.gan:null;var mg=fp.month?fp.month.gan:null;"
                    "var dg=fp.day?fp.day.gan:null;var dz=fp.day?fp.day.zhi:null;"
                    "var zsd=b.zhiShiDoor;var zfs=b.zhiFuStar;"
                    "JSON.stringify({"
                    "starDetail:typeof QimenEngine.getStarDetail==='function'?"
                    "  ['天蓬','天芮','天冲','天辅','天禽','天心','天柱','天任','天英'].map(function(s){return QimenEngine.getStarDetail(s)}):null,"
                    "doorDetail:typeof QimenEngine.getDoorDetail==='function'?"
                    "  ['休门','生门','伤门','杜门','景门','死门','惊门','开门'].map(function(d){return QimenEngine.getDoorDetail(d)}):null,"
                    "godDetail:typeof QimenEngine.getGodDetail==='function'?"
                    "  ['值符','腾蛇','太阴','六合','白虎','玄武','九地','九天'].map(function(g){return QimenEngine.getGodDetail(g)}):null,"
                    "palaceDetail:typeof QimenEngine.getPalaceDetail==='function'?"
                    "  [1,2,3,4,5,6,7,8,9].map(function(p){return QimenEngine.getPalaceDetail(p)}):null,"
                    "shenJiangDetail:typeof QimenEngine.getShenJiangDetail==='function'?"
                    "  ['子','丑','寅','卯','辰','巳','午','未','申','酉','戌','亥'].map(function(b){return QimenEngine.getShenJiangDetail(b)}):null,"
                    "allJiGe:typeof QimenEngine.getAllJiGe==='function'?QimenEngine.getAllJiGe():null,"
                    "allXiongGe:typeof QimenEngine.getAllXiongGe==='function'?QimenEngine.getAllXiongGe():null,"
                    "yongShen:{"
                    "  day:typeof QimenEngine.getYongShen==='function'?QimenEngine.getYongShen(dg):null,"
                    "  year:yg?typeof QimenEngine.getYongShen==='function'?QimenEngine.getYongShen(yg):null:null,"
                    "  month:mg?typeof QimenEngine.getYongShen==='function'?QimenEngine.getYongShen(mg):null:null"
                    "},"
                    "zhiFuKeYing:dz?typeof QimenEngine.getZhiFuKeYing==='function'?QimenEngine.getZhiFuKeYing(zfs,dz):null:null,"
                    "palaceAnalysis:b.palaces.map(function(p){"
                    "  return{"
                    "    palaceNumber:p.palaceNumber,"
                    "    specialPatterns:typeof QimenEngine.detectSpecialPatterns==='function'?"
                    "      QimenEngine.detectSpecialPatterns(p.palaceNumber,p.skyStem,p.earthStem,p.door,p.star,p.god,zsd,dg,yg,mg):null,"
                    "    palaceKeYing:typeof QimenEngine.getPalaceKeYing==='function'?"
                    "      QimenEngine.getPalaceKeYing(p.earthStem,p.skyStem,p.hiddenStems||[],p.jiGanStem):null,"
                    "    yunChouPatterns:typeof QimenEngine.getYunChouPatterns==='function'?"
                    "      QimenEngine.getYunChouPatterns(p.door,p.skyStem,p.earthStem,p.god,p.palaceNumber):null,"
                    "    palaceChangSheng:typeof QimenEngine.getPalaceChangSheng==='function'?"
                    "      QimenEngine.getPalaceChangSheng(p.palaceNumber,p.earthStem,p.skyStem,p.hiddenStems||[],p.jiGanStem):null,"
                    "    marks:p.marks,"
                    "    jiGanStem:p.jiGanStem,"
                    "    diGod:p.diGod,"
                    "    highlightStem:p.highlightStem"
                    "  }"
                    "})"
                    "})"
                    "}catch(e){JSON.stringify({error:e.message})}" % (year, month, day)))
                if isinstance(qa, dict) and 'error' not in qa:
                    result["qimen_analysis"]=qa
            except: pass
    # 大六壬
    if feature in ("liuren","all"):
        try:
            _js_load("liuren-engine")
            lr=json.loads(_js("liuren-engine",f"JSON.stringify(LiuRen.getLiuRenByDate(new Date({year},{month-1},{day},{hour or 12},{minute})))"))
            if isinstance(lr, dict) and 'error' not in lr:
                result["liuren"]=lr
                _engs.append("LiuRen")
        except: pass
    result["engine"]="+".join(_engs) if _engs else "none"
    result["_hint"]=("QimenEngine: 日家(qimen)+时家(qimen_hourly,拆补法)。断事以时家为主，日家为辅。"
        "大六壬与奇门遁甲共用一个数据入口(system='奇门')，通过feature='liuren'读取大六壬排盘。"
        "system='大六壬'或'六壬'等同 system='奇门'，返回数据含qimen+liuren双份。"
        "Qimen分析层(qimen_analysis):"
        "  格局全列表(allJiGe+allXiongGe), 用神万物类象(yongShen),"
        "  值符克应(zhiFuKeYing), 十二神将详解(shenJiangDetail),"
        "  宫位十干克应含暗干/寄宫(palaceKeYing),"
        "  十二长生含各天干在宫位所有地支(palaceChangSheng),"
        "  宫位标记/寄干/地八神/用神天干(per-palace)."
        "自探索:Object.keys(QimenEngine)/Object.keys(LiuRen)")
    return result
