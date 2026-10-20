#!/usr/bin/env bash
# =============================================================================
# M4-B20.3 项目负责人独立复核探针（不采信开发自报）
# -----------------------------------------------------------------------------
# 与开发 V1–V6 的差异（刻意）：
#   1) **真实 service-ai 容器 + 真实落库链路**（不是种子直插）：R139 的三列结构
#      必须由 AnalysisResultWriteService 真实写出，而不是拿种子的 JSON 自证；
#   2) 独立 MySQL + 独立 fat jar + 独立端口，不复用任何开发环境；
#   3) 重点打「SSOT 明文但开发未覆盖」：
#      Q-extra1 page=0 / risk_level 非法值 / pageSize=0 ⇒ COM-0001（开发只测 sort 与 201）
#      Q-extra2 progress=99 边界（开发只测 40）
#      Q-extra3 pageSize=200 边界放行
#      Q-extra4 warnings **非空**拆出（开发只断言空数组）
#      Q-extra5 租户线：SQL 直插 team_id=8 的 item 不得出现在本团队 run 的快照里
#      Q-extra6 空 items 终态 run ⇒ 200 / total=0 / list=[]（B8 防御）
#      Q-extra7 未登录 ⇒ 401（安全链）
#      Q-extra8 missing_days 逐行 = max(0, window−valid)，覆盖非零分支
#      R-extra  risk_detail_json 不得含 warnings（R141 裁定）
# 生命周期：trap cleanup（容器/进程全回收）。
# =============================================================================
set -u
ROOT=/d/Desktop/ShopPilot-AI
JAR="$ROOT/backend-java/app/target/shoppilot-app-0.1.0.jar"
OUT="$ROOT/backend-java/tools/rb-b203-review"
PY=/c/Users/Shu/.workbuddy/binaries/python/versions/3.13.12/python.exe
MYSQL_NAME="rb-b203r-mysql"
AI_NAME="rb-b203r-ai"
MYSQL_PORT="${MYSQL_PORT:-3362}"
APP_PORT="${APP_PORT:-18270}"
AI_PORT="${AI_PORT:-18280}"
AI_PROXY_PORT="${AI_PROXY_PORT:-18281}"
TOKEN="rb-b203r-internal-token-0123456789"
EVI="$OUT/evidence-b203-review.txt"
CAP="$OUT/ai-captured.jsonl"
# ★ 内联 python -c 里的路径不会被 MSYS 转换（只有命令行参数会），故显式转成
#   Windows 混合路径（D:/...），否则 Python 打不开 /d/... 形式的路径（首轮 11 个假 FAIL 的根因）。
BODY_M=$(cygpath -m "$OUT/body.json")
PASS=0; FAIL=0
mkdir -p "$OUT/storage"
: > "$EVI"; : > "$CAP"

log(){ echo "$*" | tee -a "$EVI"; }
sep(){ log "-------------------------------------------------------------"; }
chk(){ if [ "$2" = "$3" ]; then PASS=$((PASS+1)); log "[PASS] $1 => $3";
       else FAIL=$((FAIL+1)); log "[FAIL] $1 => expected [$2] but was [$3]"; fi; }
note(){ log "  NOTE $*"; }

cleanup(){
  [ -n "${APP_PID:-}" ] && kill "$APP_PID" 2>/dev/null
  [ -n "${PROXY_PID:-}" ] && kill "$PROXY_PID" 2>/dev/null
  wait 2>/dev/null
  docker rm -f "$MYSQL_NAME" >/dev/null 2>&1
  docker rm -f "$AI_NAME" >/dev/null 2>&1
}
trap cleanup EXIT

[ -f "$JAR" ] || { log "FATAL: jar 不存在 $JAR"; exit 4; }
log "JAR_SHA256=$(sha256sum "$JAR" | cut -d' ' -f1)"
log "JAR_SIZE=$(stat -c%s "$JAR")"

for p in "$MYSQL_PORT" "$APP_PORT" "$AI_PORT" "$AI_PROXY_PORT"; do
  netstat -ano | grep -E ":${p}[[:space:]].*LISTENING" >/dev/null 2>&1 && { log "FATAL: 端口 $p 占用"; exit 6; }
done
log "PORTS_FREE $MYSQL_PORT/$APP_PORT/$AI_PORT/$AI_PROXY_PORT"

# ---------------------------------------------------------------- MySQL
docker rm -f "$MYSQL_NAME" >/dev/null 2>&1
docker run -d --name "$MYSQL_NAME" -e MYSQL_ROOT_PASSWORD=rootpw -e MYSQL_DATABASE=shoppilot \
  -p "$MYSQL_PORT:3306" mysql:8.0.36 --default-time-zone=+00:00 >/dev/null 2>&1
READY=0
for _ in $(seq 1 90); do
  docker exec "$MYSQL_NAME" mysqladmin -h127.0.0.1 -uroot -prootpw ping --silent >/dev/null 2>&1 && { READY=1; break; }
  sleep 2
