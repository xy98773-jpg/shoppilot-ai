#!/usr/bin/env bash
# =============================================================================
# M7-T06 新环境部署可复现验证
# -----------------------------------------------------------------------------
# 判据（M7 清单）：在【新目录】按 README 文档执行一次 → 四/五服务健康 + 迁移成功。
# 做法：git clone 当前 HEAD 到临时目录 → 按文档 cp .env.example 并改三个宿主端口
#       （避开正在运行的 dev 栈）→ up -d --build → 健康与迁移断言 → down -v 清理。
# 为什么用 -p shoppilot-t06：compose 文件内写死 name: shoppilot-dev，-p 覆盖项目名，
#   容器与卷都带前缀，绝不与 dev 栈的数据卷/容器冲突（T06 是【全新库】，本就该从零迁移）。
# 用法：bash backend-java/tools/m7-t06/verify-t06.sh
# =============================================================================
set -u
SRC=/d/Desktop/ShopPilot-AI
OUT="$SRC/backend-java/tools/m7-t06"
EVI="$OUT/evidence-t06.txt"
CLONE="$OUT/clone"
PROJ=shoppilot-t06
NGINX_PORT=8091
BACKEND_PORT=8090
MYSQL_PORT=3399
mkdir -p "$OUT"; : > "$EVI"
PASS=0; FAIL=0
log(){ echo "$*" | tee -a "$EVI"; }
chk(){ if [ "$2" = "$3" ]; then PASS=$((PASS+1)); log "[PASS] $1 => $3";
       else FAIL=$((FAIL+1)); log "[FAIL] $1 => expected [$2] but was [$3]"; fi; }
# ★ 必须有：本脚本用到「表数 > 16」这类阈值断言。漏定义会让该行变成
#   `chk_gt: command not found` 后【静默跳过】——总数看着全绿，其实少测一条。
chk_gt(){ if [ "${3%.*}" -gt "$2" ] 2>/dev/null; then PASS=$((PASS+1)); log "[PASS] $1 => $3 (> $2)";
       else FAIL=$((FAIL+1)); log "[FAIL] $1 => expected > [$2] but was [$3]"; fi; }
sep(){ log "-------------------------------------------------------------"; }
cleanup(){
  if [ -d "$CLONE" ]; then
    docker compose -p "$PROJ" -f "$CLONE/deploy/docker-compose.dev.yml" down -v >/dev/null 2>&1
    docker compose -p "$PROJ" -f "$CLONE/deploy/docker-compose.dev.yml" --env-file "$CLONE/deploy/.env" \
      down -v >/dev/null 2>&1
    rm -rf "$CLONE"
    log "    已清理：compose down -v + 删除 clone 目录"
  fi
}
trap cleanup EXIT

log "M7-T06 新环境部署可复现验证  $(date '+%F %T')"
log "HEAD=$(git -C "$SRC" rev-parse --short HEAD)"
sep

# ---- 1. 端口可用性（避免与 dev 栈/其它服务冲突）----
log "[1] 端口可用性：$NGINX_PORT / $BACKEND_PORT / $MYSQL_PORT"
for p in $NGINX_PORT $BACKEND_PORT $MYSQL_PORT; do
  if curl -s --noproxy '*' -o /dev/null -m 2 "http://127.0.0.1:$p/" 2>/dev/null; then
    FAIL=$((FAIL+1)); log "[FAIL] 1 端口 $p 已被占用"
  else
    PASS=$((PASS+1)); log "[PASS] 1 端口 $p 空闲 => $p"
  fi
done
sep

# ---- 2. clone 当前 HEAD（本地 clone，不走网络）----
log "[2] clone HEAD 到 $CLONE"
rm -rf "$CLONE"
git clone -q "$SRC" "$CLONE" 2>>"$EVI"
CLONED_HEAD=$(git -C "$CLONE" rev-parse --short HEAD)
chk "2 clone 后 HEAD 一致" "$(git -C "$SRC" rev-parse --short HEAD)" "$CLONED_HEAD"
log "    clone 工作树干净度：$(git -C "$CLONE" status --porcelain | wc -l | tr -d ' ') 行差异（应为 0）"
sep

# ---- 3. 按 README 准备 .env（示例文件 → 覆盖端口）----
log "[3] 按文档准备 deploy/.env（仅覆盖三个宿主端口，其余用 compose 内联默认值）"
cp "$CLONE/deploy/.env.example" "$CLONE/deploy/.env"
cat >> "$CLONE/deploy/.env" <<EOF

# --- M7-T06 覆盖：避开正在运行的 dev 栈（宿主 8081/8080/3306）---
NGINX_PORT=$NGINX_PORT
BACKEND_PORT=$BACKEND_PORT
MYSQL_PORT=$MYSQL_PORT
EOF
log "    $(grep -c '^[A-Z_]*=' "$CLONE/deploy/.env") 个变量已配置"
sep

# ---- 4. up -d --build（全新库：Flyway 从 V1.0.0 跑到 V1.0.12）----
log "[4] docker compose up -d --build（全新目录 + 全新卷）"
BUILD_LOG="$OUT/build-t06.log"
if docker compose -p "$PROJ" -f "$CLONE/deploy/docker-compose.dev.yml" up -d --build >"$BUILD_LOG" 2>&1; then
  chk "4 compose up --build 退出码" "0" "0"
else
  chk "4 compose up --build 退出码" "0" "1"
  tail -20 "$BUILD_LOG" | sed 's/^/    /' | tee -a "$EVI"
fi
sep

