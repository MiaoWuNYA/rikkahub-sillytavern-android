"""
﻿【灵数学/卡巴拉/数秘】 (JS Kaabalah引擎, 1.3MB, 零随机) ⚠️ 读日期用的是 local calendar getter，构造时用 local noon: new Date(1990, 5, 15, 12) 避免时区跳日。不可传 {year,month,day} ╔══════════════════ 速览 ══════════════════╗ ║ 生命灵数 + 流年 + 挑战 + 斐波那契 ║ ║ 希伯来 Gematria (字母数值) ║ ║ Ifá 非洲占卜 (Odu) ║ ║ 生命之树 (11球体 + 22路径 + 777全对应) ║ ╚══════════════════════════════════════════╝ var d = new Date(1990, 5, 15, 12); // 6月=5, local noon 【灵数 — 6 个核心】 Kaabalah.calculateKaabalisticLifePath(d) → {parts:{day:"15",month:"06",year1:"19",year2:"90"}, reducedParts:{reducedDay:6,reducedMonth:6,reducedYear1:1,reducedYear2:9}, syntheses:{dayMonthSynthesis:66,yearSynthesis:19, reducedDayMonthSynthesis:3,reducedYearSynthesis:1,finalSynthesis:31}, lifePath:{reducedValue:4,reductionSteps:[31,4]}, personalMythologyNumbers:[6619,31,4]} Kaabalah.calculateStraightAcrossReductionLifePath(d) → {dayEnergy:{reducedValue:6,reductionSteps:[15,6]}, monthEnergy:{reducedValue:6,reductionSteps:[6]}, yearEnergy:{reducedValue:1,reductionSteps:[1990,19,10,1]}, lifePath:{reducedValue:4,reductionSteps:[15061990,31,4]}} Kaabalah.calculatePersonalYear(d, new Date()) → {reducedValue, reductionSteps} Kaabalah.calculateChallenges(d) → {day, month, year, mainChallenge, subChallenge1, subChallenge2} Kaabalah.calculateFibonacciCycle(d, new Date()) → {currentAge, cycle1~7: {reducedValue, reductionSteps}} Kaabalah.getDateEnergies(d) → {dayEnergy:{reducedValue,reductionSteps}, monthEnergy:{reducedValue,reductionSteps}, yearEnergy:{reducedValue,reductionSteps}} 辅助: Kaabalah.isMasterNumber(11)→true (22)→true (33)→true (44)→true (5)→false Kaabalah.reduceToSingleWithSteps(31) → {reducedValue, reductionSteps} Kaabalah.reduceToSingle(31) → 直接返回数字 Kaabalah.calculatePersonalMonths(d, personalYear, new Date()) → {personalMonths:[13个月],currentPersonalMonthIndex} ⚠️ personalYear需先由calculatePersonalYear得到 Kaabalah.calculatePersonalCycles(d, today, firstName) → {personalYear,personalPeriods,personalMonths,currentAge,lifePath,soulNumber?} ⚠️ 需传firstName(如"John") 【Gematria — 2 个核心】 Kaabalah.calculateGematria("chiron") → {vowels:{originalSum:16, reductionSteps:[16,7], finalValue:7}, consonants:{originalSum:1200, reductionSteps:[1200,3], finalValue:3}, synthesis:{originalSum:1216, reductionSteps:[19,10,1], finalValue:1}, includedLetters:[{latinLetterId, value, hebrewCharacter, hebrewLetterId, isVowel}, ...]} // chiron → Ch=ש=300, I=י=10, R=ר=200, O=ו=6, N=ן=700 元音I+O=16→7 辅音Ch+R+N=1200→3 Kaabalah.calculateGematria("love") → vowels:11→2 consonants:36→9 synthesis:47→20→2 L=ל=30, O=ו=6, V=ו=6, E=ה=5 Kaabalah.calculateGematria("aries") → vowels:16→7 consonants:260→8 synthesis:276→24→6 A=א=1, R=ר=200, I=י=10, E=ה=5, S=ס=60 Kaabalah.reverseGematria(111) → {results:[], hasMore, totalFound} (字典可能未加载单词表, 结果可能为空) 支持: 英文单词/希伯来音译/星座名/行星名 均可传入 calculateGematria 【Ifá — 1 个】 Kaabalah.calculateOdu(d) → {leftNumbers:[1,0,1,9], rightNumbers:[5,6,9,0], north:11, south:2, east:13, west:8, center:7} 【生命之树 — 4 个核心】 Kaabalah.buildKaabalisticMapData({numerology: d}) → {spheres:[{id,name,hebrew,number,meaning,position} ×11], paths:[{id,name,from,to,hebrew} ×22], markers:[], sphereMarkers:{}, pathMarkers:{}, countsById:{}, itemConnections:{}} Kaabalah.buildKaabalisticMapData({astrology: { planets: [{name:"Sun", zodiacPosition:{sign:{name:"Gemini"}}}, ...], nodes: [{name:"North Node", sign:"Aquarius"}, ...], houses: {ascendant:{sign:{name:"Virgo"}}, mc:{sign:{name:"Gemini"}}, ascmc:{vertex:{sign:{name:"Leo"}}}} }}) ⚠️ sign 必须是对象 {name:"Gemini"} 不是字符串 数据查询 (按需): Kaabalah.SPHERES_DATA["KETHER"] → {name,hebrew,number,meaning,colors,...} Kaabalah.LURIANIC_PATHS["11"] → {from:"Kether",to:"Chokhmah",letter:"Aleph",...} Kaabalah.HEBREW_LETTERS_DATA["ALEPH"] → {value:1,symbol:"א",meaning:"Ox",...} Kaabalah.FOUR_WORLDS → ["ATZILUTH","BRIAH","YETZIRAH","ASSIAH"] Kaabalah.FOUR_WORLDS_DATA["ATZILUTH"] → {name,meaning,...} Kaabalah.SPHERES["KETHER"] → {id,name,number,...} Kaabalah.GematriaData → {hebrewLetters:{}, latinLetters:{}, ...} 11球体: Kether→Chokhmah→Binah→Daath→Chesed→Geburah→ Tiphareth→Netzach→Hod→Yesod→Malkuth 【塔罗→卡巴拉 777 全对应】 大牌(22): 序号→路径→字母 0=Fool(11,Aleph) 1=Magician(12,Beth) 2=HighPriestess(13,Gimel) 3=Empress(14,Daleth) 4=Emperor(15,Heh) 5=Hierophant(16,Vau) 6=Lovers(17,Zain) 7=Chariot(18,Cheth) 8=Strength(19,Teth) 9=Hermit(20,Yod) 10=Wheel(21,Kaph) 11=Justice(22,Lamed) 12=HangedMan(23,Mem) 13=Death(24,Nun) 14=Temperance(25,Samekh) 15=Devil(26,Ayin) 16=Tower(27,Peh) 17=Star(28,Tzaddi) 18=Moon(29,Qoph) 19=Sun(30,Resh) 20=Judgement(31,Shin) 21=World(32,Tau) 数字牌(40): Ace=1(Kether) ... 10(Malkuth) 牌组→四世界: Wands=Atziluth, Cups=Briah, Swords=Yetzirah, Pentacles=Assiah 宫廷牌(16): King→Chokmah, Queen→Binah, Knight→Tiphareth, Page→Malkuth 查法: Kaabalah.SPHERES[name] + Kaabalah.FOUR_WORLDS[world] + HEBREW_LETTERS_DATA[letter] + LURIANIC_PATHS[pathNum] 

【塔罗 — 22 个导出】
drawTarotSpread({spreadId, deckId:"rider-waite", includeInverted:false, rng, context})  → 7牌阵抽牌
drawConsciousTarotSpread({spreadId, indices, shuffledDeck, includeInverted:false, rng, deckId, context})  → 意识塔罗(用户选牌)
shuffleTarotDeck(cards, includeInvertedCards=false, shuffleCount=6, shuffleDelay=300, rng=cryptoRandom)  → 洗牌支持逆位
listTarotSpreads()  → 7牌阵定义  listTarotDecks()  → 5牌桌  listTarotTrees()  → 3树系统
getTarotCardByNumber(n)  → 1-78查牌  getTarotCardNumber({tarotCardName:"...")  → 牌名反查编号
getTarotCardProfile({tarotCardNumber:n})  → 牌信息(meaning+type+deck)
getTarotCorrespondenceProfile({tarotCardNumber:n})  → 全78张通用:
  大牌→路径(fromSphere→toSphere)+字母+占星[{planet,zodiac}]  小牌→源质+行星  宫廷→星座+行星(⚠️Page→元素)
getTarotArchetype({tarotCardNumber:n})  → ⚠️ 仅大牌(57-78): {pathId,hebrewLetter,astrology:[{element,planet,zodiac}]}
getTarotThemeProfile({tarotCardNumber:n})  → 主题对应(planet+zodiac+element)
getTarotRepresentation({tarotCardNumber:n}, deckId)  → 牌在指定牌桌表示
getTarotRepresentations({tarotCardNumber:n})  → 所有5牌桌下表示
listTarotThemeProfiles()  → 22张大牌对应概览
ARKANNUS(22大牌)  majorArcana(22文件名)
⚠️ deckId可选: rider-waite | papus_pt(卡巴拉) | papus(占卜) | mythic(神话) | egyptian(埃及)
⚠️ 7牌阵: quick-insight(单张) | conscious-reading(意识3层·仅大牌) | time-reading(过去现在未来·仅大牌) | dialectic-reading(正反合·仅大牌) | tree-of-life-reading(11位) | celtic-cross(经典10张) | event-reading(帕普斯)

【卡巴拉对应查询(跨体系)】
buildKaabalisticMapData({astrology, numerology, gematria})  → 全地图(标记挂生命之树)
getKaabalisticCorrespondenceTargets({kind:"number", number:N})  → 数字→sphere/path对应
getKaabalisticCorrespondenceTargets({kind:"hebrewLetter", hebrewLetterId})  → 字母→路径
getKaabalisticCorrespondenceTargets({kind:"sign", sign:"aries"})  → 星座对应
getKaabalisticCorrespondenceTargets({kind:"planet", planet:"sun"})  → 行星对应
getKaabalisticCorrespondenceTargets({kind:"angle", angle:"asc"})  → 轴角对应
getAstrologyTreeMarkers(planet)  → 行星在树上的标记
getGematriaTreeMarkers(word)  → Gematria在树上的标记
getNumerologyTreeMarkers(number)  → 数字在树上的标记
getCanonicalTree()  → 完整生命之树结构
getTreeTopology()  → 树拓扑
SPHERES["KETHER"]  SPHERES_DATA["KETHER"]  FOUR_WORLDS  FOUR_WORLDS_DATA
HEBREW_LETTERS["ALEPH"]  HEBREW_LETTERS_DATA["ALEPH"]
LATIN_LETTERS  LATIN_LETTERS_DATA
LURIANIC_PATHS["11"]  MELKITZEDEKI_PATHS
COLORS_DATA  MUSICAL_NOTES_DATA  PLANETS
GematriaData  NumerologyData

╚═════════════════════════════════════════════╝
又 Object.keys(Kaabalah) 自探索全部 API, 包括:
TreeOfLife / TreeTopology 类, TREE_SPHERE_IDS / TREE_PATH_IDS, getTreeLayout,
TreeOfLife.getPath() / getSphere() 方法
【JS引擎调用】
Kaabalah    → eval_javascript(library='kaabalah-engine', code="Kaabalah.calculateKaabalisticLifePath(new Date(Date.UTC(1990,5,15)))")
Kaabalah塔罗 → 同上, code="Kaabalah.drawTarotSpread({spreadId:'celtic-cross', deckId:'rider-waite', includeInverted:true})"
  首次需 action='load' 加载库, 后续直接 eval

── 灵数 ──
又 calculatePersonalYear(birth, new Date())  又 calculateChallenges(birth)
又 calculateFibonacciCycle(birth)  又 getDateEnergies(birth)
又 calculatePersonalMonths(birth, personalYear, new Date())  又 calculatePersonalCycles(birth, today, "John")
又 isMasterNumber(n)  又 reduceToSingleWithSteps(n)

── Gematria / Ifá ──
又 calculateGematria("word")  又 reverseGematria(111)
又 calculateOdu(birth)

── 塔罗 + 卡巴拉对应 ──
又 drawTarotSpread({spreadId, deckId:"rider-waite", includeInverted:false, rng, context})  → 7牌阵抽牌
又 drawConsciousTarotSpread({spreadId, indices, shuffledDeck, includeInverted:false, rng, deckId, context})  → 意识塔罗(用户选位)
又 shuffleTarotDeck(cards, includeInvertedCards=false, shuffleCount=6, shuffleDelay=300, rng=cryptoRandom)  → 洗牌支持逆位
又 listTarotSpreads()  又 listTarotDecks()  又 listTarotTrees()  又 getTarotSpread(spreadId)
又 getTarotCardByNumber(cardNumber)  → 1-78查牌
又 getTarotCardNumber({tarotCardName:"The Magician"})  → 牌名反查编号
又 getTarotCardProfile({tarotCardNumber:n})  → 牌信息+含义
又 getTarotCorrespondenceProfile({tarotCardNumber:n})  → 78张通用: 大牌路径+字母+源质 小牌源质+行星 宫廷星座+行星
又 getTarotArchetype({tarotCardNumber:n})  → ⚠️ 仅大牌(57-78)
又 getTarotThemeProfile({tarotCardNumber:n})  → 主题对应(planet+zodiac+element)
又 getTarotRepresentation({tarotCardNumber:n}, deckId)  又 getTarotRepresentations({tarotCardNumber:n})
又 listTarotThemeProfiles()  又 ARKANNUS(22大牌)  又 majorArcana(22文件名)

── 卡巴拉生命之树核心 + 对应查询 ──
又 buildKaabalisticMapData({astrology, numerology, gematria})  → 全地图
又 getKaabalisticCorrespondenceTargets({kind:"number", number:N})  → 数字→sphere/path
又 getKaabalisticCorrespondenceTargets({kind:"hebrewLetter", hebrewLetterId})  → 字母→路径
又 getKaabalisticCorrespondenceTargets({kind:"sign", sign:"aries"})  → 星座对应
又 getKaabalisticCorrespondenceTargets({kind:"planet", planet:"sun"})  → 行星对应
又 getKaabalisticCorrespondenceTargets({kind:"angle", angle:"asc"})  → 轴角对应
又 getAstrologyTreeMarkers(planet)  又 getGematriaTreeMarkers(word)  又 getNumerologyTreeMarkers(number)
又 SPHERES["KETHER"]  又 FOUR_WORLDS  又 HEBREW_LETTERS["ALEPH"]  又 LURIANIC_PATHS["11"]
又 getCanonicalTree({system:"kaabalah", parts:["westernAstrology","tarot"]})  又 getTreeTopology()  又 getTreeTopology({system:"hermetic-qabalah"})
又 COLORS_DATA  又 MUSICAL_NOTES_DATA  又 PLANETS  又 MASTER_NUMBERS


【引擎区别速查】
  • 卡巴拉/灵数/Gematria/Ifá: Kaabalah 唯一
"""