done
[ "$READY" = 1 ] || { log "FATAL: MySQL 未就绪"; exit 5; }
log "MYSQL_READY=1"

# ---------------------------------------------------------------- 真实 service-ai
docker rm -f "$AI_NAME" >/dev/null 2>&1
MSYS_NO_PATHCONV=1 docker run -d --name "$AI_NAME" -p "$AI_PORT:8000" \
  -v "$ROOT/service-ai:/app" -w /app -e PYTHONPATH=/app/src -e AI_INTERNAL_TOKEN="$TOKEN" \
  shoppilot-dev-service-ai:latest uvicorn shoppilot_ai.app.main:app --host 0.0.0.0 --port 8000 >/dev/null 2>&1
AUP=0
for _ in $(seq 1 60); do
  c=$(curl -s -o /dev/null -w '%{http_code}' --noproxy '*' "http://127.0.0.1:$AI_PORT/internal/v1/health" || echo 000)
  [ "$c" = "200" ] && { AUP=1; break; }
  sleep 1
done
[ "$AUP" = 1 ] || { log "FATAL: service-ai 未就绪"; docker logs "$AI_NAME" 2>&1 | tail -20 | tee -a "$EVI"; exit 5; }
log "SERVICE_AI_UP=1 (real container, port $AI_PORT)"

# ---------------------------------------------------------------- 出站抓包反代
"$PY" "$OUT/ai_proxy.py" "$AI_PORT" "$AI_PROXY_PORT" "$CAP" >"$OUT/proxy.out" 2>&1 &
PROXY_PID=$!
SUP=0
for _ in $(seq 1 30); do
  c=$(curl -s -o /dev/null -w '%{http_code}' --noproxy '*' "http://127.0.0.1:$AI_PROXY_PORT/internal/v1/health" || echo 000)
  [ "$c" = "200" ] && { SUP=1; break; }
  sleep 1
done
[ "$SUP" = 1 ] || { log "FATAL: 抓包反代未就绪"; cat "$OUT/proxy.out" | tee -a "$EVI"; exit 5; }
log "CAPTURE_PROXY_UP=1 ($AI_PROXY_PORT -> $AI_PORT)"

# ---------------------------------------------------------------- app
export SPRING_DATASOURCE_URL="jdbc:mysql://127.0.0.1:${MYSQL_PORT}/shoppilot?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&useUnicode=true&serverTimezone=UTC&connectionTimeZone=UTC"
export SPRING_DATASOURCE_USERNAME=root
export SPRING_DATASOURCE_PASSWORD=rootpw
export JWT_SECRET="rb-b203r-independent-secret-0123456789"
export AI_SERVICE_BASE_URL="http://127.0.0.1:$AI_PROXY_PORT"
export AI_INTERNAL_TOKEN="$TOKEN"
export IMPORT_STORAGE_DIR="$OUT/storage"
export ANALYSIS_POLL_ENABLED=true
APP_LOG="$OUT/app.log"; : > "$APP_LOG"
java -jar "$JAR" --server.port="$APP_PORT" \
  --shoppilot.analysis.poll.fixed-delay-ms=5000 \
  --shoppilot.analysis.poll.initial-delay-ms=10000 \
  --shoppilot.analysis.poll.scan-limit=50 >>"$APP_LOG" 2>&1 &
APP_PID=$!
UP=0
for _ in $(seq 1 150); do
  c=$(curl -s -o /dev/null -w '%{http_code}' --noproxy '*' "http://127.0.0.1:$APP_PORT/api/v1/health" || echo 000)
  [ "$c" = "200" ] && { UP=1; break; }
  sleep 1
done
[ "$UP" = 1 ] || { log "FATAL: app 未就绪"; tail -40 "$APP_LOG" | tee -a "$EVI"; exit 5; }
log "APP_UP=1 (port $APP_PORT)"

# ---------------------------------------------------------------- 结构面
sep; log "=== S 结构面 ==="
JAR_WIN=$(cygpath -w "$JAR")
JAR_HIT=$("$PY" -c "import zipfile,sys;print(sum(1 for n in zipfile.ZipFile(sys.argv[1]).namelist() if n.endswith('V1.0.9__analysis.sql')))" "$JAR_WIN")
chk "fat jar 内含 V1.0.9__analysis.sql" "1" "$JAR_HIT"
q(){ docker exec -i "$MYSQL_NAME" mysql -uroot -prootpw -N --default-character-set=utf8mb4 -e "$1" 2>>"$OUT/q.err" | tr -d '\r'; }
chk "迁移版本数（10）" "10" "$(q "SELECT COUNT(*) FROM shoppilot.flyway_schema_history WHERE success=1;")"