# ---- 5. 五服务 healthy ----
log "[5] 健康状态（最多等 180s）"
HEALTHY=0
for i in $(seq 1 60); do
  HEALTHY=0
  for s in mysql backend-java service-ai frontend-web nginx; do
    st=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
          "$PROJ-$s-1" 2>/dev/null)
    [ "$st" = "healthy" ] && HEALTHY=$((HEALTHY+1))
  done
  [ "$HEALTHY" = "5" ] && break
  sleep 3
done
for s in mysql backend-java service-ai frontend-web nginx; do
  st=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
        "$PROJ-$s-1" 2>/dev/null)
  log "    $s = ${st:-<缺失>}"
done
chk "5 healthy 服务数" "5" "$HEALTHY"
sep

# ---- 6. 迁移成功（全新库必须 13 个、末号 1.0.12）----
log "[6] Flyway 迁移（全新库从零执行；V1.0.13 = M8-T04 幂等键占位表）"
# ★ 口令必须从 clone 的 deploy/.env 读：.env.example 的占位口令是 CHANGE_ME_app_password，
#   与 dev 栈 compose 内联默认 changeme_app_password 不同——用错口令时 mysql 静默失败，
#   会把「连不上」误显示成「迁移数为空」（本批踩过）。
MP=$(grep -m1 '^MYSQL_PASSWORD=' "$CLONE/deploy/.env" | cut -d= -f2-)
MIG=$(docker exec "$PROJ-mysql-1" mysql -ushoppilot -p"$MP" shoppilot \
      --default-character-set=utf8mb4 -N -B -e "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1" 2>/dev/null)
chk "6 成功迁移数" "14" "$MIG"
LATEST=$(docker exec "$PROJ-mysql-1" mysql -ushoppilot -p"$MP" shoppilot \
        --default-character-set=utf8mb4 -N -B -e "SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1" 2>/dev/null)
chk "6 最新版本" "1.0.13" "$LATEST"
TABLES=$(docker exec "$PROJ-mysql-1" mysql -ushoppilot -p"$MP" shoppilot \
        --default-character-set=utf8mb4 -N -B -e "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='shoppilot'" 2>/dev/null)
chk_gt "6 建出的业务表数" "24" "$TABLES"
sep

# ---- 7. 对外入口与内网隔离 ----
log "[7] 入口与隔离"
code=$(curl -s --noproxy '*' -o "$OUT/health-t06.json" -w '%{http_code}' "http://127.0.0.1:$NGINX_PORT/api/v1/health")
chk "7 经 nginx /api/v1/health" "200" "$code"
chk "7 status 字段" "UP" "$(sed -n 's/.*"status":"\([A-Z]*\)".*/\1/p' "$OUT/health-t06.json" | head -1)"
BODY=$(docker exec "$PROJ-backend-java-1" curl -s --noproxy '*' \
       -w '|%{http_code}' http://service-ai:8000/internal/v1/health 2>/dev/null)
chk "7 backend → service-ai 内网 health" "200" "${BODY##*|}"
code=$(curl -s --noproxy '*' -o /dev/null -m 5 -w '%{http_code}' "http://127.0.0.1:8000/internal/v1/health" 2>/dev/null)
case "$code" in
  000|"") PASS=$((PASS+1)); log "[PASS] 7 宿主 8000 不可达 => curl exit（000）" ;;
  *) FAIL=$((FAIL+1)); log "[FAIL] 7 宿主 8000 不可达 => HTTP $code" ;;
esac
MAP=$(docker port "$PROJ-service-ai-1" 2>/dev/null | wc -l | tr -d ' ')
chk "7 service-ai 无宿主端口映射" "0" "$MAP"
# 宿主 8091 是 SPA，不应泄露 actuator
code=$(curl -s --noproxy '*' -o "$OUT/act-t06.json" -w '%{http_code}' "http://127.0.0.1:$NGINX_PORT/actuator/health")
chk "7 nginx 未代理 actuator（返回 SPA）" "ok" "$( grep -q '"status":"UP"' "$OUT/act-t06.json" 2>/dev/null && echo "泄露" || echo ok )"
sep

# ---- 8. 全新库冒烟：API 可用 + 「无种子用户」这一事实被如实断言 ----
log "[8] 全新库冒烟（全新库【没有】种子用户：迁移只建结构，不建业务数据）"
code=$(curl -s --noproxy '*' -o "$OUT/login-t06.json" -w '%{http_code}' \
  -X POST "http://127.0.0.1:$NGINX_PORT/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"owner01","password":"1234"}')
# 全新库登录必须失败：能登成功才说明库被污染（复用了别人的数据卷）
chk "8 全新库登录被拒（无种子用户）" "401" "$code"
chk "8 登录错误码" "AUTH-1002" "$(sed -n 's/.*"code":"\([A-Z0-9-]*\)".*/\1/p' "$OUT/login-t06.json" | head -1)"
code=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' \
  "http://127.0.0.1:$NGINX_PORT/api/v1/inventory/balances?page=1&pageSize=1")
chk "8 未认证读列表 401" "401" "$code"
# 注册/建号属 README 的首跑步骤（见 README §7「首跑：建团队与用户」），不在本脚本范围
sep

log "[9] 清理（down -v + 删除 clone）—— 由 trap 执行"
sep
log "TOTAL: PASS=$PASS FAIL=$FAIL"
[ "$FAIL" = "0" ] && log "RESULT: ALL PASS" || log "RESULT: HAS FAILURES"
[ "$FAIL" = "0" ] || exit 1
