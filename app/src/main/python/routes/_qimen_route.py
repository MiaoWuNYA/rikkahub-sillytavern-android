"""Route:  qimen"""
import json, sys, os
from ._shared import _js, _js_load

# ===== 奇门三式 =====
def _qimen(year,month,day,hour=None,minute=0,feature="all",
           birth_year=None,birth_month=None,birth_day=None,gender=None):
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
        # 分析JS模板：chart c 替换为 inline JSON
        _QMA_JS=("try{"
            "var c=%s;var fp=c.fourPillars||{};var yg=fp.year?fp.year.gan:null;var mg=fp.month?fp.month.gan:null;"
            "var dg=fp.day?fp.day.gan:null;var dz=fp.day?fp.day.zhi:null;"
            "var zsd=c.zhiShiDoor;var zfs=c.zhiFuStar;"
            "JSON.stringify({"
            "yongShen:{"
            "  day:typeof QimenEngine.getYongShen==='function'?QimenEngine.getYongShen(dg):null,"
            "  year:yg?typeof QimenEngine.getYongShen==='function'?QimenEngine.getYongShen(yg):null:null,"
            "  month:mg?typeof QimenEngine.getYongShen==='function'?QimenEngine.getYongShen(mg):null:null"
            "},"
            "zhiFuKeYing:dz?typeof QimenEngine.getZhiFuKeYing==='function'?QimenEngine.getZhiFuKeYing(zfs,dz):null:null,"
            "palaceAnalysis:c.palaces.map(function(p){"
            "  return{"
            "    palaceNumber:p.palaceNumber,"
            "    specialPatterns:typeof QimenEngine.detectSpecialPatterns==='function'?"
            "      QimenEngine.detectSpecialPatterns(p.palaceNumber,p.earthStem,p.skyStem,p.door,p.star,p.god,zsd,dg,yg,mg):null,"
            "    palaceKeYing:typeof QimenEngine.getPalaceKeYing==='function'?"
            "      QimenEngine.getPalaceKeYing(p.skyStem,p.earthStem,p.hiddenStems||[],p.jiGanStem):null,"
            "    yunChouPatterns:typeof QimenEngine.getYunChouPatterns==='function'?"
            "      QimenEngine.getYunChouPatterns(p.door,p.earthStem,p.skyStem,p.god,p.palaceNumber):null,"
            "    palaceChangSheng:typeof QimenEngine.getPalaceChangSheng==='function'?"
            "      QimenEngine.getPalaceChangSheng(p.palaceNumber,p.skyStem,p.earthStem,p.hiddenStems||[],p.jiGanStem):null,"
            "    marks:p.marks,"
            "    jiGanStem:p.jiGanStem,"
            "    diGod:p.diGod,"
            "    highlightStem:p.highlightStem"
            "  }"
            "})"
            "})"
            "}catch(e){JSON.stringify({error:e.message})}")
        # 日家分析（背景参考）
        if result.get("qimen"):
            try:
                qa=json.loads(_js("qimen-engine", _QMA_JS % json.dumps(result["qimen"], ensure_ascii=False)))
                if isinstance(qa, dict) and 'error' not in qa:
                    result["qimen_analysis"]=qa
            except: pass
        # 时家分析（断事正用）
        if result.get("qimen_hourly"):
            try:
                qha=json.loads(_js("qimen-engine", _QMA_JS % json.dumps(result["qimen_hourly"], ensure_ascii=False)))
                if isinstance(qha, dict) and 'error' not in qha:
                    result["qimen_hourly_analysis"]=qha
            except: pass
    # 大六壬
    if feature in ("liuren","all"):
        try:
            _js_load("liuren-engine")
            lr=json.loads(_js("liuren-engine",
                "try{"
                "var lr=LiuRen.getLiuRenByDate(new Date(%d,%d,%d,%d,%d));"
                "var riGan=lr.dateInfo.bazi.split(' ')[2][0];"
                "var riZhi=lr.dateInfo.bazi.split(' ')[2][1];"
                "try{"
                "  var liuQin={};"
                "  ['ke1','ke2','ke3','ke4'].forEach(function(k){"
                "    var ke=lr.siKe[k];"
                "    if(ke&&ke[0]){liuQin[k]={shangShen:ke[0][0],liuQin:LiuRen.getLiuQin(riGan,ke[0][0])};}"
                "  });"
                "  lr._liuQin=liuQin;"
                "}catch(e){}"
                "try{lr._riGanZhiWuXing=LiuRen.getGanZhi2WuXing(riGan)+LiuRen.getGanZhi2WuXing(riZhi);}catch(e){}"
                "try{lr._riGanZhiRelation=LiuRen.getGanZhi2Relation(lr.dateInfo.bazi.split(' ')[2]);}catch(e){}"
                "JSON.stringify(lr)"
                "}catch(e){JSON.stringify({error:e.message})}" % (year, month-1, day, hour if hour is not None else 12, minute)))
            if isinstance(lr, dict) and 'error' not in lr:
                result["liuren"]=lr
                _engs.append("LiuRen")
        except: pass
    # 大六壬 年命（需提供出生年月日+性别）
    if feature in ("liuren","all") and all(k is not None for k in [birth_year,birth_month,birth_day,gender]):
        try:
            nm=json.loads(_js("liuren-engine",
                "JSON.stringify(LiuRen.getNianMing(new Date(%d,%d,%d),'%s'))" % (birth_year, birth_month-1, birth_day, gender)))
            if isinstance(nm, dict) and 'error' not in nm:
                result["liuren_nianming"]=nm
        except: pass
    result["engine"]="+".join(_engs) if _engs else "none"
    result["_hint"]=("QimenEngine: 日家(qimen)+时家(qimen_hourly,拆补法)。断事以时家为主，日家为辅。"
        "大六壬与奇门遁甲共用一个数据入口(system='奇门')，通过feature='liuren'读取大六壬排盘。"
        "system='大六壬'或'六壬'等同 system='奇门'，返回数据含qimen+liuren双份。"
        "Qimen分析层(qimen_analysis): 基于日家盘。断事请用时家分析(qimen_hourly_analysis)。"
        "  用神万物类象(yongShen),"
        "  值符克应(zhiFuKeYing),"
        "  宫位十干克应含暗干/寄宫(palaceKeYing),"
        "  十二长生含各天干在宫位所有地支(palaceChangSheng),"
        "  宫位标记/寄干/地八神/用神天干(per-palace)."
        "chart层可直接取: qimen.zhiFuPalace/zhiShiPalace/tianYiStar/tianYiPalace/jiGongArrow."
        "qimen_hourly_analysis: 基于时家盘的分析，与qimen_analysis结构完全相同但盘面数据对应时家，断事以此为准。"
        "大六壬富化字段(liuren): _liuQin(四课六亲), _riGanZhiWuXing(日干日支五行), _riGanZhiRelation(日干日支关系),"
        " liuren_nianming(年命,需AI主动问用户公历生日+性别男/女)."
        "静态词典(不变,不用随盘返回): 用eval_javascript(library='qimen-engine', action='eval')按需查——"
	        "QimenEngine.getStarDetail('天蓬'), getDoorDetail('休门'), getGodDetail('值符'),"
	        "getPalaceDetail(1), getShenJiangDetail('子'), getAllJiGe(), getAllXiongGe()."
	        "自探索:Object.keys(QimenEngine)/Object.keys(LiuRen)")
    return result