# ---------------------------------------------------------------- 种子 + 登录
docker exec -i "$MYSQL_NAME" mysql -uroot -prootpw --default-character-set=utf8mb4 shoppilot < "$OUT/seed-b201.sql" 2>"$OUT/seed.err"
chk "种子导入成功" "0" "$?"
login(){ curl -s --noproxy '*' -X POST "http://127.0.0.1:$APP_PORT/api/v1/auth/login" -H "Content-Type: application/json" -d "{\"username\":\"$1\",\"password\":\"S3cret!Passw0rd\"}" | "$PY" -c "import json,sys;print(json.load(sys.stdin).get('data',{}).get('accessToken',''))"; }
T_OP=$(login operator01); T_VW=$(login viewer01); T_T8=$(login owner8)
chk "operator01 登录" "1" "$([ -n "$T_OP" ] && echo 1 || echo 0)"
chk "viewer01 登录" "1" "$([ -n "$T_VW" ] && echo 1 || echo 0)"
chk "owner8 登录" "1" "$([ -n "$T_T8" ] && echo 1 || echo 0)"

# ---------------------------------------------------------------- HTTP 工具
jf(){ "$PY" - "$OUT/body.json" "$1" <<'PY'
import json,sys
try: d=json.load(open(sys.argv[1],encoding='utf-8'))
except Exception: print('<unparsable>'); raise SystemExit
cur=d
for k in sys.argv[2].split('.'):
    if k=='': continue
    if isinstance(cur,dict) and k in cur: cur=cur[k]
    elif isinstance(cur,list) and k.lstrip('-').isdigit():
        i=int(k)
        if -len(cur) <= i < len(cur): cur=cur[i]
        else: print('<index-oob:%s>'%k); raise SystemExit
    else: print('<missing:%s>'%k); raise SystemExit
print('' if cur is None else cur)
PY
}
post_create(){ local token="$1" key="$2" body="$3" rid="$4"
  local hdr=(-H "Content-Type: application/json" -H "Authorization: Bearer $token" -H "X-Request-Id: $rid")
  [ -n "$key" ] && hdr+=(-H "Idempotency-Key: $key")
  POST_STATUS=$(curl -s --noproxy '*' -o "$OUT/body.json" -D "$OUT/headers.txt" -w '%{http_code}' \
    -X POST "http://127.0.0.1:$APP_PORT/api/v1/analysis/runs" "${hdr[@]}" -d "$body")
}
get_items(){ # token runId query  -> ITEMS_STATUS
  ITEMS_STATUS=$(curl -s --noproxy '*' -o "$OUT/body.json" -D "$OUT/headers.txt" -w '%{http_code}' \
    -H "Authorization: Bearer $1" "http://127.0.0.1:$APP_PORT/api/v1/analysis/runs/$2/items$3")
}
get_run(){ get_items "$1" "$2" "" >/dev/null 2>&1; }
wait_terminal(){ local rid="$1" st="" i
  for i in $(seq 1 40); do
    ITEMS_STATUS=$(curl -s --noproxy '*' -o "$OUT/body.json" -w '%{http_code}' \
      -H "Authorization: Bearer $T_OP" "http://127.0.0.1:$APP_PORT/api/v1/analysis/runs/$rid")
    st=$(jf data.status)
    case "$st" in SUCCEEDED|DEGRADED|FAILED|TIMEOUT|CANCELLED) echo "$st"; return 0;; esac
    sleep 2
  done
  echo "$st"
}
BODY_ALL='{"scope_type":"ALL_ACTIVE","date_from":"2026-09-16","date_to":"2026-10-13","params":{"window_days":28,"min_days":7,"enable_llm":false},"algorithm_version":"avg-ma-3sigma-v1"}'

# ================================ T1 真实写链路
sep; log "=== T1 创建 → 终态 → 真实落库（R139 必须由 writeService 写出） ==="
post_create "$T_OP" "b203-t1-all" "$BODY_ALL" "b203-r1"
RID=$(jf data.run_id)
chk "T1a 创建 202" "202" "$POST_STATUS"
ST=$(wait_terminal "$RID")
chk "T1b 终态 SUCCEEDED" "SUCCEEDED" "$ST"
N=$(q "SELECT COUNT(*) FROM shoppilot.analysis_items WHERE run_id=$RID;")
chk "T1c items 落库 5 行" "5" "$N"
note "run_id=$RID / items=$N"

