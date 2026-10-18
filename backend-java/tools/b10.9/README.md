# B10.9 取证归档（R86：主数据导入不得改写库内编码字面量）

## 一、本批修的是什么

| 编号 | 现象（修复前） | 根因 | 修法 |
|---|---|---|---|
| **R86** | 库里 `SKU-A`，导入单行 `sku-a` → `validate error_rows=0`、`confirm COMPLETED`，但 `GET /api/v1/skus/9002` 返回 `sku-a`；`audit_logs.biz_no` 也是 `sku-a`（**无法还原**改写前的编码）；再导入 `SKU-A` 又改回大写（**最后写入者赢**）。行数不变、id 不变、无 `CAT-3002`、无重复行 ⇒ **静默篡改** | `CatalogWriteService#doUpsertXxxByCode` 的命中分支调 `updateSku/updateWarehouse/...`，而这些 update 方法内的 `setString(w, body, "<code>", "<code>")` **无条件覆盖** code 列。判定本身是对的（`selectIdByCode` 走 `utf8mb4_0900_ai_ci` 能 ci 命中），只是命中后**多做了一步改写** | 命中分支传 `writableCode=false`，**不** set code 列；create 分支行为一字不变 |
| **R86-b**（连带，任务书第 3 点 a 要求自查） | 同上四类（`WAREHOUSE` / `SUPPLIER` / `PRODUCT`）全部同构 | 同上 | 四类**同步**修，只改一类不算完成 |
| **R86-c**（连带） | 批量出口（Pass 1）的命中判定与单行出口**不等价**：`preloadXxxIdsByCode` 返回的 Map 键是**库里写法**，而查找用**文件写法** ⇒ 文件写 `sku-c` 时预取表必然落空 ⇒ 被判「不存在」⇒ 走 create 撞 `uk_sku_team_code` ⇒ Pass 1 整批回滚、退化成 Pass 2 逐行重放 | 预取表的键与查找键折叠口径不一致（`in(...)` 受 `ai_ci` 约束能匹配，但 Java 侧 Map key 对不上） | 预取表 `put` 与查找 `get` **都**经 `foldCode(...)` 折叠 ⇒ 两条出口判定严格等价 |

### 为什么不是「加一个 CAT-3002 拦小写」

口径是「**命中并保留库内写法**」，不是「小写视为非法」。若改成报错，运营每次用不同大小写导出文件都会被拦下，
而库内本来就只有一个物理行（`ai_ci` 唯一键保证），报错等于凭空制造摩擦。任务书亦明令禁止此方向。

### 为什么不是「在导入路径上打标记跳过」

任务书点名禁止 `ThreadLocal` / 请求头开关一类绕过。本批采用的是**调用点显式传参**：

```java
private SkuView updateSku(Long id, JsonNode body, boolean writableCode) { ... }

upsertSku(id, pathProductId, body)          → updateSku(id, body, true)    // REST
doUpsertSkuByCode(body, preloadedIds)       → updateSku(existingId, body, false)  // 导入命中
```

理由：这是「**同一内核、两种业务语义**」的必要表达点（`code` 在 REST 是普通字段、在导入是定位键）。
参数让这处差异在调用处**一眼可见、可静态检视、可被测试覆盖**；`ThreadLocal` 则把它藏进隐式上下文，
后人加第五个调用方时必然漏判。

## 二、第 3 点的 REST 裁定（本批最需要说清的一条）

任务书要求：先 grep `API_CONTRACT` 确认 REST 是否允许改 `code`，据此二选一。

**裁定：REST 允许改 `code`** ⇒ 走「把 `set code` 从共用 update 方法移出、改由调用方显式传递」，
**不做**口径收紧。依据（三条互相印证）：

1. **`API_CONTRACT §10.3`**：`PATCH /api/v1/warehouses|suppliers|products|skus/{id}` 一律写「修改 XX」，
   **未**把 `code` 列入禁改清单；`§1.2` 只规定 `PATCH` 是「局部更新指定字段」，无字段白名单。
2. **`DATABASE_DESIGN §5.4–§5.7`**：四张主数据表的「**更新规则**」逐条读过——
   `warehouses`「`team_id` 不可变」、`skus`「`product_id` 不可变」、`products`/`suppliers` 只写停用规则，
   **均未**标 `code` 不可变。对照同文件 `§5.2:199`（`teams.code`「**不可变**（作为对外标识）」）与
   `§5.7:348`（`skus.product_id`「**不可变**」）——**作者要标就会标，没标即未禁止**。
