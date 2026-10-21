#!/usr/bin/env bash
# =============================================================================
# M7-T05 Docker Compose 部署验证（PROJECT_STRUCTURE §7.1 的 7 项清单）
# -----------------------------------------------------------------------------
# 前置：deploy/.env 可不存在（用 compose 内联 dev 默认值）；栈已 `up -d --build`。
# 用法：bash backend-java/tools/m7-t05/verify-compose.sh [--with-recreate]
#   --with-recreate：额外做第 7 项「down 后 up 数据保留」（会短暂停栈，约 40s）
# 端口：nginx 8081 / backend 8080 / mysql 3306（dev 栈默认；宿主 8000 必须【不可达】）
# =============================================================================
set -u
ROOT=/d/Desktop/ShopPilot-AI
OUT="$ROOT/backend-java/tools/m7-t05"
EVI="$OUT/evidence-t05.txt"
CF="$ROOT/deploy/docker-compose.dev.yml"
BASE="http://127.0.0.1:8081"
PASS=0; FAIL=0
mkdir -p "$OUT"; : > "$EVI"
log(){ echo "$*" | tee -a "$EVI"; }
chk(){ if [ "$2" = "$3" ]; then PASS=$((PASS+1)); log "[PASS] $1 => $3";
       else FAIL=$((FAIL+1)); log "[FAIL] $1 => expected [$2] but was [$3]"; fi; }
chk_gt(){ if [ "${3%.*}" -gt "$2" ] 2>/dev/null; then PASS=$((PASS+1)); log "[PASS] $1 => $3 (> $2)";
       else FAIL=$((FAIL+1)); log "[FAIL] $1 => expected > [$2] but was [$3]"; fi; }
sep(){ log "-------------------------------------------------------------"; }
dc(){ docker compose -f "$CF" "$@"; }

log "M7-T05 Compose 部署验证  $(date '+%F %T')"
log "compose 文件：$CF"
log "compose 版本：$(docker compose version --short 2>/dev/null)"
sep

# ---- 1. 构建与启动（本脚本不重复 build；若栈未起则先起）----
log "[1] 当前服务清单"
dc ps --format "table {{.Service}}\t{{.State}}\t{{.Status}}" 2>/dev/null | tee -a "$EVI"
RUNNING=$(dc ps --status running --format "{{.Service}}" 2>/dev/null | sort | tr '\n' ' ')
chk "1 服务数（mysql/backend-java/service-ai/frontend-web/nginx）" "5" "$(echo $RUNNING | wc -w | tr -d ' ')"
sep

# ---- 2. 五服务 healthy ----
log "[2] 健康状态"
HEALTHY=0
for s in mysql backend-java service-ai frontend-web nginx; do
  st=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
        "shoppilot-dev-$s-1" 2>/dev/null)
  if [ "$st" = "healthy" ]; then HEALTHY=$((HEALTHY+1)); log "    $s = healthy"; else log "    $s = $st"; fi
done
chk "2 healthy 服务数" "5" "$HEALTHY"
sep

# ---- 3. nginx → backend /api/v1/health ----
log "[3] 经 nginx 访问后端健康端点"
code=$(curl -s --noproxy '*' -o /tmp/t05-health.json -w '%{http_code}' "$BASE/api/v1/health")
chk "3 /api/v1/health HTTP" "200" "$code"
chk "3 status 字段" "UP" "$(sed -n 's/.*"status":"\([A-Z]*\)".*/\1/p' /tmp/t05-health.json | head -1)"
code=$(curl -s --noproxy '*' -o /tmp/t05-act.json -w '%{http_code}' "$BASE/actuator/health")
chk "3 /actuator/health HTTP（注：nginx 不代理 actuator，返回 SPA HTML）" "200" "$code"
# ★ R189：nginx 只代理 /api/，actuator 属内网端点（最小暴露）。此处断言「未被代理」= 响应体不含 UP
if grep -q '"status":"UP"' /tmp/t05-act.json 2>/dev/null; then
  FAIL=$((FAIL+1)); log "[FAIL] 3 nginx 未代理 actuator => 期望 SPA HTML，实际返回了 actuator JSON"