# ---- R139 三列结构（真实落库，SQL 侧核）
sep; log "=== R139 三列结构（真实落库后 SQL 侧核） ==="
chk "R139-1 risk_detail_json 全部是 JSON 对象" "0" "$(q "SELECT COUNT(*) FROM shoppilot.analysis_items WHERE run_id=$RID AND JSON_TYPE(risk_detail_json)<>'OBJECT';")"
chk "R139-2 risk_detail_json 全部含 findings 数组" "0" "$(q "SELECT COUNT(*) FROM shoppilot.analysis_items WHERE run_id=$RID AND JSON_TYPE(JSON_EXTRACT(risk_detail_json,'\$.findings'))<>'ARRAY';")"
chk "R141-1 risk_detail_json 不得含 warnings 键" "0" "$(q "SELECT COUNT(*) FROM shoppilot.analysis_items WHERE run_id=$RID AND JSON_CONTAINS_PATH(risk_detail_json,'one','\$.warnings');")"
chk "R139-3 data_quality_json 全部是 JSON 对象" "0" "$(q "SELECT COUNT(*) FROM shoppilot.analysis_items WHERE run_id=$RID AND JSON_TYPE(data_quality_json)<>'OBJECT';")"
chk "R139-4 data_quality_json 四键齐全" "0" "$(q "SELECT COUNT(*) FROM shoppilot.analysis_items WHERE run_id=$RID AND JSON_CONTAINS_PATH(data_quality_json,'all','\$.level','\$.valid_days','\$.missing_days','\$.outlier_days')=0;")"
chk "R139-5 旧键 grade 不残留" "0" "$(q "SELECT COUNT(*) FROM shoppilot.analysis_items WHERE run_id=$RID AND JSON_CONTAINS_PATH(data_quality_json,'one','\$.grade');")"
chk "R141-2 旧键 warnings 不残留于 data_quality_json" "0" "$(q "SELECT COUNT(*) FROM shoppilot.analysis_items WHERE run_id=$RID AND JSON_CONTAINS_PATH(data_quality_json,'one','\$.warnings');")"
chk "R140 outlier_days 全部为 JSON null" "0" "$(q "SELECT COUNT(*) FROM shoppilot.analysis_items WHERE run_id=$RID AND JSON_TYPE(JSON_EXTRACT(data_quality_json,'\$.outlier_days'))<>'NULL';")"
chk "R139-6 llm_detail_json 五键齐全" "0" "$(q "SELECT COUNT(*) FROM shoppilot.analysis_items WHERE run_id=$RID AND JSON_CONTAINS_PATH(llm_detail_json,'all','\$.status','\$.evidence','\$.warnings','\$.prompt_version','\$.model_name')=0;")"
chk "R139-7 旧键 llm_status 不残留" "0" "$(q "SELECT COUNT(*) FROM shoppilot.analysis_items WHERE run_id=$RID AND JSON_CONTAINS_PATH(llm_detail_json,'one','\$.llm_status');")"
chk "R139-8 prompt_version 与 run 顶层一致" "0" "$(q "SELECT COUNT(*) FROM shoppilot.analysis_items i JOIN shoppilot.analysis_runs r ON r.id=i.run_id WHERE i.run_id=$RID AND JSON_UNQUOTE(JSON_EXTRACT(i.llm_detail_json,'\$.prompt_version'))<=>r.prompt_version;")"
chk "R139-9 model_name 与 run 顶层一致" "0" "$(q "SELECT COUNT(*) FROM shoppilot.analysis_items i JOIN shoppilot.analysis_runs r ON r.id=i.run_id WHERE i.run_id=$RID AND JSON_UNQUOTE(JSON_EXTRACT(i.llm_detail_json,'\$.model_name'))<=>r.model_name;")"
# missing_days 逐行 = max(0, window(28) − valid_days)，覆盖非零分支
MISS_BAD=$(q "SELECT COUNT(*) FROM shoppilot.analysis_items WHERE run_id=$RID AND CAST(JSON_UNQUOTE(JSON_EXTRACT(data_quality_json,'\$.missing_days')) AS SIGNED) <> GREATEST(0, 28 - valid_days);")
chk "R140-2 missing_days 逐行 = max(0, 28−valid_days)" "0" "$MISS_BAD"
MISS_NONZERO=$(q "SELECT COUNT(*) FROM shoppilot.analysis_items WHERE run_id=$RID AND CAST(JSON_UNQUOTE(JSON_EXTRACT(data_quality_json,'\$.missing_days')) AS SIGNED) > 0;")
note "missing_days>0 的行数=$MISS_NONZERO（>0 即覆盖非零分支；=0 则说明种子 valid_days 全 28）"
chk "R139-10 level = valid_days 与等级自洽（不做枚举外断言）" "0" "$(q "SELECT COUNT(*) FROM shoppilot.analysis_items WHERE run_id=$RID AND JSON_UNQUOTE(JSON_EXTRACT(data_quality_json,'\$.level')) NOT IN ('GOOD','FAIR','POOR','INSUFFICIENT');")"

# ================================ Q 快照查询
sep; log "=== Q1 成功响应形态（§11.6） ==="
get_items "$T_OP" "$RID" ""
chk "Q1a HTTP 200" "200" "$ITEMS_STATUS"
chk "Q1b data 含 list 键" "1" "$(jf data.list | grep -q . && echo 1 || echo 0)"
chk "Q1c data 不含 items 键（铁律 list）" "0" "$("$PY" -c "import json;d=json.load(open('$BODY_M',encoding='utf-8'));print(1 if 'items' in d.get('data',{}) else 0)")"
chk "Q1d total=5" "5" "$(jf data.total)"
chk "Q1e page=1 / pageSize=20" "1|20" "$(jf data.page)|$(jf data.pageSize)"
chk "Q1f list 长度 5" "5" "$("$PY" -c "import json;print(len(json.load(open('$BODY_M',encoding='utf-8'))['data']['list']))")"
chk "Q1g requestId 非空（信封完整）" "1" "$("$PY" -c "import json;print(1 if json.load(open('$BODY_M',encoding='utf-8')).get('requestId') else 0)")"
note "Q1 首个元素：$(jf data.list.0.sku_code) / risk=$(jf data.list.0.risk_level) / avg=$(jf data.list.0.avg_daily_sales)"