3. **`DATA_DICTIONARY:23`**：把「不可变」定义为「该字段写入后不允许 UPDATE（**应用层禁止 + 无更新接口**）」。
   `code` 有 `PATCH` 接口，且原实现明确允许改（`updateSku` 里的 `setString(w, body, "sku_code", …)`），
   现状就是「允许」。

⇒ 若选另一条（直接删掉 `set code`），等于**在没有 SSOT 依据的情况下收紧 REST 能力**，
且会静默破坏既有管理端功能。故本批**保留** REST 改码能力，并由 `R86-h` 用例把它钉住。

## 三、环境与产物

| 项 | 值 |
|---|---|
| 端口 | MySQL `3360` / 应用 `18240`（任务书指定；`netstat -ano` 实测二者均空闲） |
| 容器 | `b109-mysql`（`mysql:8.0.36`，`--default-time-zone=+00:00`） |
| 应用 | 自建 fat jar（`./mvnw -B -DskipTests package`），`JAR_SHA256` 见 `evidence-b109.txt` 首几行 |
| 种子 | `backend-java/tools/evi/seed-b95.sql`（**未改动**）+ 脚本内补一行供应商 `9003=SUP-X`（seed 不含供应商行，而任务书【4】c 要求「库里 SUP-X」） |
| `IMPORT_STORAGE_DIR` | 显式设为 `tools/b10.9/.import-storage`，`trap cleanup EXIT` 时删除 |

**重跑方式**：

```bash
cd backend-java && ./mvnw -B -DskipTests package
bash backend-java/tools/b10.9/run-b109-evidence.sh
# 可覆盖： B109_MYSQL_PORT=xxxx B109_APP_PORT=xxxxx
```

产物：`evidence-b109.txt`（断言日志，入库）、`*.log`（应用 stdout / 响应头，已 gitignore）。

## 四、断言分组

| 组 | 门槛对应 | 内容 |
|---|---|---|
| **G1** | a | SKU：库里 `SKU-A` → 导入 `sku-a` → `COMPLETED`、**①库内 code ②GET 对外返回** 均 `SKU-A`、`chkne` 排除 `sku-a`、行数不变、`name` 已更新 |
| **G2** | b | WAREHOUSE：`WH-MAIN` → `wh-main` → 同上（①②） |
| **G3** | c | SUPPLIER：`SUP-X` → `sup-x` → 同上（①②） |
| **G4** | d | PRODUCT：`P-BAG-001` → `p-bag-001` → 同上（①②）；验证 §7.1 的 `product_code` 一并修 |
| **G5** | e | 回归：库里**无**该编码时导入小写新编码 → 真的新建、**写入小写原样**、`chkne` 排除被折叠成大写、不报 `CAT-3002`（四类各一轮） |
| **G6** | f | 回归：完全同大小写既有编码仍走命中更新，`barcode` 确实被更新、行数不变 |
| **G7** | g | 审计：命中分支 `biz_no ==` 库内既有写法（`chkne` 排除文件里的写法），四类各验 |
| **G8** | h | REST 回归：`PATCH` 改 code 四类全测仍生效；`POST` 创建小写原样落库（create 分支不变）。**本组排在最后**，因其会改名并污染后续组夹具 |
| **G9** | 任务书未列 | 批量出口（Pass 1）：一批内「大写命中 + 小写命中」两行都命中更新、无行级错误、行数不变 |
| **G10** | 门槛 3 边界 1 | R85 四处判定键（R6 探针形态）：快照 / 销售三元组 / 主数据 `duplicateCode` 三处大小写重复仍判 `ERROR`，码分别为 `COM-0001` / `SAL-6004` / `CAT-3002` |
| **G11** | 门槛 3 边界 2 | R80 跨仓不误杀（同 SKU 两仓 + 仓库写作 `wh-b`）→ `COMPLETED`、两仓余额 `13.000`/`4.000`、幂等键后缀 `9004`/`9006` |
| **G12** | 门槛 3 边界 3 | R79 `PARTIAL_SUCCESS` 仍可达：改 `listed_date` 制造写服务内部失败 → 行级 `SAL-6003`（`chkne` 排除 `COM-0002`）、成功行已落库 |
| **G13** | 门槛 4 | 跑完 `seed-b95.sql` 后**直接**执行 `§7.3` 对账 SQL → **0 行**；`qty_after = qty_before + qty_delta` 违例 0 行；`success+failed=total` 违例 0 行；「有余额行但无任何流水」的行数 0 |