else
  PASS=$((PASS+1)); log "[PASS] 3 nginx 未代理 actuator（返回 SPA，符合最小暴露）"
fi
# actuator 只能在容器内访问
IA=$(docker exec shoppilot-dev-backend-java-1 curl -s --noproxy '*' http://127.0.0.1:8080/actuator/health 2>/dev/null)
chk "3 容器内 actuator status" '{"status":"UP"}' "$IA"
sep

# ---- 4. 容器内 backend → service-ai 内部健康 ----
log "[4] 内网可达：backend-java → service-ai:8000"
BODY=$(docker exec shoppilot-dev-backend-java-1 \
        curl -s --noproxy '*' -w '|%{http_code}' http://service-ai:8000/internal/v1/health 2>/dev/null)
chk "4 internal health HTTP" "200" "${BODY##*|}"
case "${BODY%|*}" in *'"status":"UP"'*) chk "4 internal status" "UP" "UP" ;; *) chk "4 internal status" "UP" "${BODY%|*}" ;; esac
sep

# ---- 5. 宿主直连 8000 必须失败（NFR-SEC-04）----
log "[5] service-ai 不对外暴露"
code=$(curl -s --noproxy '*' -o /dev/null -m 5 -w '%{http_code}' "http://127.0.0.1:8000/internal/v1/health" 2>/dev/null)
case "$code" in
  000|"") PASS=$((PASS+1)); log "[PASS] 5 宿主 8000 不可达 => curl exit（000）" ;;
  *) FAIL=$((FAIL+1)); log "[FAIL] 5 宿主 8000 不可达 => 期望连接失败，实际 HTTP $code" ;;
esac
MAP=$(docker port shoppilot-dev-service-ai-1 2>/dev/null | wc -l | tr -d ' ')
chk "5 service-ai 无宿主端口映射" "0" "$MAP"
sep

# ---- 6. Flyway 迁移 ----
log "[6] Flyway 迁移版本"
MIG=$(docker exec shoppilot-dev-mysql-1 mysql -ushoppilot -pchangeme_app_password shoppilot \
        --default-character-set=utf8mb4 -N -B -e "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1" 2>/dev/null)
chk "6 成功迁移数" "13" "$MIG"
LATEST=$(docker exec shoppilot-dev-mysql-1 mysql -ushoppilot -pchangeme_app_password shoppilot \
        --default-character-set=utf8mb4 -N -B -e "SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1" 2>/dev/null)
chk "6 最新版本" "1.0.12" "$LATEST"
sep

# ---- 7. down → up 数据保留（可选）----
if [ "${1:-}" = "--with-recreate" ]; then
  log "[7] down（保留卷）→ up → 数据保留"
  # 不只查 users：users 是种子表，重启后由 Flyway/种子重建也能「碰巧」一致。
  # 必须查【只可能来自真实写入】的业务表，才能证明卷真的没丢。
  SNAP_BEFORE=$(docker exec shoppilot-dev-mysql-1 mysql -ushoppilot -pchangeme_app_password shoppilot \
           --default-character-set=utf8mb4 -N -B -e \
           "SELECT CONCAT('users=',IFNULL((SELECT COUNT(*) FROM users),-1),',skus=',IFNULL((SELECT COUNT(*) FROM skus),-1),',txns=',IFNULL((SELECT COUNT(*) FROM inventory_transactions),-1),',audit=',IFNULL((SELECT COUNT(*) FROM audit_logs),-1),',flyway=',IFNULL((SELECT COUNT(*) FROM flyway_schema_history WHERE success=1),-1))" 2>/dev/null)
  log "    重启前快照：$SNAP_BEFORE"
  dc down >/dev/null 2>&1
  dc up -d >/dev/null 2>&1
  for i in $(seq 1 60); do
    st=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
          shoppilot-dev-backend-java-1 2>/dev/null)
    [ "$st" = "healthy" ] && break
    sleep 2
  done
  SNAP_AFTER=$(docker exec shoppilot-dev-mysql-1 mysql -ushoppilot -pchangeme_app_password shoppilot \
           --default-character-set=utf8mb4 -N -B -e \
           "SELECT CONCAT('users=',IFNULL((SELECT COUNT(*) FROM users),-1),',skus=',IFNULL((SELECT COUNT(*) FROM skus),-1),',txns=',IFNULL((SELECT COUNT(*) FROM inventory_transactions),-1),',audit=',IFNULL((SELECT COUNT(*) FROM audit_logs),-1),',flyway=',IFNULL((SELECT COUNT(*) FROM flyway_schema_history WHERE success=1),-1))" 2>/dev/null)
  log "    重启后快照：$SNAP_AFTER"
  chk "7 down/up 后数据快照不变（users/skus/txns/audit/flyway）" "$SNAP_BEFORE" "$SNAP_AFTER"
  chk "7 重启后 backend healthy" "healthy" "$st"