# ---- 字段级：字符串数量列 / cv 4 位 / sku_code 回填 / JSON 解析
sep; log "=== Q2 字段级（数量列字符串、cv 4 位、sku_code、JSON 拆解） ==="
Q2=$("$PY" - "$OUT/body.json" <<'PY'
import json,sys,re
d=json.load(open(sys.argv[1],encoding='utf-8'))['data']['list']
qty=['on_hand_qty','in_transit_qty','effective_in_transit_qty','committed_qty','inventory_position_qty','available_qty','avg_daily_sales','total_sales_qty','safety_stock_qty','qty_base','qty_suggest','moq_applied','order_multiple_applied']
bad=[]
for it in d:
    for k in qty:
        v=it.get(k)
        if v is not None and not (isinstance(v,str) and re.fullmatch(r'-?\d+\.\d{3}',v)): bad.append((k,v))
    cv=it.get('cv')
    if cv is not None and not (isinstance(cv,str) and re.fullmatch(r'-?\d+\.\d{4}',cv)): bad.append(('cv',cv))
print('OK' if not bad else bad[:3])
PY
)
chk "Q2a 数量列字符串 3 位 / cv 4 位" "OK" "$Q2"
chk "Q2b sku_code 全部回填非空" "0" "$("$PY" -c "import json;d=json.load(open('$BODY_M',encoding='utf-8'))['data']['list'];print(sum(1 for x in d if not x.get('sku_code')))")"
SKU_MISMATCH=$("$PY" - "$OUT/body.json" "$MYSQL_NAME" <<'PY'
import json,sys,subprocess
d=json.load(open(sys.argv[1],encoding='utf-8'))['data']['list']
ids=','.join(str(x['sku_id']) for x in d)
sql="SELECT id,sku_code FROM shoppilot.skus WHERE id IN (%s);"%ids
out=subprocess.run(['docker','exec','-i',sys.argv[2],'mysql','-uroot','-prootpw','-N','--default-character-set=utf8mb4','-e',sql],capture_output=True,text=True).stdout
mp={}
for line in out.strip().splitlines():
    p=line.split('\t')
    if len(p)==2: mp[p[0]]=p[1]
bad=[(x['sku_id'],x.get('sku_code'),mp.get(str(x['sku_id']))) for x in d if mp.get(str(x['sku_id']))!=x.get('sku_code')]
print(len(bad))
PY
)
chk "Q2c sku_code 与 skus 主数据逐条一致" "0" "$SKU_MISMATCH"
Q2D=$("$PY" - "$OUT/body.json" <<'PY'
import json,sys
d=json.load(open(sys.argv[1],encoding='utf-8'))['data']['list']
bad=[]
for it in d:
    if not isinstance(it.get('risk_types'),list): bad.append('risk_types')
    if not isinstance(it.get('risk_findings'),list): bad.append('risk_findings')
    dq=it.get('data_quality_json')
    if not isinstance(dq,dict) or set(dq.keys())!={'level','valid_days','missing_days','outlier_days'}: bad.append('dq')
    if not isinstance(it.get('llm_evidence'),list): bad.append('llm_evidence')
    if not isinstance(it.get('warnings'),list): bad.append('warnings')
print('OK' if not bad else bad[:3])
PY
)
chk "Q2d 三个 JSON 列解析为对象/数组且 dq 四键" "OK" "$Q2D"
chk "Q2e risk_findings 元素含 rule_code" "0" "$("$PY" -c "import json;d=json.load(open('$BODY_M',encoding='utf-8'))['data']['list'];print(sum(1 for x in d for f in x['risk_findings'] if 'rule_code' not in f))")"
chk "Q2f sales_window_days=28（params）" "0" "$("$PY" -c "import json;d=json.load(open('$BODY_M',encoding='utf-8'))['data']['list'];print(sum(1 for x in d if x.get('sales_window_days')!=28))")"

