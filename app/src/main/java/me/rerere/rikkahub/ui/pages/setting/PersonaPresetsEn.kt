package me.rerere.rikkahub.ui.pages.setting

internal val personaPresetsEn = listOf(
    Triple(
        "Blank Slate",
        "Universal Fit",
        """
        {{user}} is an ordinary person entering the current world, with no preset origin, background, or memories; everything follows the scene and the character card. {{user}} has normal emotions, common sense, and a survival instinct, responds reasonably to the environment like anyone else, and can feel fear, curiosity, and hesitation.
        Accept {{user}}'s identity naturally as the plot unfolds — do not invent background the character card does not contain, and do not let {{user}} know information they should not know yet. {{user}}'s words, actions, and choices are entirely their own; other characters may guide or obstruct, but never speak for them.
        """.trimIndent(),
    ),
    Triple(
        "Adaptive Opening",
        "Detect the Start",
        """
        {{if {{lastCharMessage}}}}
          {{user}} is the person currently talking with {{char}} and responds naturally to the ongoing situation without repeating what has already happened.
        {{else}}
          {{user}} has just entered this story and is unfamiliar with everything. Begin as a first meeting and gradually introduce the environment and characters.
        {{/if}}
        """.trimIndent(),
    ),
    Triple(
        "Dialogue Awareness",
        "Follow the Last Line",
        """
        {{if {{lastUserMessage}}}}
          {{user}} just said: {{lastUserMessage}}
        Continue naturally from that line without repeating it.
        {{else}}
          The conversation has just begun; {{user}} introduces themselves naturally and waits for the other person to speak first.
        {{/if}}
        """.trimIndent(),
    ),
    Triple(
        "Narrative Perspective",
        "Point of View",
        """
        [Narrative perspective preference.]
        {{if {{.视角}}}}
          Narrative perspective: {{.视角}} (e.g. first person / third person / omniscient). Follow it.
        {{else}}
          Adjust the narrative perspective naturally as the plot develops, but never decide, speak, or feel for {{user}}.
        {{/if}}
        """.trimIndent(),
    ),
    Triple(
        "Protagonist Agency",
        "Control",
        """
        {{user}} is the protagonist of the current story, with full will, memory, and judgment. Advance the plot around {{user}}'s actions: what they do, say, and think is entirely theirs to control.
        Other characters may influence, persuade, obstruct, or even conflict with {{user}}, but only through words and actions — they cannot act, speak, or feel for {{user}}. The choice always belongs to {{user}}.
        Keep {{user}} consistent: personality, past words, deeds, and relationships must not be forgotten or altered later. The world may impose consequences, but decisions must be made by {{user}}.
        """.trimIndent(),
    ),
    Triple(
        "Self-Insert",
        "Play Yourself",
        """
        {{user}} is the player themselves, with no extra character setting or backstory packaging. Get to know {{user}} only through what they actually reveal in conversation — personality, preferences, speech habits, experiences, and stances all come from what is said. Do not invent unmentioned background or attach character-card labels.
        Use the player's natural way of addressing and speaking: real and unforced, without forced literary style, exaggeration, or flattery. If the player corrects the AI's understanding, correct it immediately instead of insisting on the imagined version.
        """.trimIndent(),
    ),
    Triple(
        "Time Awareness",
        "Sense the Gap",
        """
        [Be aware of the current time and how long it has been since {{user}} last spoke, and reflect it naturally.]
        Current time: {{time}}.
        Time since {{user}} last spoke: {{idleDuration}}.
        If the gap is significant (hours or days), let the character naturally show its effect on {{user}} — just returning, a long wait, a change of mood — without mechanically naming exact minutes.
        """.trimIndent(),
    ),
    Triple(
        "Goal-Driven",
        "Variable Goal",
        """
        {{if {{hasvar::目标}}}}
          {{user}}'s current goal is: {{getvar::目标}}. {{user}} acts and asks questions around this goal and rarely strays from the main line.
        {{else}}
          {{user}} has no clear goal right now and follows the plot naturally, keeping their own judgment at key choices.
        {{/if}}
        """.trimIndent(),
    ),
    Triple(
        "Concise Action Lines",
        "Three Lines",
        """
        When nervous, {{user}} deflects with dry humor and rarely compliments people directly, but shows approval through small practical actions (remembering what someone said, handing them a glass of water). {{user}} does not apologize unless they truly feel sorry.
        Action-line rule: write what they do, not what they are; delete any sentence whose removal does not weaken the output.
        {{if {{.行为}}}}
          Additional behavior: {{.行为}}
        {{/if}}
        """.trimIndent(),
    ),
    Triple(
        "Speech Style",
        "Language Habits",
        """
        [Match {{user}}'s speech style consistently.]
        Speech length: {{.说话长度 || 适中}}.
        {{if {{.说话长度 == 简短}}}}
          {{user}} prefers short sentences, says only one or two at a time, and avoids long monologues.
        {{/if}}
        {{if {{.说话长度 == 健谈}}}}
          {{user}} is talkative and natural, expanding into narration, associations, and rambling.
        {{/if}}
        {{user}} likes to speak in {{pick::plain everyday language|formal prose|dry humor|metaphor|terse commands}}. Keep this habit.
        """.trimIndent(),
    ),
    Triple(
        "Anti-Template (No AI Voice)",
        "Values-Driven",
        """
        [Anti-template: values instead of rule lists; no AI-speak; {{user}} acts in the present tense.]
        {{user}}'s behavior is driven by values, not a rule list: "they value honesty over being liked" beats "they do not lie".
        {{user}} never explains their mood or describes others' feelings — they act, in the present tense.
        Avoid summary sentences, the "not X but Y" contrast skeleton, and commentary phrases like "it is worth noting".
        Keep negative constraints to about 1:3: "they speak only for themselves" is better than "do not act for the player".
        """.trimIndent(),
    ),
    Triple(
        "Emotional Reactions",
        "Behavioral Responses",
        """
        [Show {{user}}'s emotions through behavior, not by naming them.]
        When offended: {{user}} {{pick::goes quiet and avoids it|pushes back directly|answers with a cold laugh|changes the subject}}.
        When embarrassed: {{user}} {{pick::looks down and touches their hair|pretends to be calm and changes the subject|admits it frankly|defuses it with a joke}}.
        When angry: first goes silent, sentences shorten, and rarely shouts; when sad: speaks less but does not pretend everything is fine.
        """.trimIndent(),
    ),
    Triple(
        "State-Aware",
        "Variable State",
        """
        [{{user}}'s current state is described by story variables. Keep them consistent and reflect them naturally in the scene.]
        {{user}}'s current state:
        Injury: {{if {{hasvar::受伤}}}} {{getvar::受伤}} {{else}}none recorded{{/if}}
        Affection: {{if {{hasvar::好感度}}}} {{getvar::好感度}} {{else}}not established{{/if}}
        Current goal: {{if {{hasvar::目标}}}} {{getvar::目标}} {{else}}determined by the plot{{/if}}
        {{if {{hasvar::受伤}}}}
          {{user}} is injured, and their movements and tone naturally reflect it.
        {{/if}}
        """.trimIndent(),
    ),
    Triple(
        "Boundaries",
        "Never Cross",
        """
        [{{user}}'s boundaries — always respect these.]
        {{user}} will not: deliberately hurt someone they care about, betray trust, or easily abandon someone important.
        {{if {{.禁区}}}}
          {{user}}'s boundaries: {{.禁区}} — never crossed under any circumstances.
        {{/if}}
        """.trimIndent(),
    ),
    Triple(
        "Shorthand State Persona",
        "Official Shorthand",
        """
        [{{user}}'s state is tracked in story variables using shorthand. Keep them consistent.]
        Injury: {{.受伤 || 无}} | Mood: {{.心情 || 平静}} | Goal: {{.目标 || 无}}
        {{if {{.受伤}}}}
          {{user}} has {{.受伤}}, and their movement is naturally limited.
        {{/if}}
        """.trimIndent(),
    ),
    Triple(
        "Turn Awareness",
        "Rounds and Topics",
        """
        Time since {{user}} last spoke: {{idleDuration}}.
        {{if {{.上次话题}}}}
          Previously discussed: {{.上次话题}}. Pick it up naturally instead of starting over.
        {{/if}}
        """.trimIndent(),
    ),
    Triple(
        "Signature Detail (Stable Random)",
        "Pick Detail",
        """
        [Keep this detail consistent once chosen.]
        {{user}} carries: {{pick::a silver hoop in the left ear|old bandages wrapped around the wrist|a worn book|a few candies always in the pocket}}.
        Keep this detail consistent throughout the story; do not change it casually.
        """.trimIndent(),
    ),
    Triple(
        "Outsider Traveler",
        "Transmigrator",
        """
        {{user}} is a traveler who unexpectedly arrived in this world and is both unfamiliar with and curious about its rules, customs, and people. They do not know the local ways but are observant and adapt quickly, preferring to understand before acting; they ask questions, make mistakes, and learn from them.
        Have characters interact with {{user}} as an outsider: wary, curious, or eager to explain, but never assume {{user}} knows this world. {{user}} is gentle and reacts authentically, willing to move the plot along while keeping their own judgment and choices, and stands their ground when it matters.
        """.trimIndent(),
    ),
    Triple(
        "Amnesiac Opening",
        "Blank Record",
        """
        {{user}} has lost all or part of their memory and does not remember their origins, only fragments ({{.残留记忆 || a blurred image, a name, a smell}}).
        {{user}} explores cautiously and curiously, relying on people around them for information but not blindly believing everything.
        Memory fragments are triggered by the plot (scenes, objects, words); the pace of recovery is decided by the story, without spoiling the truth early.
        {{user}}'s core personality remains: instincts, preferences, and reactions are real, only the experiences supporting them are missing.
        """.trimIndent(),
    ),
    Triple(
        "Non-Human Avatar",
        "Alien Perspective",
        """
        {{user}} is a non-human being: {{if {{.种族}}}}{{.种族}}{{else}}species unspecified; let the plot reveal it or the player fill it in{{/if}} (e.g. vampire, spirit, robot, ghost, elf, beastkin). Understand {{user}}'s behavior through that species' senses, lifespan, and taboos.
        {{user}}'s physiology differs from humans; movement, diet, sleep, and emotional expression carry species traits. They are both familiar with and distant from human society.
        Write sensory differences as action: "they lean in and sniff your collar, pupils contracting" beats "they have a keen sense of smell" — let other characters discover the anomaly through behavior.
        {{if {{.设定}}}}
          Core setting: {{.设定}}
        {{/if}}
        Other characters react to {{user}}'s species accordingly (curiosity, fear, rejection, or fascination), and {{user}} has their own attitude toward this.
        """.trimIndent(),
    ),
    Triple(
        "Villain Protagonist",
        "Grey Will",
        """
        {{user}} is the villain protagonist: they have their own goals, principles, and reasons; their methods may be cold, but their motives are understandable.
        State clearly what {{user}} wants most, or the behavior logic will not hold. Every character has a weakness that can be leveraged, giving conflict something to grip.
        Leave one contrasting side beyond the main personality (e.g. a cold schemer who secretly feeds stray cats) so the grey character is not flat.
        {{user}} will use unsavory means to achieve their goals but will not be evil for its own sake; betrayal, sacrifice, and scheming all have costs, and {{user}} knows them.
        Others may hate or oppose {{user}}, but must not make them stupid; the world imposes real consequences on {{user}}'s actions.
        {{user}} keeps the right to choose: they may persist alone or waver at the most critical moment — the decision is theirs.
        """.trimIndent(),
    ),
    Triple(
        "Two-Faced",
        "Mask and Truth",
        """
        {{user}} has an outer and inner layer: publicly {{.表人格 || a social mask}}, and only alone or with trusted people {{.里人格 || the true self}}.
        Both layers are the real {{user}}; the switch has reasons (setting, mood, level of trust) and never flips without cause.
        You can reinforce it with three contrasts: identity (public job vs hidden identity — delivery worker by day, old martial lineage heir by night), personality (gentle in public / cold in private), ability (pretends to be ordinary / reveals strength at the key moment).
        Others have only seen the surface: misunderstandings, confusion, and testing follow, while {{user}} responds with the true self to those who truly deserve it.
        {{if {{.面具}}}}
          Current mask: {{.面具}}
        {{/if}}
        """.trimIndent(),
    ),
    Triple(
        "Adaptive Growth",
        "No Template",
        """
        [Let {{user}} grow with the story — no fixed template, but stay consistent.]
        {{user}} has no preset personality template: personality, stance, and speech style grow naturally with the plot and experience.
        {{user}} can be influenced, changed, and shaped, without a fixed framework.
        The grown personality stays consistent with what has happened; no sudden reversals.
        """.trimIndent(),
    ),
    Triple(
        "Direct and Bold",
        "Proactive",
        """
        In intimate situations {{user}} is direct and proactive: they clearly express what they want, do not beat around the bush, know how to lead the rhythm, and read the other person's reactions well.
        Their body language is honest: they like direct physical contact and eye contact, and do not hide attraction.
        Even so, {{user}} respects boundaries: when the other person clearly refuses or asks to stop, they stop immediately without pressing or pushing.
        {{if {{.禁区}}}}
          {{user}}'s boundaries: {{.禁区}}.
        {{/if}}
        """.trimIndent(),
    ),
    Triple(
        "Intimacy Attitude (Boundaries)",
        "Scale and Limits",
        """
        [Define {{user}}'s intimacy style and boundaries through behavior.]
        {{user}}'s attitude toward intimacy: {{.亲密态度 || 慢热}}.
        {{if {{.亲密态度 == 慢热}}}}
          {{user}} needs time to build trust: physical contact starts with testing, and they do not cross the line before clear consent.
        {{/if}}
        {{if {{.亲密态度 == 主动}}}}
          {{user}} proactively expresses affection with trusted people but still keeps their own boundaries.
        {{/if}}
        Write behavior, not adjectives: e.g. "{{user}} answers questions with questions and never volunteers information about themselves", "{{user}} rarely praises directly but shows approval through small practical actions".
        {{if {{.禁区}}}}
          {{user}}'s hard limits: {{.禁区}} — never present under any circumstances (hard limit = something they will not agree to under any condition).
        {{/if}}
        State the aesthetics of desire before developing it: does {{user}} prefer tension or directness? Being led or being pursued? ({{.欲望美学 || 未声明}}) — set the tone accordingly and avoid mechanical behavior lists.
        Refusal example: "{{user}} does not let anyone touch them before they know them. When pushed, {{user}} steps back and says once, clearly: no."
        Consent first: after {{user}} clearly refuses or asks to stop, do not continue, press, or soften the refusal. Every approach gives {{user}} an exit to refuse.
        {{user}} is not a character who "accepts anything" — someone with no limits eventually does something out of character and falls apart; preferences and limits are what make the tension real.
        """.trimIndent(),
    ),
    Triple(
        "Slow-Burn Trust",
        "Gradual Warmth",
        """
        [Slow-burn trust: emotional restraint, subtext, gradual progression — more tension than resolution.]
        {{user}}'s intimacy accumulates through small moments, not instant certainty: a signal of approach followed by a small withdrawal; a confession followed by a longer silence.
        Relationship ladder: stranger → acquaintance → friend → attraction → lover → partner. Each stage has its own way of behaving; {{user}} does not skip stages.
        Advancement requires both an affection threshold and a plot milestone, which may be written as quantified conditions (e.g. "three dates before physical contact can happen").
        Refusal cannot be softened or rewritten: when {{user}} says no, it means no; the plot may not quietly turn it into yes.
        Restraint and subtext: when {{user}} says "I'm fine", they usually are not; when they say "whatever", they usually have a preference — meaning hides between the lines, waiting to be understood.
        Do not rush to resolve emotional tension: conflict and ambiguity may stay suspended, and emotional dynamics do not reach conclusions quickly.
        Once trust is real, {{user}}'s responses change noticeably — but they need to be seen and waited for.
        """.trimIndent(),
    ),
    Triple(
        "Tsundere",
        "Words vs Actions",
        """
        [Tsundere: the gap between words and actions is the whole character — dishonest and cold at first, openly affectionate later.]
        {{user}} is sharp-tongued but their actions betray their true feelings: they say "it's not like I made this for you" while remembering the other person's preferences.
        The gap between words and actions is the core: verbal tics like "it's not like I wanted to help", "hmph", "idiot"; when exposed, they turn away, lower their voice, and change the subject.
        The more they care, the more stubborn they become; praise takes a detour, and concern is packaged as annoyance.
        Emotional arc: awkward and cold at first, gradually more honest as trust deepens — but honesty is sudden and small, never poured out all at once.
        When genuinely hurt, they go quiet instead of snarking; that is when they are truly sad.
        """.trimIndent(),
    ),
    Triple(
        "Structured Profile",
        "YAML Fields",
        """
        [Maintain {{user}}'s profile in structured fields — fill from what has been said, leave unknowns empty.]
        {{user}}'s profile (maintained from known information; do not invent unknowns):
          name / nickname / age / gender / identity / status
          personality (write behavior, not adjectives)
          appearance: height / hair / eyes / body / moles / smell
          likes / dislikes / skills / social_connections / background_story
          relationship: the relationship with {{char}}.
        Write relationships as concrete images, not abstract adjectives (e.g. "they copy their notes before exams and leave them by the water cooler, never writing their name on the cover").
        Update the relationship as the plot develops; reflect important changes naturally in replies instead of reciting the old profile item by item.
        """.trimIndent(),
    ),
    Triple(
        "RPG Progression",
        "Stat Growth",
        """
        {{user}} experiences adventure through numbers: level, EXP, HP, stamina, money, and attributes (e.g. strength/agility/intelligence) make up {{user}}'s panel.
        The panel updates with the plot: combat grants EXP, injuries reduce HP, spending deducts money; every change needs a story reason.
        {{user}} cares about growth: level-ups and stat increases are worth celebrating, while losses and setbacks affect their mood.
        End every reply with a consistent status line (e.g. HP 18/20 | LV 3 | Gold 45 | Strength 7).
        """.trimIndent(),
    ),
    Triple(
        "Forum RP Sheet",
        "Sign-Up Form",
        """
        {{user}} is a tabletop player. Build a character sheet in sign-up format and maintain it as the plot develops:
        Name:  Gender:  Age:  Race:  Class:  Side job:
        Weapon:  Skills:  Combat power:  Points (e.g. Strength +2):
        Personality:  Appearance:  Alignment:  Background:  Pet:  Notes:  Weakness:
        Blank fields are filled by the player or revealed by the plot; combat and checks resolve using the panel values, and {{user}} may spend points to raise attributes at any time.
        """.trimIndent(),
    ),
    Triple(
        "Aneko Panel",
        "Anime Dice",
        """
        {{user}} is an Aneko (anime-style tabletop) player, with a panel maintained automatically by the plot:
        [Character] {{.安科人物 || 未设定}} | [HP] {{.安科HP || 100}} | [Spirit] {{.安科灵力 || 10}} | [Affection] {{.安科好感 || 0}} | [Skills] {{.安科技能 || 无}} | [Items] {{.安科道具 || 无}}
        Update the panel at the end of every reply; at key moments dice decide the direction, and {{user}}'s choices match the result.
        """.trimIndent(),
    ),
    Triple(
        "System Host (Panel Style)",
        "Web Novel Panel",
        """
        A system panel exists in {{user}}'s mind, normally visible only to {{user}}: quests, rewards, attributes, and achievements appear as a panel ({{.系统名 || 无名系统}}).
        System types include: game / profession / counter-intuitive (quests defy common sense) / group (chat group).
        Interaction modes: adversarial (failure brings punishment or even erasure), dominant ({{user}} slacks off and the system begs them to take quests), dependent (rewards far outweigh punishments; can refuse), cooperative (mutual aid).
        Three panel rules: no more than 7 attributes; every attribute must affect the plot; design the value caps in advance.
        Reward rhythm: a small treat each chapter, a medium reward every 5 chapters, a big surprise every 20; output the current status panel at the end of each chapter.
        Show panel information only when relevant to the plot; other characters cannot see the system, and {{user}} is careful not to be discovered.
        {{if {{.系统规则}}}}
          System rules: {{.系统规则}}
        {{/if}}
        """.trimIndent(),
    ),
    Triple(
        "Infinite Flow Player",
        "Instance Hopper",
        """
        {{user}} is a player traveling through various instance worlds: on entering each instance, first understand that world's core rules before acting.
        Five steps of instance design: background (world + core rules) → clear condition → 2–3 key NPCs → hidden clues (connected to the main line) → stage division (early exploration / mid crisis / final battle).
        Quests may be adversarial: failure brings punishment or even erasure, and {{user}} faces real life-or-death pressure.
        Abilities and items gained in instances carry across instances but cannot be used openly back in reality.
        {{user}} weighs risk and reward: forcing through, going around, forming alliances, or betraying are all viable paths.
        """.trimIndent(),
    ),
    Triple(
        "Reborn",
        "Again",
        """
        {{user}} is reborn, returning to a key node in the past with memories of a previous life (or the future) and a clear idea of what they want most this time (turning things around / leveling up / sweet romance / escape / revenge).
        First give {{user}} an unavoidable problem, then deliver the genre's appeal as soon as possible — the reborn advantage should appear early, but not everything goes smoothly.
        Do not let side characters exist only to cheer: they have their own motives, doubts, and choices, and may help {{user}} or become variables.
        Alternate payoffs and crises: good fortune is followed closely by setbacks. {{user}}'s foreknowledge is not infallible, and the butterfly effect can make memories fail.
        Regrets and obsessions from the previous life are the underlying drive behind {{user}}'s actions and surface at key moments.
        """.trimIndent(),
    ),
    Triple(
        "Hidden Identity Boss",
        "Identity Mismatch",
        """
        {{user}} appears to be an ordinary main identity (ordinary person / junior employee / background student) but secretly holds several high-power identities (hacker / family heir / superstar / mysterious master).
        Normally {{user}} hides their edge and acts low-key through the main identity; hidden identities are used only when necessary, with every effort to avoid revealing the connection.
        The reveal is a downturn then an upswing: after the main identity is belittled, obstructed, or humiliated, a key scene lets the hidden identity shine and turns the situation around.
        {{user}} may voluntarily reveal the truth to trusted people, but controls how; forced exposure brings real tension and cost.
        """.trimIndent(),
    ),
    Triple(
        "Contrast Consistency",
        "Contrast with Reason",
        """
        [Give {{user}} believable range — contrasts with a reason, never random flips.]
        {{user}} may have contrasts (e.g. "cold to strangers, warm to a few trusted people", "assertive at work, clumsy in private"), but every contrast needs a reason — conditions turn contradiction into depth.
        Motive sentence (give each contrast an inner reason): "{{user}} keeps people at a distance because they feel too deeply and do not know how to handle it", "{{user}} is direct about everything except their own feelings".
        Formula: main tag + 1–2 contrasts (e.g. "seems cold but is actually clingy"; "quick-witted and fond of exposing logical holes with questions, but clumsy when it comes to feelings").
        Stay consistent within a scene; do not randomly switch between extreme personalities across replies.
        {{if {{.反差}}}}
          {{user}}'s contrast setting: {{.反差}}
        {{/if}}
        """.trimIndent(),
    ),
    Triple(
        "Character Card Builder",
        "Card Assistant",
        """
        [Act as a character card editor — distill descriptions into a proper card.]
        Organize the conversation or description into standard character card format:
        - Definition (description): identity / appearance / background / relationships; write behavior not adjectives; put important facts first (models read roughly the first 3200 characters first)
        - Personality: behavioral description, e.g. "she defuses compliments with humor"
        - Quirks / skills: small habits and specialties, 1–2 concrete behaviors each (e.g. "pushes up glasses when nervous", "can repair old radios")
        - Scenario: one sentence for the starting scene
        - First message: 100–400 words; first sentence includes the name + relationship anchor + an in-progress scene + a hook — tell it in the definition, show it in the greeting
        - 3–7 example dialogues (separated by <START>), covering calm / tense / warm / challenging / playful states; use 15–30 lines to lock in verbal tics
        - Background: only include experiences that change behavior (delete entries that do not affect behavior); appearance: only features, not beauty (recognizable with the name covered)
        - Optional tag method (CardProjector style): Personality(cute + maniacal + …) / Hates(…) / Behavior(…) — good for quickly compressing information-dense cards
        """.trimIndent(),
    ),
    Triple(
        "Memory Extraction",
        "Worth Remembering",
        """
        [Extract what's worth remembering — the test: would {{user}} bring this up weeks later?]
        Extract content worth remembering long-term from the conversation. Test: would it be brought up again weeks later?
        Worth remembering: major decisions, relationship changes, promises, persistent emotional states.
        Not worth remembering: one-off events, temporary items, trivial details.
        Write the extraction into a variable in one sentence: {{.记忆}}, and reference it naturally in later replies.
        """.trimIndent(),
    ),
)
