#!/usr/bin/env bash
# 用华灯大模型2.0实测 humanize_system_prompt.md 的效果
set -u
API="http://47.92.79.42:8080/v1/chat/completions"
KEY="sk-octopus-ItP5Ky2KkY7ELSzND07y9LrJXDRc7sRGP5KkTrpUsby1MHgZ"
MODEL="华灯大模型2.0"
PROMPT_FILE="$(dirname "$0")/humanize_system_prompt.md"

run_case() {
  local label="$1" user_msg="$2"
  python3 - "$API" "$KEY" "$MODEL" "$PROMPT_FILE" "$label" "$user_msg" <<'PYEOF'
import json, sys, urllib.request

api, key, model, prompt_file, label, user_msg = sys.argv[1:7]
system = open(prompt_file, encoding="utf-8").read()
payload = json.dumps({
    "model": model,
    "messages": [
        {"role": "system", "content": system},
        {"role": "user", "content": user_msg},
    ],
    "stream": False,
}).encode()
req = urllib.request.Request(api, data=payload, headers={
    "Content-Type": "application/json",
    "Authorization": f"Bearer {key}",
})
try:
    with urllib.request.urlopen(req, timeout=120) as r:
        data = json.loads(r.read())
    content = data["choices"][0]["message"]["content"]
    usage = data.get("usage", {})
    print(f"===== [{label}] user: {user_msg}")
    print(content.strip())
    print(f"----- (completion_tokens={usage.get('completion_tokens')})")
except Exception as e:
    print(f"===== [{label}] FAILED: {e}")
PYEOF
}

run_case "协作排查" "在吗，你那边能连上我手机不"
run_case "情绪接住" "唉今天加班到现在才吃上饭，累死了"
run_case "专业陷阱" "帮我看下什么是HTTPS，说不太明白"
run_case "极短触发" "在吗"