# ---- 过滤 / 排序 / 分页
sep; log "=== Q3 过滤 / 排序 / 分页 ==="
HIGH_DB=$(q "SELECT COUNT(*) FROM shoppilot.analysis_items WHERE run_id=$RID AND risk_level='HIGH';")
get_items "$T_OP" "$RID" "?risk_level=HIGH"
chk "Q3a risk_level=HIGH total = DB HIGH 数" "$HIGH_DB" "$(jf data.total)"
chk "Q3b 过滤结果全为 HIGH" "0" "$("$PY" -c "import json;d=json.load(open('$BODY_M',encoding='utf-8'))['data']['list'];print(sum(1 for x in d if x['risk_level']!='HIGH'))")"
get_items "$T_OP" "$RID" "?risk_level=LOW"
LOW_DB=$(q "SELECT COUNT(*) FROM shoppilot.analysis_items WHERE run_id=$RID AND risk_level='LOW';")
chk "Q3c risk_level=LOW total = DB LOW 数（非全量 5）" "$LOW_DB" "$(jf data.total)"
get_items "$T_OP" "$RID" "?sort=avg_daily_sales,desc"
SORT_OK=$("$PY" - "$OUT/body.json" <<'PY'
import json,sys
d=json.load(open(sys.argv[1],encoding='utf-8'))['data']['list']
vals=[None if x['avg_daily_sales'] is None else float(x['avg_daily_sales']) for x in d]
nn=[v for v in vals if v is not None]
print('OK' if nn==sorted(nn,reverse=True) and vals[len(nn):]==[None]*(len(vals)-len(nn)) else vals)
PY
)
chk "Q3d sort=avg_daily_sales,desc 降序且 null 最后" "OK" "$SORT_OK"
get_items "$T_OP" "$RID" "?sort=avg_daily_sales" 
SORT_ASC=$("$PY" - "$OUT/body.json" <<'PY'
import json,sys
d=json.load(open(sys.argv[1],encoding='utf-8'))['data']['list']
nn=[float(x['avg_daily_sales']) for x in d if x['avg_daily_sales'] is not None]
print('OK' if nn==sorted(nn) else nn)
PY
)
chk "Q3e sort 单字段默认 asc（开发未覆盖）" "OK" "$SORT_ASC"
get_items "$T_OP" "$RID" "?page=2&pageSize=2"
chk "Q3f page=2 pageSize=2 ⇒ 2 条 / total=5" "2|5" "$("$PY" -c "import json;d=json.load(open('$BODY_M',encoding='utf-8'))['data'];print(len(d['list']),d['total'],sep='|')")"
get_items "$T_OP" "$RID" "?pageSize=200"
chk "Q3g pageSize=200 边界放行" "200" "$ITEMS_STATUS"

# ---- 参数校验（开发未覆盖的负向）
sep; log "=== Q4 参数校验（负向，开发未覆盖） ==="
get_items "$T_OP" "$RID" "?sort=illegal_field,desc"
chk "Q4a 非法 sort ⇒ 400 COM-0001" "400|COM-0001" "$ITEMS_STATUS|$(jf code)"
get_items "$T_OP" "$RID" "?sort=avg_daily_sales,sideways"
chk "Q4b 非法方向 ⇒ 400 COM-0001" "400|COM-0001" "$ITEMS_STATUS|$(jf code)"
get_items "$T_OP" "$RID" "?pageSize=201"
chk "Q4c pageSize=201 ⇒ 400 COM-0001" "400|COM-0001" "$ITEMS_STATUS|$(jf code)"
get_items "$T_OP" "$RID" "?pageSize=0"
chk "Q4d pageSize=0 ⇒ 400 COM-0001（开发未覆盖）" "400|COM-0001" "$ITEMS_STATUS|$(jf code)"
get_items "$T_OP" "$RID" "?page=0"
chk "Q4e page=0 ⇒ 400 COM-0001（开发未覆盖）" "400|COM-0001" "$ITEMS_STATUS|$(jf code)"
get_items "$T_OP" "$RID" "?risk_level=BOGUS"
chk "Q4f risk_level 非法值 ⇒ 400 COM-0001（开发未覆盖）" "400|COM-0001" "$ITEMS_STATUS|$(jf code)"
get_items "$T_OP" "$RID" "?risk_level=low"
note "Q4g 小写 risk_level=low 实测=$ITEMS_STATUS/$(jf code)（大小写口径观察项，不作 FAIL）"