**实测结果**：`B109_CHECK_TOTAL=125` / `B109_FAIL_COUNT=0` / `B109_RESULT=ALL PASS`（门槛要求 ≥16 项、FAIL 0）。

末尾全景输出（可直接看出「哪些编码被保留、哪些是小写原样新建」）：

```
编码全景 team7：skus=9002=SKU-A-RENAMED,9003=SKU-B,9005=SKU-C,9103=sku-brand-new
            wh=9004=WH-RENAMED,9006=WH-B,9104=wh-branch
            sup=9003=SUP-RENAMED,9004=sup-new
            prod=9001=P-RENAMED,9102=p-tote-001,9103=p-post-001
审计biz_no：SKU9002=SKU-A WH9004=WH-MAIN SUP9003=SUP-X PROD9001=P-BAG-001
```

最后一行是 R86 最直接的证据：**四次导入命中，审计里留下的全是库内既有写法**。
（`SKU-A-RENAMED` / `WH-RENAMED` 等是 G8 的 REST 改名所致，属预期。）

## 五、探针有效性（负向验证）

把四类 by-code 命中分支的 `writableCode` 临时改回 `true`（等价于关闭 R86 修复），
重跑 `ImportMasterDataCodeLiteralTest`：**9 项中 6 项失败**，现象与缺陷报告一字不差：

| 失败用例 | 断言 | 实测（关闭修复后） |
|---|---|---|
| `skuImportDoesNotRewriteCodeLiteral` | 库内 `sku_code` = `SKU-A` | **`sku-a`** |
| `warehouseImportDoesNotRewriteCodeLiteral` | 库内 `code` = `WH-MAIN` | **`wh-main`** |
| `supplierImportDoesNotRewriteCodeLiteral` | 库内 `code` = `SUP-X` | **`sup-x`** |
| `productImportDoesNotRewriteCodeLiteral` | 库内 `product_code` = `P-BAG-001` | **`p-bag-001`** |
| `importUpdateAuditUsesInDbCodeLiteral` | 审计 `biz_no` = `SKU-A` | **`sku-a`** |
| `batchPathHitDetectionIsCaseInsensitiveToo` | 小写命中行 `sku_code` = `SKU-B` | **`sku-b`** |

`lowercaseNewCodeIsActuallyCreated`（e）、`exactCaseExistingCodeStillUpsert`（f）、
`restPatchCanStillChangeCode`（h）**仍通过** —— 这是正确的：
这三项分别验证「create 分支未受影响」「命中更新本身未受影响」「REST 能力未受影响」，
它们**不应**随 R86 修复一起失败。若它们也跟着失败，说明探针写错了（在测修复之外的东西）。

临时改动已全部还原（`CatalogWriteService.java` 恢复为 `writableCode=false`），还原后 9 项全绿。

## 六、实现要点（下次接续先看这段）

1. **折叠只用于「判定」，绝不用于「写入」**。`foldCode(...)` 只出现在
   `preloadXxxIdsByCode` 的 `put` 与 `doUpsertXxxByCode` 的 `get`；
   create 分支的 `entity.setCode(CatalogJson.text(body, "code"))` **原样写入**，
   所以库里能并存用户自己写的小写编码（`G5` 专门验证这一点）。
2. **审计 `bizNo` 必须取更新后的视图**，不能取 `body` 里的值。
   `W/H/S` 三类 `updateXxx` 内部会 `selectById` 重查 ⇒ `view.getCode()` 天然是库内既有字面量；
   `PRODUCT` 走 `withView=false`，返回的是 guard 已取到的**更新前**实体，
   其 `product_code` 同样等于库内既有字面量（因为我们没改它）——两条路径都成立，但**理由不同**，
   改 `withView` 时须重新确认。