else
  log "[7] down/up 数据保留：未执行（需 --with-recreate；避免中断并行任务）"
fi
sep
# ---- 8. NFR-OBS-02：指标端点（M8-T02 / R195）----
log "[8] 指标埋点：容器内 /actuator/prometheus 可见四类指标"
PROM=$(docker exec shoppilot-dev-backend-java-1 curl -s --noproxy '*' \
      http://127.0.0.1:8080/actuator/prometheus 2>/dev/null)
# ★ Prometheus 文本格式把指标名里的 '.' 转成 '_'：shoppilot.import.batch.total → shoppilot_import_batch_total
chk "8 导入批次终态指标存在" "ok" "$(echo "$PROM" | grep -q '^shoppilot_import_batch_total{' && echo ok || echo missing)"
chk "8 导入确认耗时指标存在" "ok" "$(echo "$PROM" | grep -q '^shoppilot_import_confirm_seconds_count' && echo ok || echo missing)"
chk "8 分析运行终态指标存在" "ok" "$(echo "$PROM" | grep -q '^shoppilot_analysis_run_total{' && echo ok || echo missing)"
# 标签是「成功率/降级率」的分子分母来源：缺了标签，PromQL 只能算总数，指标失去意义
chk "8 导入指标带 status 标签" "ok" "$(echo "$PROM" | grep -q 'shoppilot_import_batch_total{.*status=' && echo ok || echo missing)"
# ★ 两个标签【分别】断言，不断言它们的先后顺序：Prometheus 文本格式按标签名字典序输出
#   （llm_status 在 status 之前），写死顺序会得到与实现无关的假红（本批踩过）。
chk "8 分析指标带 status 标签" "ok" "$(echo "$PROM" | grep -q 'shoppilot_analysis_run_total{.*status=' && echo ok || echo missing)"
chk "8 分析指标带 llm_status 标签" "ok" "$(echo "$PROM" | grep -q 'shoppilot_analysis_run_total{.*llm_status=' && echo ok || echo missing)"
# 敏感端点仍不暴露（R189 的最小暴露不许被 prometheus 顺带撕开）
for e in env beans configprops heapdump threaddump loggers; do
  c=$(docker exec shoppilot-dev-backend-java-1 curl -s --noproxy '*' -o /dev/null \
      -w '%{http_code}' "http://127.0.0.1:8080/actuator/$e" 2>/dev/null)
  chk "8 敏感端点 $e 仍 404" "404" "$c"
done
# 宿主侧仍不可达（nginx 不代理 /actuator，返回的是 SPA 而非指标）
code=$(curl -s --noproxy '*' -o /tmp/t05-prom.html -m 5 -w '%{http_code}' \
       "$BASE/actuator/prometheus" 2>/dev/null)
chk "8 宿主取 prometheus 拿到的是 SPA" "ok" "$( grep -q '<!DOCTYPE html' /tmp/t05-prom.html 2>/dev/null && echo ok || echo "泄漏或异常：$code" )"
sep

log "TOTAL: PASS=$PASS FAIL=$FAIL"
[ "$FAIL" = "0" ] && log "RESULT: ALL PASS" || log "RESULT: HAS FAILURES"
[ "$FAIL" = "0" ] || exit 1