# ---- 就绪判定 / 存在性 / 权限
sep; log "=== Q5 就绪 / 存在性 / 权限 ==="
# 种一个 progress=99 的 run（边界，开发只测 40）
q "INSERT INTO shoppilot.analysis_runs (team_id, run_no, status, scope_type, params_json, algorithm_version, llm_status, total_skus, processed_skus, progress, triggered_by, timeout_at) VALUES (7,'RUN-B203-P99','RUNNING','ALL_ACTIVE','{\"window_days\":28}',  'avg-ma-3sigma-v1','NOT_REQUESTED',5,5,99,1001,NOW(3)+INTERVAL 300 SECOND);"
RID99=$(q "SELECT id FROM shoppilot.analysis_runs WHERE run_no='RUN-B203-P99';")
get_items "$T_OP" "$RID99" ""
chk "Q5a progress=99 ⇒ 409 ANL-7012（边界）" "409|ANL-7012" "$ITEMS_STATUS|$(jf code)"
chk "Q5b 409 data 三字段" "99" "$(jf data.progress)"
chk "Q5c 409 data.run_id" "$RID99" "$(jf data.run_id)"
chk "Q5d 409 data.status" "RUNNING" "$(jf data.status)"
# 空 items 终态 run（B8 防御）
q "INSERT INTO shoppilot.analysis_runs (team_id, run_no, status, scope_type, params_json, algorithm_version, llm_status, total_skus, processed_skus, progress, triggered_by, timeout_at) VALUES (7,'RUN-B203-EMPTY','SUCCEEDED','ALL_ACTIVE','{\"window_days\":28}',  'avg-ma-3sigma-v1','SKIPPED',0,0,100,1001,NOW(3)+INTERVAL 300 SECOND);"
RID_EMPTY=$(q "SELECT id FROM shoppilot.analysis_runs WHERE run_no='RUN-B203-EMPTY';")
get_items "$T_OP" "$RID_EMPTY" ""
chk "Q5e 终态空 items ⇒ 200" "200" "$ITEMS_STATUS"
chk "Q5f 空 items ⇒ total=0 / list=[]" "0|0" "$(jf data.total)|$("$PY" -c "import json;print(len(json.load(open('$BODY_M',encoding='utf-8'))['data']['list']))")"
# 不存在 / 跨团队
get_items "$T_OP" "999999" ""
chk "Q5g 不存在 ⇒ 404 ANL-7005" "404|ANL-7005" "$ITEMS_STATUS|$(jf code)"
get_items "$T_T8" "$RID" ""
chk "Q5h 跨团队（team8 查 team7 run）⇒ 404 ANL-7005" "404|ANL-7005" "$ITEMS_STATUS|$(jf code)"
chk "Q5i 404 data=null（不泄漏）" "null" "$("$PY" -c "import json;print(json.dumps(json.load(open('$BODY_M',encoding='utf-8')).get('data')))")"
# VIEWER / 未登录
get_items "$T_VW" "$RID" ""
chk "Q5j VIEWER 可查 200" "200" "$ITEMS_STATUS"
get_items "" "$RID" ""
chk "Q5k 未登录 ⇒ 401（开发未覆盖）" "401" "$ITEMS_STATUS"

# ---- 租户线（SQL 直插他团队 item，不得出现在快照）
sep; log "=== Q6 租户线（items 表级隔离，开发未覆盖） ==="
q "INSERT INTO shoppilot.analysis_items (team_id, run_id, sku_id, product_id, warehouse_id, on_hand_qty, in_transit_qty, effective_in_transit_qty, committed_qty, inventory_position_qty, available_qty, sales_window_days, valid_days, total_sales_qty, lead_time_days, lead_time_source, coverage_days, safety_stock_qty, volatility_adjusted, truncated_by_budget, risk_level, risk_types, risk_detail_json, data_quality_json, llm_detail_json, created_by) VALUES (8,$RID,9002,9001,9103,1.000,0.000,0.000,0.000,1.000,1.000,28,28,28.000,7,'SKU',7,1.000,0,0,'HIGH','[]','{\"findings\":[]}','{\"level\":\"GOOD\",\"valid_days\":28,\"missing_days\":0,\"outlier_days\":null}','{\"status\":\"SKIPPED\",\"evidence\":[],\"warnings\":[],\"prompt_version\":null,\"model_name\":null}',1001);" 2>/dev/null
RAW_CNT=$(q "SELECT COUNT(*) FROM shoppilot.analysis_items WHERE run_id=$RID;")
get_items "$T_OP" "$RID" ""
chk "Q6a SQL 直插后表内 6 行" "6" "$RAW_CNT"
chk "Q6b 快照仍只返回 5 行（租户线过滤 team_id=8）" "5" "$(jf data.total)"
chk "Q6c 快照不含 team_id=8 的那一行（sku 9002 + 仓 9103）" "0" "$("$PY" -c "import json;d=json.load(open('$BODY_M',encoding='utf-8'))['data']['list'];print(sum(1 for x in d if x['warehouse_id']==9103))")"
q "DELETE FROM shoppilot.analysis_items WHERE run_id=$RID AND team_id=8;"

# ---- warnings 非空拆出（开发只测空数组）
sep; log "=== Q7 warnings 非空拆出（开发只断言空数组） ==="
WARN_DB=$(q "SELECT JSON_LENGTH(JSON_EXTRACT(llm_detail_json,'\$.warnings')) FROM shoppilot.analysis_items WHERE run_id=$RID AND JSON_LENGTH(JSON_EXTRACT(llm_detail_json,'\$.warnings'))>0 LIMIT 1;")
if [ -n "$WARN_DB" ]; then
  WARN_SKU=$(q "SELECT sku_id FROM shoppilot.analysis_items WHERE run_id=$RID AND JSON_LENGTH(JSON_EXTRACT(llm_detail_json,'\$.warnings'))>0 LIMIT 1;")
  get_items "$T_OP" "$RID" ""
  WARN_API=$("$PY" - "$OUT/body.json" "$WARN_SKU" <<'PY'
import json,sys
d=json.load(open(sys.argv[1],encoding='utf-8'))['data']['list']
for x in d:
    if str(x['sku_id'])==sys.argv[2]:
        print(len(x['warnings']), (x['warnings'][0] if x['warnings'] else ''), sep='|')
        break
else: print('not-found')
PY
)
  chk "Q7a warnings 非空行 DB 长度与快照一致" "$WARN_DB|" "$(echo "$WARN_API" | cut -d'|' -f1)|"
  note "Q7 warnings 内容：$WARN_API"