3. **`preloadXxxIdsByCode` 的键必须折叠**（R86-c）。这是 B4-PERF 引入的
   「一次 `IN` 查询代替逐行 `selectIdByCode`」优化的隐藏前提：`IN` 受 `ai_ci` 约束能大小写匹配，
   但 Java Map 的 key 不会自动折叠。⚠️ 将来若新增第五类主数据，**必须**照抄 `foldCode` 两侧。
4. **不要用 `Locale.ROOT` 以外的折叠**。土耳其语环境下 `"i".toUpperCase()`（默认语言）会得到
   「带点大写 I」，与 `utf8mb4_0900_ai_ci` 的折叠结果不一致 ⇒ 同一编码在不同机器上判定不同。

## 七、复现坑（真栈脚本层面）

> 本批真栈共跑了 **5 轮**：第 1 轮 53 FAIL、第 2 轮 4 FAIL、第 3 轮 1 FAIL、第 4 轮 3 FAIL、第 5 轮 0 FAIL。
> **前 4 轮的每一个 FAIL 都已归因到脚本/断言，不是代码缺陷**（逐条见第八节）。
> 这个衰减曲线本身就是证据：若真是代码缺陷，修正夹具不会让 FAIL 归零。

- **`field_mapping_json` 只能映射【该 CSV 真实存在的列】**。映射到文件里没有的列 ⇒ mapping 阶段
  `422` ⇒ 批次停在 `PARSED` ⇒ 后续 `validate`/`confirm` **全部连锁失败**。
  首轮 53 个 FAIL 里有 26 个是这个原因。⇒ 每份 CSV 配一份对应的 `MAP_*`（本脚本有 4 份 SKU 映射）。
- **必须先剥掉外层信封再取业务字段**。`warehouse`/`supplier` 的业务字段就叫 `code`，
  而信封里也有 `"code":"OK"` ⇒ 直接对全文 `grep '"code":"..."'` 会抓到 `OK`。
  本脚本用 `dataField()`（先 `sed 's/.*"data"://` 再匹配）。
- **大小写断言必须用 `BINARY` 比较**。列排序规则是 `utf8mb4_0900_ai_ci`，
  `WHERE sku_code='SKU-BRAND-NEW'` 会**匹配到** `sku-brand-new` 那一行 ⇒ 普通比较根本区分不出大小写。
  要断言「库里存的是小写」，只能写 `BINARY sku_code='sku-brand-new'`。
- **审计查询要限定 `change_summary`**。REST `PATCH` 走 `@Audited` 切面**也**写
  `MASTER_DATA_STATUS_CHANGE`，且**不填 `biz_no`**（切面不读业务表）⇒
  `ORDER BY id DESC LIMIT 1` 在跑过 REST 组之后会取到那条 `biz_no=NULL` 的审计。
  本脚本的 `bizNo()` 额外带 `AND change_summary='导入更新<资源名>'`。
  ⚠️ 注意字面量：`导入更新 SKU`（**有**空格）vs `导入更新仓库`/`导入更新供应商`/`导入更新商品`（**无**空格）。
- **「恰好 N 条」类断言必须限定作用域**。本脚本 G1 与 G7 两次导入都命中了同一个 SKU ⇒
  审计是**两条**（一次导入一条，不是重复写审计的缺陷）。只按 `(action,biz_type,biz_id)` 计数会得到 2。
  反过来，「biz_no 必须是库内写法」的正确写法是**分别断言两条的 biz_no 都等于 `SKU-A`、且没有一条等于 `sku-a`**。
- **组序要考虑夹具污染**。REST 改名组（G8）会把 `SKU-A`/`WH-MAIN`/`P-BAG-001` 全改掉，
  后续任何引用这些编码的组都会连锁失败 ⇒ 把破坏性组**放到最后**。
- **`GET /api/v1/skus|warehouses|suppliers|products/{id}` 是按 id 寻址，没有 by-code GET**；
  响应信封是 `{code,message,data:{...}}`，编码字段分别是 `sku_code` / `code` / `code` / `product_code`。
- **审计的编码字段是 `audit_logs.biz_no`**（不是 `detail` / `change_summary`）。
- **`import_row_errors` 取错误行必须加 `AND error_level='ERROR'`**：同一批次里成功行可能带
  `WARNING`（如 `IMP-4006`），`ORDER BY row_no LIMIT 1` 会误取。
- **`run_chain` 里的 `VS` 必须在函数内取**：validate 会把批次推进到 `VALIDATED`，
  再调一次会被状态守卫拦成 `422 IMP-4005`（这是正确行为，不是缺陷）。
- **端口先 `netstat -ano` 再用**：`3350` 被宿主 `WorkBuddy.exe` 占用（任务书亦已禁）。
  本批用 `3360` / `18240`，跑前实测二者均空闲。
- **`pwd -W` 转 Windows 路径**：Windows 原生 `curl -F file=@...` 不认 `/d/...`。
- **`--noproxy '*'`**：本机全局 `http.proxy=127.0.0.1:7890`（Clash）会劫持 `localhost`。
- **`--default-character-set=utf8mb4`**：否则 `mysql` 客户端的中文字面量退化成 `?`，产生假 `FAIL`。
- **应用日志写独立文件**，不要与 `tee` 共用同一路径（会互相覆盖）。
- **显式设 `IMPORT_STORAGE_DIR`**：否则上传文件落到仓库根的 `./data/import/`。
- **`seed-b95.sql` 不含供应商行**：`G3`/`G5c` 需要 `SUP-X`，脚本内单独 `INSERT`（不改 seed 文件本身）。
- **Bash 工具里手工起的后台 java 进程会在任务结束时被回收**：
  想手工复现某个 HTTP 场景，别用 `nohup … &`，直接把探针写进主脚本里跑（进程生命周期由 `trap cleanup` 管）。

## 八、偏差登记

见交付汇报【6】。要点：

### 实现侧

1. 修了任务书未列、但与 R86 同源的**第五处缺陷**：批量出口（Pass 1）预取表的键未折叠（R86-c）。
   若不修，`G9` 场景下小写命中的行会被判「不存在」而撞 `uk_sku_team_code`，Pass 1 整批回滚
   ⇒ 批量导入退化成逐行重放。**这是任务书第 3 点 a「必须同步」要求下的自查发现。**
2. **REST 侧裁定为「允许改 code」**（依据见第二节），因此**没有**收紧口径，
   实现上把 `set code` 从共用的 update 内核移出、由调用方显式传 `writableCode`。
   ⚠️ 若项目负责人的原意是 REST 也不允许改，则本批实现需相应改为「直接删 `set code`」，
   并同步订正 `API_CONTRACT §10.3` —— **这是口径问题，我不自行改 docs**。
3. **测试侧**：`ImportMasterDataCodeLiteralTest` 共 9 项 ≥ 任务书要求的 8 项；
   四类各 ≥1 正向（a–d）+ 1 回归（e/f）。
4. 未改动任何既有 416 项用例（`git diff --stat` 只含 1 个 `src/main` 文件 + 2 个新增文件）。

### 取证侧（前 4 轮 FAIL 的逐条归因，全部是脚本/断言错误）

| 轮次 | FAIL 数 | 归因 |
|---|---|---|
| 第 1 轮 | 53 | ① `MAP_SKU` 映射到 CSV 里不存在的 `barcode`/`name` 列 ⇒ 批次停在 `PARSED`，**连锁 26 项**；② `jstr` 抓到信封的 `"code":"OK"` 而非业务 `code`（warehouse/supplier 各 1 项）；③ G8 的 REST 改名污染 G9–G12 的夹具（**约 20 项**） |
| 第 2 轮 | 4 | ① `chkne` 语义用反 + 未用 `BINARY` 比较（`ai_ci` 下大小写不可分）；② G10c 的两列 CSV 用了三列映射；③ G7 的「恰好 1 条」不变式本身写错（两次导入 ⇒ 两条审计） |
| 第 3 轮 | 1 | G7 审计条数不变式仍未按实际语义改正 |
| 第 4 轮 | 3 | `bizNo()` 的 `change_summary` 参数多传了前导空格（`导入更新 仓库` vs `导入更新仓库`） |
| 第 5 轮 | **0** | — |

集成测试首轮 2 次失败亦属我自己的夹具/断言错误（`skus` 插入的 `team_id`/`product_id` 参数传反导致 FK 失败；
审计查询 `SELECT` 未含 `biz_id` 却断言了它），非代码缺陷，已修正。