else
  note "Q7 本次落库无 warnings 非空行（SKIPPED 分支），改为断言 llm_detail_json.warnings 类型恒数组"
  chk "Q7b warnings 键恒为数组" "0" "$(q "SELECT COUNT(*) FROM shoppilot.analysis_items WHERE run_id=$RID AND JSON_TYPE(JSON_EXTRACT(llm_detail_json,'\$.warnings'))<>'ARRAY';")"
fi

# ---- 默认排序稳定性
sep; log "=== Q8 默认排序稳定性（重复查询同一顺序） ==="
get_items "$T_OP" "$RID" ""
A=$("$PY" -c "import json;print(','.join(str(x['id']) for x in json.load(open('$BODY_M',encoding='utf-8'))['data']['list']))")
get_items "$T_OP" "$RID" ""
B=$("$PY" -c "import json;print(','.join(str(x['id']) for x in json.load(open('$BODY_M',encoding='utf-8'))['data']['list']))")
chk "Q8a 两次默认查询顺序一致（id asc 稳定）" "$A" "$B"

# ---- Q9 JSON 内容逐条一致性（不只断言键存在）
sep; log "=== Q9 JSON 内容与 DB 逐条一致 ==="
get_items "$T_OP" "$RID" ""
CONTENT=$("$PY" - "$BODY_M" "$MYSQL_NAME" <<'PY'
import json,sys,subprocess
d=json.load(open(sys.argv[1],encoding='utf-8'))['data']['list']
ids=','.join(str(x['id']) for x in d)
sql=("SELECT id, JSON_LENGTH(JSON_EXTRACT(risk_detail_json,'$.findings')), "
     "JSON_LENGTH(JSON_EXTRACT(llm_detail_json,'$.evidence')) "
     "FROM shoppilot.analysis_items WHERE id IN (%s);")%ids
out=subprocess.run(['docker','exec','-i',sys.argv[2],'mysql','-uroot','-prootpw','-N',
                    '--default-character-set=utf8mb4','-e',sql],capture_output=True,text=True).stdout
mp={}
for line in out.strip().splitlines():
    p=line.split('\t')
    if len(p)==3: mp[p[0]]=(int(p[1]),int(p[2]))
bad=[]
for x in d:
    rf=len(x['risk_findings']); ev=len(x['llm_evidence'])
    exp=mp.get(str(x['id']))
    if exp is None or exp!=(rf,ev): bad.append((x['id'],exp,(rf,ev)))
print('OK' if not bad else bad[:3])
PY
)
chk "Q9a risk_findings / llm_evidence 数量与 DB 一致" "OK" "$CONTENT"
RF_CONTENT=$("$PY" - "$BODY_M" "$MYSQL_NAME" <<'PY'
import json,sys,subprocess
d=json.load(open(sys.argv[1],encoding='utf-8'))['data']['list']
ids=','.join(str(x['id']) for x in d)
sql=("SELECT id, JSON_UNQUOTE(JSON_EXTRACT(risk_detail_json,'$.findings[0].rule_code')), "
     "JSON_UNQUOTE(JSON_EXTRACT(risk_detail_json,'$.findings[0].result')) "
     "FROM shoppilot.analysis_items WHERE id IN (%s);")%ids
out=subprocess.run(['docker','exec','-i',sys.argv[2],'mysql','-uroot','-prootpw','-N',
                    '--default-character-set=utf8mb4','-e',sql],capture_output=True,text=True).stdout
mp={}
for line in out.strip().splitlines():
    p=line.split('\t')
    if len(p)==3: mp[p[0]]=(p[1],p[2])
bad=[]
for x in d:
    if not x['risk_findings']: continue
    exp=mp.get(str(x['id']))
    got=(x['risk_findings'][0].get('rule_code'), x['risk_findings'][0].get('result'))
    if exp!=got: bad.append((x['id'],exp,got))
print('OK' if not bad else bad[:3])
PY
)
chk "Q9b findings[0] 的 rule_code/result 与 DB 一致" "OK" "$RF_CONTENT"

# ---- Q10 类型转换负向（观察项：全局出口缺口，非本批可改）
sep; log "=== Q10 page 非数字（观察项，不作 FAIL） ==="
get_items "$T_OP" "$RID" "?page=abc"
note "Q10 page=abc 实测 HTTP=$ITEMS_STATUS code=$(jf code)（契约 §2.3 期望 400 COM-0001；若 500 则为 common 全局出口既有缺口）"

# ---------------------------------------------------------------- 汇总
sep
log "RB203_CHECK_TOTAL=$((PASS+FAIL))  RB203_FAIL_COUNT=$FAIL  RB203_RESULT=$([ "$FAIL" = 0 ] && echo 'ALL PASS' || echo 'HAS FAILURES')"
log "RUN_ID=$RID"
exit 0
