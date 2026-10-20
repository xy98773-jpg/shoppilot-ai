package com.shoppilot.analysis.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shoppilot.analysis.application.dto.AnalysisItemView;
import com.shoppilot.analysis.application.dto.CancelRunResponse;
import com.shoppilot.analysis.application.dto.CreateAnalysisRunRequest;
import com.shoppilot.analysis.application.dto.CreateRunOutcome;
import com.shoppilot.analysis.application.dto.AnalysisRunStatusResponse;
import com.shoppilot.analysis.application.dto.CreateRunResponse;
import com.shoppilot.analysis.application.dto.RunItemsOutcome;
import com.shoppilot.analysis.domain.AnalysisParams;
import com.shoppilot.analysis.domain.AnalysisRunStatus;
import com.shoppilot.analysis.domain.AnalysisScope;
import com.shoppilot.analysis.domain.AnalysisScopeType;
import com.shoppilot.analysis.domain.CanonicalJson;
import com.shoppilot.analysis.domain.LlmStatus;
import com.shoppilot.analysis.domain.RiskLevel;
import com.shoppilot.analysis.infrastructure.ai.AiServiceClient;
import com.shoppilot.analysis.infrastructure.ai.dto.AiParams;
import com.shoppilot.analysis.infrastructure.ai.dto.AiRunCreateRequest;
import com.shoppilot.analysis.infrastructure.ai.dto.AiRunItem;
import com.shoppilot.analysis.infrastructure.ai.dto.AiScope;
import com.shoppilot.analysis.infrastructure.ai.dto.AiValidateRequest;
import com.shoppilot.analysis.infrastructure.persistence.entity.AnalysisRunEntity;
import com.shoppilot.analysis.infrastructure.persistence.mapper.AnalysisRunMapper;
import com.shoppilot.analysis.infrastructure.persistence.entity.AnalysisItemEntity;
import com.shoppilot.analysis.infrastructure.persistence.mapper.AnalysisItemMapper;
import com.shoppilot.analysis.infrastructure.sampling.AnalysisItemAssembler;
import com.shoppilot.analysis.infrastructure.sampling.AnalysisItemAssembler.SkuRef;
import com.shoppilot.audit.application.AuditWriteService;
import com.shoppilot.audit.domain.model.AuditAction;
import com.shoppilot.audit.domain.model.AuditEvent;
import com.shoppilot.audit.domain.model.AuditResult;
import com.shoppilot.common.context.TeamContext;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.ApiErrorDetail;
import com.shoppilot.common.web.BizException;
import com.shoppilot.common.web.PageResult;
import com.shoppilot.common.web.RequestIdContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 分析运行的应用服务（M4-T03 {@code createRun} / M4-T05 状态查询 / M4-T13 快照查询 /
 * M4-T14 取消；B20.4 起 {@code createRun} 接入数据质量预检 {@code R131}）。
 *
 * <p><b>权威依据</b>：{@code API_CONTRACT.md §11.5}（端点与响应）、{@code §6}（幂等键语义）、
 * {@code ERROR_CODES.md §2.8}（{@code ANL-7001/7005/7007/7008/7009/7011/7013}）、
 * {@code DESIGN_BASIS.md §9.7}（状态机）、{@code §13.2}（Java 先落 {@code PENDING} 再把 id 交给 Python）、
 * {@code §13.4}（{@code timeout_at = 创建 + 300s}）、{@code D-25}（{@code run_no} 团队内日期前缀 + 序列）。
 *
 * <h2>★ 为什么<b>不</b>加 {@code @Transactional}</h2>
 * <p>本方法由「多条<b>彼此独立</b>的单语句写」组成：插入（自动提交）→ 调 Python（不可回滚的外部副作用）
 * → 更新状态 → 写审计。若把整段包进一个事务：① 插入在调 Python 前不提交，Python 拿到的 {@code
 * analysis_run_id} 在库里尚不存在，B20 的轮询会 404；② Python 失败后即便 catch 住并改成 {@code FAILED}，
 * 外层事务仍可能因异常被标记 rollback-only → {@code UnexpectedRollbackException}（{@code R79} 同一坑）；
 * ③ 审计走 {@code REQUIRES_NEW}，与业务事务混在一起只会增加死锁面。故按「单语句自动提交 + 显式状态补偿」
 * 推进，审计用 {@link AuditWriteService#record}（永不抛、旁路）。
 *
 * <h2>★ 幂等四态（{@code API_CONTRACT §6}）</h2>
 * <ol>
 *   <li>缺 key → 控制器 {@code COM-0010}（本类兜底再判一次，防绕过控制器直调）；</li>
 *   <li>同键同体 → 返回既有 run，{@code replayed=true}（控制器加 {@code Idempotent-Replayed: true}）；</li>
 *   <li>同键异体 → {@code COM-0009}（409）；比对范围：{@code scope_type / scope_json / warehouse_id /
 *       params_json / algorithm_version}（JSON 经 {@link CanonicalJson} 归一化后比较）；</li>
 *   <li>同键 + 首次进行中 → 同样返回既有 run（202），不新建、不报错。</li>
 * </ol>
 *
 * <h2>★ 并发保护 {@code ANL-7001}（409）</h2>
 * <p>同团队 + 同 {@code scope_type} + {@code warehouse_id} 相等（{@code null} 安全）+ 范围有交集
 * （{@link AnalysisScope#overlaps}：{@code ALL_ACTIVE} 任一进行中即冲突；{@code SELECTED_SKUS} 集合相交；
 * {@code CATEGORY} 同类目）且存在 {@code PENDING}/{@code RUNNING}。
 */
@Service
public class AnalysisAppService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisAppService.class);

    /** 审计业务类型（{@code DATABASE_DESIGN §6-J.1}：{@code ANALYSIS_RUN}）。 */
    private static final String AUDIT_BIZ_TYPE = "ANALYSIS_RUN";

    /** 运行编号前缀（{@code D-25}：日期前缀 + 团队内日内序列）。 */
    private static final String RUN_NO_PREFIX = "RUN-";

    /** 运行编号日期前缀（UTC 自然日；裁定③：analysis 不依赖 identity 取团队时区）。 */
    private static final DateTimeFormatter RUN_NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 单次分析整体超时（{@code DESIGN_BASIS §13.4}：默认 300s，由 B20 的超时扫描消费）。 */
    private static final long TIMEOUT_SECONDS = 300L;

    /** {@code run_no} 撞唯一键时的最大尝试次数（任务书 §三.2：上限 3 次）。 */
    private static final int MAX_RUN_NO_ATTEMPTS = 3;

    /** 幂等键最大长度（{@code API_CONTRACT §6}：1..128）。 */
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    /** 失败原因列宽（{@code analysis_runs.error_message VARCHAR(500)}）。 */
    private static final int ERROR_MESSAGE_MAX_LENGTH = 500;

    /** 快照查询每页上限（{@code API_CONTRACT §2.2} / {@code §11.6}：默认 20，上限 200）。 */
    private static final int MAX_PAGE_SIZE = 200;

    /** 快照查询排序白名单（{@code §11.6} B4：仅三字段 + asc/desc；白名单即防注入边界）。 */
    private static final Set<String> ITEM_SORT_COLUMNS =
            Set.of("avg_daily_sales", "risk_level", "created_at");

    private final AnalysisRunMapper runMapper;
    private final AiServiceClient aiServiceClient;
    private final AuditWriteService auditWriteService;
    /** 只读装配器（M4-B20.1：解除 {@code R120} / {@code R123}；application 不直连 Mapper）。 */
    private final AnalysisItemAssembler itemAssembler;
    /** 状态同步（M4-B20.2：轮询 / 超时兜底；{@code GET /runs/{id}} 的读时同步也走它）。 */
    private final AnalysisRunSyncService syncService;
    /** 结果明细快照（M4-B20.3：{@code GET /runs/{id}/items} 分页查询；本模块自有 Mapper）。 */
    private final AnalysisItemMapper itemMapper;

    public AnalysisAppService(AnalysisRunMapper runMapper,
                              AiServiceClient aiServiceClient,
                              AuditWriteService auditWriteService,
                              AnalysisItemAssembler itemAssembler,
                              AnalysisRunSyncService syncService,
                              AnalysisItemMapper itemMapper) {
        this.runMapper = runMapper;
        this.aiServiceClient = aiServiceClient;
        this.auditWriteService = auditWriteService;
        this.itemAssembler = itemAssembler;
        this.syncService = syncService;
        this.itemMapper = itemMapper;
    }

    /**
     * 创建分析运行（{@code POST /api/v1/analysis/runs}）。
     *
     * @param request        请求体（{@code team_id} 不在其中——只从 {@link TeamContext} 取）
     * @param idempotencyKey {@code Idempotency-Key} 请求头（非空，长度 1..128）
     * @return 响应数据 + 是否幂等重放
     * @throws BizException {@code COM-0010}（缺/非法幂等键）、{@code COM-0001}（结构/字段非法）、
     *                      {@code COM-0009}（同键异体）、{@code ANL-7001}（并发冲突）、
     *                      {@code ANL-7007}（SKU 超限）、{@code ANL-7009}（参数越界）、
     *                      {@code ANL-7013}（日期区间非法）、以及 client 透出的 {@code ANL-7002/7003/7004}
     */
    public CreateRunOutcome createRun(CreateAnalysisRunRequest request, String idempotencyKey) {
        // a. 团队/用户上下文（team_id 绝不来自请求体）
        TeamContext.Snapshot snapshot = TeamContext.require();
        String key = requireIdempotencyKey(idempotencyKey);

        // b. 参数校验（顺序固定：scope → params → 日期区间 → LLM 配置）
        AnalysisScope scope = resolveScope(request);
        AnalysisParams params = AnalysisParams.resolve(request.params());
        validateDateRange(request);
        validatePromptVersion(request, params);

        // b2. 默认仓解析（§5.36 Q3/Q4）：请求未指定仓时解析团队默认仓，**解析结果贯穿三处**
        //     （落库 / 幂等比对 / 并发保护）。★ 若比对仍用请求原值，首次回填后再用同一
        //     Idempotency-Key 重投（请求依旧不带仓）会被判成「异体」→ 误报 COM-0009。
        Long resolvedWarehouseId = itemAssembler.resolveWarehouseId(snapshot.teamId(), request.warehouseId());

        // c. 幂等：命中槽位 → 比对；一致直接返回既有 run
        AnalysisRunEntity existing = findByIdempotencyKey(key);
        if (existing != null) {
            return reconcile(existing, request, scope, params, resolvedWarehouseId);
        }

        // d. 并发保护 ANL-7001（同团队 + 同 scope_type + 仓库相等 + 范围相交 + 进行中）
        assertNoConflictingRun(scope, resolvedWarehouseId);

        // e0. 装配 items（§5.36 Q5）：**必须在 insertPending 之前**——
        //     跨团队/非 ACTIVE SKU、无默认仓、SKU 超限都是纯客户端校验错误，
        //     它们不应留下一个 FAILED 的 run 与一条 FINISH 审计。
        List<AiRunItem> items = itemAssembler.assemble(snapshot.teamId(), resolvedWarehouseId,
                scope, request.dateFrom(), request.dateTo());

        // e. 落库（PENDING 快照；run_no 撞键重取序列，上限 3 次）
        //    ★ R108/A2：撞键后若回查到同键 run（并发窗口），必须回到 c 的幂等判定——
        //    同体返回既有（replayed=true），异体 COM-0009；禁止直接返回 concurrent 跳过比对。
        AnalysisRunEntity saved;
        try {
            saved = insertPending(snapshot, request, scope, params, key, resolvedWarehouseId, items.size());
        } catch (IdempotentRace race) {
            return reconcile(race.existing(), request, scope, params, resolvedWarehouseId);
        }

        // f0. 数据质量预检（R131，M4-B20.4 §B1）：**必须在落库之后**——
        //     analyzable=false 要求把这个 run 置 FAILED（AI_SERVICE_CONTRACT §3.1 字段表），
        //     没有 run 就没有可落状态的对象。
        //     ★ §B2：复用 e0 已装配的 items，**禁止**二次装配（不得再 resolveWarehouseId + assemble，
        //    那会重复查库且可能与 e0 结果不一致）。
        try {
            aiServiceClient.validate(AiValidateRequest.of(snapshot.teamId(), resolvedWarehouseId,
                    scope, request.dateFrom(), request.dateTo(), params, items));
        } catch (BizException ex) {
            // §B3：ANL-7008（analyzable=false）与技术失败（ANL-7002/7003/7004…）同口径——
            // 都置 FAILED + FINISH(FAILURE) + 上抛，❌ 不留 PENDING 悬挂（B20 的超时扫描依赖状态可信）。
            boolean quality = ErrorCodes.ANL_7008.equals(ex.getErrorCode());
            markFailed(saved, ex.getErrorCode(), ex.getMessage());
            auditFinish(snapshot, saved, AuditResult.FAILURE,
                    (quality ? "数据质量预检未通过：" : "数据质量预检失败：") + ex.getErrorCode(),
                    ex.getErrorCode());
            throw ex;
        } catch (RuntimeException ex) {
            markFailed(saved, ErrorCodes.COM_0002, "系统繁忙，请稍后重试");
            auditFinish(snapshot, saved, AuditResult.FAILURE, "数据质量预检异常", ErrorCodes.COM_0002);
            throw ex;
        }

        // f/g. 发起；失败置 FAILED（不留 PENDING 悬挂）后原样上抛
        try {
            aiServiceClient.createAnalysisRun(toAiRequest(saved, scope, params, request, items));
        } catch (BizException ex) {
            markFailed(saved, ex.getErrorCode(), ex.getMessage());
            auditFinish(snapshot, saved, AuditResult.FAILURE,
                    "分析任务发起失败：" + ex.getErrorCode(), ex.getErrorCode());
            throw ex;
        } catch (RuntimeException ex) {
            markFailed(saved, ErrorCodes.COM_0002, "系统繁忙，请稍后重试");
            auditFinish(snapshot, saved, AuditResult.FAILURE, "分析任务发起异常", ErrorCodes.COM_0002);
            throw ex;
        }

        markRunning(saved, params.enableLlm());
        auditCreate(snapshot, saved, scope);

        log.info("分析任务已创建：runId={} runNo={} teamId={} scopeType={} totalSkus={}",
                saved.getId(), saved.getRunNo(), snapshot.teamId(), saved.getScopeType(), saved.getTotalSkus());
        return new CreateRunOutcome(CreateRunResponse.from(saved), false);
    }

    /**
     * 取消分析运行（{@code POST /api/v1/analysis/runs/{id}/cancel}；M4-T14，B20.4）。
     *
     * <p><b>权威依据</b>：{@code API_CONTRACT.md §10.7}（端点与权限：{@code OPERATOR+}）、
     * {@code §2}（409/422 边界）、{@code DESIGN_BASIS.md §9.7}（合法转换）、
     * {@code ERROR_CODES.md §2.8}（{@code ANL-7005} / {@code ANL-7011}）、
     * {@code AI_SERVICE_CONTRACT.md §3.6}（Java 立即置终态、远端取消尽力而为）。
     *
     * <h2>★ 为什么是「一条 CAS UPDATE」而不是「先读后写」</h2>
     * <p>读与写之间存在并发窗口：轮询/超时扫描可能在这期间把 run 推进到终态
     * （{@code SUCCEEDED}/{@code DEGRADED}/{@code FAILED}/{@code TIMEOUT}）。用
     * {@code WHERE id=? AND status IN ('PENDING','RUNNING')} 把「状态合法性」压进数据库的一次原子判定，
     * 影响 0 行即说明状态已变——此时再读回按「幂等 / 不可取消」分流，<b>绝不用读到的旧状态做写</b>。
     *
     * <h2>★ 三种结果</h2>
     * <ol>
     *   <li>影响 1 行 → 取消成功：200 + {@code {run_id,status,cancelled_at}}；写<b>恰 1 条</b>
     *       {@code ANALYSIS_RUN_CANCEL} 审计；再<b>尽力而为</b>通知 Python（失败只 WARN）；</li>
     *   <li>影响 0 行且已是 {@code CANCELLED} → <b>200 幂等重放</b>（{@code API_CONTRACT §10.7} 幂等列 ✅）：
     *       不改状态、<b>不重复审计</b>、<b>不再调 Python</b>；</li>
     *   <li>影响 0 行且是其它终态 → {@code ANL-7011}（422，「无法取消（非 PENDING/RUNNING）」）。</li>
     * </ol>
     * <p>★ 口径（任务书 §三.1）：<b>不用 {@code ANL-7010}</b>。{@code API_CONTRACT §2} 的 409/422 边界原文
     * 把「对 {@code SUCCEEDED} 的 run 再取消」归为 <b>422</b>；{@code ANL-7010} 是「终态再操作」的通用码，
     * 本端点按更具体的 {@code ANL-7011} 返回。
     *
     * <p>★ 不删任何数据：{@code analysis_items} 快照保留；取消后 {@code GET /items} 仍按
     * {@code progress<100} ⇒ {@code 409 ANL-7012}（{@link #listRunItems} 不特判取消态）。
     *
     * @param runId 运行 ID
     * @return 取消后的响应数据（幂等重放时是首次取消的真实 {@code cancelled_at}）
     * @throws BizException {@code ANL-7005}（不存在或跨团队——租户线使两者同表现）、
     *                      {@code ANL-7011}（终态且非 CANCELLED）
     */
    public CancelRunResponse cancelRun(Long runId) {
        TeamContext.Snapshot snapshot = TeamContext.require();

        // a. 存在性（租户线：跨团队同表现 → 404，不泄漏他团队是否存在）
        AnalysisRunEntity run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException(ErrorCodes.ANL_7005, "分析任务不存在");
        }

        // b. 唯一写入口：CAS（任务书 §A4 / 执行提醒 #1）
        int updated = runMapper.update(null, Wrappers.<AnalysisRunEntity>lambdaUpdate()
                .eq(AnalysisRunEntity::getId, runId)
                .in(AnalysisRunEntity::getStatus,
                        AnalysisRunStatus.PENDING.name(), AnalysisRunStatus.RUNNING.name())
                .set(AnalysisRunEntity::getStatus, AnalysisRunStatus.CANCELLED.name())
                .set(AnalysisRunEntity::getCurrentStep, "CANCELLED")
                // ★ 执行提醒 #2：finished_at 取数据库时钟 NOW(3)（UTC），不用 JVM 本地时间
                .setSql("finished_at = NOW(3)"));

        if (updated == 0) {
            // 状态在「读 → 写」之间被推进（或本来就是终态）：重读后分流
            AnalysisRunEntity latest = runMapper.selectById(runId);
            if (latest == null) {
                throw new BizException(ErrorCodes.ANL_7005, "分析任务不存在");
            }
            if (AnalysisRunStatus.CANCELLED.name().equals(latest.getStatus())) {
                // 幂等重放（§A4 / §三.2）：不审计、不调 Python、不改状态
                log.info("取消分析任务（幂等重放）：runId={} 已是 CANCELLED", runId);
                return CancelRunResponse.from(latest);
            }
            log.warn("取消分析任务被拒绝：runId={} status={}（仅 PENDING/RUNNING 可取消）",
                    runId, latest.getStatus());
            throw new BizException(ErrorCodes.ANL_7011, "该任务当前不可取消");
        }

        // c. 读回 DB 生成的 finished_at（响应 cancelled_at 的唯一真源）
        AnalysisRunEntity cancelled = runMapper.selectById(runId);
        if (cancelled == null) {
            // 理论不可达（本仓无删除 run 的路径）；防御式兜底，不返回半截响应
            throw new BizException(ErrorCodes.ANL_7005, "分析任务不存在");
        }

        // d. 审计恰 1 条（ANALYSIS_RUN_CANCEL；不再记 ANALYSIS_RUN_FINISH——语义不同，§A6/§八.7）
        auditCancel(snapshot, cancelled, run.getStatus());

        // e. 远端尽力而为（§A5）：本地已是权威终态，Python 取消失败只 WARN、不回滚、不抛
        try {
            if (!aiServiceClient.cancelBestEffort(runId)) {
                log.warn("远端取消未成功（不影响本地终态）：runId={}", runId);
            }
        } catch (RuntimeException ex) {
            log.warn("远端取消异常（不影响本地终态）：runId={} err={}", runId, ex.toString());
        }

        log.info("分析任务已取消：runId={} from={} to=CANCELLED", runId, run.getStatus());
        return CancelRunResponse.from(cancelled);
    }

    /**
     * 查询分析运行状态（{@code GET /api/v1/analysis/runs/{id}}；M4-T05 的<b>读时同步</b>通道）。
     *
     * <p>★ 读时同步：仅当 {@code status ∈ {PENDING, RUNNING}} 时先向 Python 拉一次状态再回响应
     * （{@code §5.38} 裁定 #2 的双通道：定时扫描 + 读时同步）。终态<b>不</b>触发——
     * Java 已是权威，没有理由为一个已完成的 run 再打扰对端。
     *
     * <p>★ 静默降级（{@code R134}）：Python 超时 / 5xx / 未配置时，只记 WARN 并返回<b>库内现状</b>，
     * <b>不抛异常</b>。理由：3s 的对端超时若变成用户看到的 500，轮询页就完全不可用；
     * 用户看到的进度可能滞后于真实进度，但前端轮询会自愈（R134 的前端约定）。
     *
     * @param runId 运行 ID
     * @return 状态响应（同步失败时是库内快照，可能滞后）
     * @throws BizException {@code ANL-7005}（不存在或跨团队——租户线使两者同表现，不泄漏他团队是否存在）
     */
    public AnalysisRunStatusResponse getRunStatus(Long runId) {
        AnalysisRunEntity run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException(ErrorCodes.ANL_7005, "分析任务不存在");
        }
        // ★ R143（B20.4.1 F1）：必须用 equals，**不能**用 ==。MyBatis 从 ResultSet 读出的是
        //   运行时新建的 String，与 Enum.name() 的实例不是同一引用 ⇒ == 恒为 false ⇒
        //   读时同步永不触发（实测：polling 关闭时创建 run，120s 仍 RUNNING|5|VALIDATING，
        //   抓包零 GET /runs/{id}）。左常量右变量：run.getStatus() 为 null 时不 NPE。
        if (AnalysisRunStatus.PENDING.name().equals(run.getStatus())
                || AnalysisRunStatus.RUNNING.name().equals(run.getStatus())) {
            // ★ 同步失败不外抛：syncFromPython 内部已 try/catch 并记 WARN
            syncService.syncFromPython(run);
            // 同步可能已推进状态（COMPLETED → 落库 → 终态），必须重新读回
            AnalysisRunEntity refreshed = runMapper.selectById(runId);
            if (refreshed != null) {
                run = refreshed;
            }
        }
        return AnalysisRunStatusResponse.from(run);
    }

    /**
     * 查询分析结果明细（{@code GET /api/v1/analysis/runs/{id}/items}；M4-T13）。
     *
     * <p>★ 权限：<b>任何已登录成员（含 VIEWER）</b>——与 {@link #getRunStatus} 同口径，只读明细
     * 不改任何业务数据，故<b>不加</b> {@code @RequireRole}（{@code API_CONTRACT §11.6} / {@code §9}）。
     *
     * <p>★ 就绪判定：只对 {@code progress == 100} 开放（{@code SUCCEEDED}/{@code DEGRADED}），
     * 否则返回 {@link RunItemsOutcome#notReady}（{@code ANL-7012}，{@code §11.6} 建议的最简口径，
     * 不按 {@code status} 枚举逐一判断）。
     *
     * <p>★ 排序白名单（防注入）：{@code avg_daily_sales / risk_level / created_at} + {@code asc/desc}，
     * 映射到实体列（Lambda 方法引用），<b>禁止</b>把用户输入拼进 {@code ORDER BY}。
     *
     * @param runId     运行 ID；不存在/跨团队 ⇒ {@code ANL-7005}（404，租户线使两者同表现）
     * @param riskLevel 可选过滤（{@code NONE/LOW/MEDIUM/HIGH/UNKNOWN}）；非法 ⇒ {@code COM-0001}
     * @param page      页码（默认 1）
     * @param pageSize  每页大小（默认 20，上限 200）
     * @param sort      排序（{@code avg_daily_sales,desc}）；非法 ⇒ {@code COM-0001}
     * @return 就绪则分页明细；未就绪则 {@code notReady}（由控制器转 {@code 409 ANL-7012}）
     */
    public RunItemsOutcome listRunItems(Long runId, String riskLevel, int page, int pageSize, String sort) {
        AnalysisRunEntity run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException(ErrorCodes.ANL_7005, "分析任务不存在");
        }
        if (run.getProgress() == null || run.getProgress() < 100) {
            return RunItemsOutcome.notReady(run);
        }
        validatePage(page, pageSize);
        RiskLevel level = resolveRiskLevel(riskLevel);
        SortSpec spec = parseSort(sort);

        LambdaQueryWrapper<AnalysisItemEntity> wrapper = Wrappers.<AnalysisItemEntity>lambdaQuery()
                .eq(AnalysisItemEntity::getRunId, runId);
        if (level != null) {
            wrapper.eq(AnalysisItemEntity::getRiskLevel, level.name());
        }
        applySort(wrapper, spec);

        Page<AnalysisItemEntity> result = itemMapper.selectPage(new Page<>(page, pageSize), wrapper);

        // sku_code 回填：走装配器只读门面（禁止 application 直连 catalog Mapper）
        List<Long> skuIds = result.getRecords().stream()
                .map(AnalysisItemEntity::getSkuId).distinct().toList();
        Map<Long, SkuRef> skuRefs = itemAssembler.loadSkuRefs(skuIds);

        List<AnalysisItemView> views = result.getRecords().stream()
                .map(e -> AnalysisItemView.from(e, skuCodeOf(skuRefs, e.getSkuId())))
                .toList();
        return RunItemsOutcome.ready(PageResult.of(result.getTotal(), page, pageSize, views));
    }

    /** 分页参数校验（{@code page ≥ 1}、{@code pageSize ∈ 1..200}）；非法 ⇒ {@code COM-0001}。 */
    private static void validatePage(int page, int pageSize) {
        if (page < 1) {
            throw badParam("page", "page 必须 ≥ 1", page);
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            // ★ R146：details[].field 用 snake_case（API_CONTRACT §2.3）；message 文案保留
            //   「pageSize」供人阅读。与 GlobalExceptionHandler 的 toSnakeCase(ex.getName())
            //   口径一致（pageSize=abc 走类型转换分支，也会得到 page_size）。
            throw badParam("page_size", "pageSize 必须在 1.." + MAX_PAGE_SIZE + " 之间", pageSize);
        }
    }

    /** {@code risk_level} 过滤值解析（可选；非法 ⇒ {@code COM-0001}）。 */
    private static RiskLevel resolveRiskLevel(String riskLevel) {
        if (riskLevel == null || riskLevel.isBlank()) {
            return null;
        }
        RiskLevel level = RiskLevel.fromCode(riskLevel.trim());
        if (level == null) {
            throw badParam("risk_level", "必须是 NONE/LOW/MEDIUM/HIGH/UNKNOWN 之一", riskLevel);
        }
        return level;
    }

    /**
     * {@code sort} 解析（白名单 + 方向；{@code null} = 无显式排序，由调用方套默认排序）。
     *
     * <p>★ 白名单即防注入边界：列名只从 {@link #ITEM_SORT_COLUMNS} 取，落到 {@code ORDER BY} 时
     * 再经 {@link #applySort} 映射到实体 Lambda 方法引用，<b>用户输入绝不进 SQL 文本</b>。
     */
    private static SortSpec parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return null;
        }
        String[] parts = sort.split(",", -1);
        if (parts.length == 1) {
            return new SortSpec(requireSortColumn(parts[0]), true);
        }
        if (parts.length == 2) {
            String column = requireSortColumn(parts[0]);
            String dir = parts[1].trim().toLowerCase();
            if ("asc".equals(dir)) {
                return new SortSpec(column, true);
            }
            if ("desc".equals(dir)) {
                return new SortSpec(column, false);
            }
        }
        throw badParam("sort", "排序参数必须是「字段,asc|desc」且字段在白名单内", sort);
    }

    private static String requireSortColumn(String raw) {
        String column = raw.trim();
        if (!ITEM_SORT_COLUMNS.contains(column)) {
            throw badParam("sort", "排序字段不在白名单内（avg_daily_sales/risk_level/created_at）", raw);
        }
        return column;
    }

    /** 把已校验的排序片段落到实体列（Lambda 方法引用，杜绝 SQL 注入）。 */
    private static void applySort(LambdaQueryWrapper<AnalysisItemEntity> wrapper, SortSpec spec) {
        if (spec == null) {
            // 默认排序：主键升序（稳定、可复现）
            wrapper.orderByAsc(AnalysisItemEntity::getId);
            return;
        }
        switch (spec.column) {
            case "avg_daily_sales" -> {
                if (spec.ascending) {
                    wrapper.orderByAsc(AnalysisItemEntity::getAvgDailySales);
                } else {
                    wrapper.orderByDesc(AnalysisItemEntity::getAvgDailySales);
                }
            }
            case "risk_level" -> {
                if (spec.ascending) {
                    wrapper.orderByAsc(AnalysisItemEntity::getRiskLevel);
                } else {
                    wrapper.orderByDesc(AnalysisItemEntity::getRiskLevel);
                }
            }
            case "created_at" -> {
                if (spec.ascending) {
                    wrapper.orderByAsc(AnalysisItemEntity::getCreatedAt);
                } else {
                    wrapper.orderByDesc(AnalysisItemEntity::getCreatedAt);
                }
            }
            default -> throw badParam("sort", "排序字段不在白名单内", spec.column);
        }
    }

    /** {@code sku_code} 回填（查不到主数据 ⇒ {@code null}，契约不要求此列恒非空）。 */
    private static String skuCodeOf(Map<Long, SkuRef> skuRefs, Long skuId) {
        SkuRef ref = skuRefs.get(skuId);
        return ref == null ? null : ref.skuCode();
    }

    private static BizException badParam(String field, String message, Object value) {
        return BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                List.of(new ApiErrorDetail(field, message, value)));
    }

    /** 已校验的排序片段（列名已过白名单）。 */
    private record SortSpec(String column, boolean ascending) {
    }

    // =====================================================================
    // b. 参数校验
    // =====================================================================

    /** 范围类型 + 明细（{@code COM-0001} / {@code ANL-7007}，全部由 {@link AnalysisScope} 负责）。 */
    private static AnalysisScope resolveScope(CreateAnalysisRunRequest request) {
        AnalysisScopeType type = AnalysisScopeType.fromCode(request.scopeType());
        if (type == null) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(ApiErrorDetail.of("scope_type",
                            "必须是 ALL_ACTIVE / SELECTED_SKUS / CATEGORY 之一")));
        }
        return AnalysisScope.resolve(type, request.scopeJson());
    }

    /** 日期区间：缺失 → {@code COM-0001}；{@code date_from > date_to} → {@code ANL-7013}。 */
    private static void validateDateRange(CreateAnalysisRunRequest request) {
        if (request.dateFrom() == null) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(ApiErrorDetail.of("date_from", "date_from 不能为空")));
        }
        if (request.dateTo() == null) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(ApiErrorDetail.of("date_to", "date_to 不能为空")));
        }
        if (request.dateFrom().isAfter(request.dateTo())) {
            throw new BizException(ErrorCodes.ANL_7013, "分析时间区间不合法");
        }
    }

    /** {@code enable_llm=true} 时 {@code prompt_version} 必填（否则 Python 无法选模板）。 */
    private static void validatePromptVersion(CreateAnalysisRunRequest request, AnalysisParams params) {
        if (Boolean.TRUE.equals(params.enableLlm())
                && (request.promptVersion() == null || request.promptVersion().isBlank())) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(ApiErrorDetail.of("prompt_version",
                            "enable_llm=true 时 prompt_version 不能为空")));
        }
    }

    private static String requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BizException(ErrorCodes.COM_0010, "缺少必要的请求信息");
        }
        String trimmed = idempotencyKey.trim();
        if (trimmed.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw BizException.withDetails(ErrorCodes.COM_0010, "缺少必要的请求信息",
                    List.of(ApiErrorDetail.of("Idempotency-Key", "长度必须在 1..128 之间")));
        }
        return trimmed;
    }

    // =====================================================================
    // c. 幂等
    // =====================================================================

    /** 按 {@code (team_id, idempotency_key)} 查既有 run（租户线自动追加 team_id）。 */
    private AnalysisRunEntity findByIdempotencyKey(String key) {
        List<AnalysisRunEntity> rows = runMapper.selectList(Wrappers.<AnalysisRunEntity>lambdaQuery()
                .eq(AnalysisRunEntity::getIdempotencyKey, key)
                .orderByAsc(AnalysisRunEntity::getId)
                .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 同键同体 → 返回既有 run（{@code replayed=true}）；异体 → {@code COM-0009}。
     *
     * <p><b>★ R108（B15.1）：比对范围必须覆盖 {@code API_CONTRACT §6.3} 的全部请求字段</b>，
     * 顺序：{@code scope_type → scope_json → warehouse_id → date_from → date_to →
     * params_json → algorithm_version → prompt_version → model_name}。
     * B15 只比了其中 5 项，导致「同键仅改日期」返回 202 静默重放旧 run（客户端完全无法察觉）。
     *
     * <p>口径：
     * <ul>
     *   <li>日期用 {@link Objects#equals}（{@code LocalDate} 值相等）；</li>
     *   <li>{@code prompt_version} / {@code model_name} 两侧先「空白 → null」再比（与
     *       {@code insertPending} 的落库口径一致，避免 {@code ""} 与 {@code null} 造成的假异体）；</li>
     *   <li>JSON 两列仍走 {@link CanonicalJson#jsonEquals}（键序/数值归一），不弱化。</li>
     * </ul>
     */
    private static CreateRunOutcome reconcile(AnalysisRunEntity existing,
                                              CreateAnalysisRunRequest request,
                                              AnalysisScope scope,
                                              AnalysisParams params,
                                              Long resolvedWarehouseId) {
        boolean sameBody = Objects.equals(existing.getScopeType(), scope.scopeType().name())
                && CanonicalJson.jsonEquals(CanonicalJson.parseOrNull(existing.getScopeJson()),
                        CanonicalJson.parseOrNull(scope.canonicalJson()))
                // ★ Q3：比对用**解析后**的仓库，而非请求原值（请求可为 null = 默认仓）
                && Objects.equals(existing.getWarehouseId(), resolvedWarehouseId)
                // ★ date_from / date_to 的落库载体：data_start_date / data_end_date
                //   （DATABASE_DESIGN §5.14 定义即「输入销售数据最早/最晚日期」= 请求窗口；
                //   表无独立 date_from/date_to 列，且本批禁新增迁移）
                && Objects.equals(existing.getDataStartDate(), request.dateFrom())
                && Objects.equals(existing.getDataEndDate(), request.dateTo())
                && CanonicalJson.jsonEquals(CanonicalJson.parseOrNull(existing.getParamsJson()),
                        CanonicalJson.parseOrNull(params.canonicalJson()))
                && Objects.equals(existing.getAlgorithmVersion(), request.algorithmVersion())
                && Objects.equals(blankToNull(existing.getPromptVersion()),
                        blankToNull(request.promptVersion()))
                && Objects.equals(blankToNull(existing.getModelName()),
                        blankToNull(request.modelName()));
        if (!sameBody) {
            throw new BizException(ErrorCodes.COM_0009, "相同的幂等键已用于另一笔请求，请更换幂等键");
        }
        return new CreateRunOutcome(CreateRunResponse.from(existing), true);
    }

    /**
     * 撞键后回查到同键 run 的内部信号（{@code R108/A2}）。
     *
     * <p>用异常而非返回值：{@code insertPending} 正常只返回「刚插入的实体」，
     * 把「并发命中」塞进返回类型会让调用方多一个 {@code null} 分支、也容易再次漏掉比对。
     */
    private static final class IdempotentRace extends RuntimeException {

        private final transient AnalysisRunEntity existing;

        private IdempotentRace(AnalysisRunEntity existing) {
            super("idempotent-key race", null, false, false);
            this.existing = existing;
        }

        private AnalysisRunEntity existing() {
            return existing;
        }
    }

    // =====================================================================
    // d. 并发保护
    // =====================================================================

    /** 存在同范围进行中 run → {@code ANL-7001}（409）。 */
    private void assertNoConflictingRun(AnalysisScope scope, Long warehouseId) {
        List<AnalysisRunEntity> inFlight = runMapper.selectList(Wrappers.<AnalysisRunEntity>lambdaQuery()
                .in(AnalysisRunEntity::getStatus,
                        AnalysisRunStatus.PENDING.name(), AnalysisRunStatus.RUNNING.name())
                .eq(AnalysisRunEntity::getScopeType, scope.scopeType().name()));
        for (AnalysisRunEntity other : inFlight) {
            if (!Objects.equals(other.getWarehouseId(), warehouseId)) {
                continue;
            }
            AnalysisScope otherScope = storedScope(other);
            if (otherScope != null && scope.overlaps(otherScope)) {
                log.warn("分析任务并发冲突：existingRunId={} existingRunNo={} scopeType={}",
                        other.getId(), other.getRunNo(), other.getScopeType());
                throw new BizException(ErrorCodes.ANL_7001,
                        "该 SKU 范围已有正在运行的分析任务，请稍后或取消后重试");
            }
        }
    }

    /**
     * 解析进行中 run 的入库范围。
     *
     * <p>★ {@link AnalysisScope#parse} 对 {@code null} 返回 {@code null}；而 {@code ALL_ACTIVE} 的
     * {@code scope_json} 本来就是 {@code NULL}——若直接透传会把「全部 SKU 的进行中任务」误判为无范围、
     * 从而漏报 {@code ANL-7001}。故先按 {@code scope_type} 还原，再解析明细。
     */
    private static AnalysisScope storedScope(AnalysisRunEntity entity) {
        AnalysisScopeType type = AnalysisScopeType.fromCode(entity.getScopeType());
        if (type == null) {
            return null;
        }
        if (type == AnalysisScopeType.ALL_ACTIVE) {
            return new AnalysisScope(type, null, null);
        }
        AnalysisScope parsed = AnalysisScope.parse(CanonicalJson.parseOrNull(entity.getScopeJson()));
        return parsed != null && parsed.scopeType() == type ? parsed : null;
    }

    // =====================================================================
    // e. 落库
    // =====================================================================

    /** 插入 {@code PENDING} 快照；撞 {@code uk_run_team_no} 时重取序列（上限 3 次）。 */
    private AnalysisRunEntity insertPending(TeamContext.Snapshot snapshot,
                                            CreateAnalysisRunRequest request,
                                            AnalysisScope scope,
                                            AnalysisParams params,
                                            String idempotencyKey,
                                            Long resolvedWarehouseId,
                                            int assembledCount) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String dayPrefix = RUN_NO_PREFIX + RUN_NO_DATE.format(now) + "-";

        AnalysisRunEntity entity = new AnalysisRunEntity();
        entity.setStatus(AnalysisRunStatus.PENDING.name());
        entity.setScopeType(scope.scopeType().name());
        entity.setScopeJson(scope.canonicalJson());
        // ★ Q4：insert 直接写解析值（不做二次 UPDATE 回填）；列虽标注「不可变」，
        //   但这是**初始化**而非变更——请求没带仓时这里写的就是该 run 的默认仓。
        entity.setWarehouseId(resolvedWarehouseId);
        entity.setParamsJson(params.canonicalJson());
        entity.setAlgorithmVersion(request.algorithmVersion());
        entity.setPromptVersion(blankToNull(request.promptVersion()));
        entity.setModelName(blankToNull(request.modelName()));
        entity.setLlmStatus(LlmStatus.NOT_REQUESTED.name());
        // ★ R108：请求窗口落 data_start_date/data_end_date——这两列是幂等比对里
        //   date_from/date_to 的唯一载体（表无独立列，本批禁新增迁移）。
        //   B19/B20 回写结果时不得覆盖已有值，否则重放判定会漂移。
        entity.setDataStartDate(request.dateFrom());
        entity.setDataEndDate(request.dateTo());
        // ★ R135（§5.38 裁定 #14）：total_skus 的语义由「请求声明数」改为「装配后实际数」。
        //   B20.1 之前用 scope.plannedSkuCount()，对 ALL_ACTIVE/CATEGORY 恒为 0——
        //   用户看到「计划 0 个 SKU」而 Python 实际算了 N 个。装配已在 insertPending 之前完成，
        //   因此直接写 items.size()，无需二次 UPDATE。
        entity.setTotalSkus(assembledCount);
        entity.setProcessedSkus(0);
        entity.setProgress(0);
        entity.setRequestId(RequestIdContext.get());
        entity.setIdempotencyKey(idempotencyKey);
        entity.setTriggeredBy(snapshot.userId());
        entity.setTimeoutAt(now.plusSeconds(TIMEOUT_SECONDS));
        entity.setCreatedBy(snapshot.userId());
        entity.setUpdatedBy(snapshot.userId());

        for (int attempt = 1; attempt <= MAX_RUN_NO_ATTEMPTS; attempt++) {
            // ★ 重置自增主键：上一次失败若已回填 id，重复 insert 会带主键 → 撞 PRIMARY
            entity.setId(null);
            entity.setRunNo(nextRunNo(dayPrefix));
            try {
                runMapper.insert(entity);
                // 读回数据库生成列（created_at / updated_at），保证响应与库内一字不差
                AnalysisRunEntity saved = runMapper.selectById(entity.getId());
                return saved == null ? entity : saved;
            } catch (DuplicateKeyException ex) {
                // 并发同键：另一请求已抢先落库 → 交给 createRun 走 c 的幂等判定（R108/A2）
                AnalysisRunEntity concurrent = findByIdempotencyKey(idempotencyKey);
                if (concurrent != null) {
                    throw new IdempotentRace(concurrent);
                }
                log.warn("run_no 撞唯一键，重取序列：attempt={}/{} runNo={}",
                        attempt, MAX_RUN_NO_ATTEMPTS, entity.getRunNo());
                if (attempt == MAX_RUN_NO_ATTEMPTS) {
                    throw new BizException(ErrorCodes.COM_0001, "运行编号生成冲突，请重试");
                }
            }
        }
        // 循环体在第 3 次失败时已抛出，此行仅为编译可达性
        throw new BizException(ErrorCodes.COM_0001, "运行编号生成冲突，请重试");
    }

    /** 团队内当日下一序号（{@code count + 1}；撞键由调用方重试）。 */
    private String nextRunNo(String dayPrefix) {
        Long count = runMapper.selectCount(Wrappers.<AnalysisRunEntity>lambdaQuery()
                .likeRight(AnalysisRunEntity::getRunNo, dayPrefix));
        long sequence = (count == null ? 0L : count) + 1L;
        if (sequence > 9999L) {
            throw new BizException(ErrorCodes.COM_0001, "当日分析任务数量已达上限，请明日再试");
        }
        return dayPrefix + String.format("%04d", sequence);
    }

    // =====================================================================
    // f/g. 状态推进 + 审计
    // =====================================================================

    /** {@code PENDING → RUNNING}（{@code §11.5} 202 示例：{@code progress=5}、{@code current_step=VALIDATING}）。 */
    private void markRunning(AnalysisRunEntity run, Boolean enableLlm) {
        LocalDateTime startedAt = LocalDateTime.now(ZoneOffset.UTC);
        AnalysisRunEntity patch = new AnalysisRunEntity();
        patch.setId(run.getId());
        patch.setStatus(AnalysisRunStatus.RUNNING.name());
        patch.setStartedAt(startedAt);
        patch.setCurrentStep("VALIDATING");
        patch.setProgress(5);
        patch.setLlmStatus(Boolean.TRUE.equals(enableLlm)
                ? LlmStatus.PENDING.name() : LlmStatus.NOT_REQUESTED.name());
        runMapper.updateById(patch);

        run.setStatus(patch.getStatus());
        run.setStartedAt(startedAt);
        run.setCurrentStep(patch.getCurrentStep());
        run.setProgress(patch.getProgress());
        run.setLlmStatus(patch.getLlmStatus());
    }

    /** {@code PENDING → FAILED}（发起失败补偿；{@code error_message} 为对外脱敏文案）。 */
    private void markFailed(AnalysisRunEntity run, String errorCode, String errorMessage) {
        LocalDateTime finishedAt = LocalDateTime.now(ZoneOffset.UTC);
        AnalysisRunEntity patch = new AnalysisRunEntity();
        patch.setId(run.getId());
        patch.setStatus(AnalysisRunStatus.FAILED.name());
        patch.setErrorCode(errorCode);
        patch.setErrorMessage(truncate(errorMessage, ERROR_MESSAGE_MAX_LENGTH));
        patch.setFinishedAt(finishedAt);
        runMapper.updateById(patch);

        run.setStatus(patch.getStatus());
        run.setErrorCode(errorCode);
        run.setErrorMessage(patch.getErrorMessage());
        run.setFinishedAt(finishedAt);
    }

    /** 审计 {@code ANALYSIS_RUN_CREATE}（{@code #15}，{@code biz_type=ANALYSIS_RUN}，{@code SUCCESS}）。 */
    private void auditCreate(TeamContext.Snapshot snapshot, AnalysisRunEntity run, AnalysisScope scope) {
        String summary = "创建分析任务：" + scope.scopeType().name()
                + "，计划 SKU " + run.getTotalSkus() + " 个";
        auditWriteService.record(AuditEvent.builder()
                .teamId(snapshot.teamId())
                .action(AuditAction.ANALYSIS_RUN_CREATE)
                .bizType(AUDIT_BIZ_TYPE)
                .bizId(run.getId())
                .bizNo(run.getRunNo())
                .operatorId(snapshot.userId())
                .operatorName(snapshot.username())
                .operatorRole(primaryRole(snapshot))
                .result(AuditResult.SUCCESS)
                .changeSummary(summary)
                .build());
    }

    /** 审计 {@code ANALYSIS_RUN_FINISH}（{@code #16}；发起失败记 {@code FAILURE}）。 */
    private void auditFinish(TeamContext.Snapshot snapshot, AnalysisRunEntity run,
                             AuditResult result, String summary, String errorCode) {
        auditWriteService.record(AuditEvent.builder()
                .teamId(snapshot.teamId())
                .action(AuditAction.ANALYSIS_RUN_FINISH)
                .bizType(AUDIT_BIZ_TYPE)
                .bizId(run.getId())
                .bizNo(run.getRunNo())
                .operatorId(snapshot.userId())
                .operatorName(snapshot.username())
                .operatorRole(primaryRole(snapshot))
                .result(result)
                .errorCode(errorCode)
                .changeSummary(summary)
                .build());
    }

    /**
     * 审计 {@code ANALYSIS_RUN_CANCEL}（{@code DATABASE_DESIGN §6-J.1 #29}，{@code result=SUCCESS}）。
     *
     * <p>★ 只在 CAS 成功时调用<b>恰一次</b>：幂等重放（已 {@code CANCELLED}）不再记；
     * 也<b>不</b>额外记 {@code ANALYSIS_RUN_FINISH}——取消与「运行结束」是两个语义
     * （任务书 §A6 / §八.7）。
     *
     * @param fromStatus CAS 前读到的状态（{@code PENDING}/{@code RUNNING}），仅用于审计摘要
     */
    private void auditCancel(TeamContext.Snapshot snapshot, AnalysisRunEntity run, String fromStatus) {
        auditWriteService.record(AuditEvent.builder()
                .teamId(snapshot.teamId())
                .action(AuditAction.ANALYSIS_RUN_CANCEL)
                .bizType(AUDIT_BIZ_TYPE)
                .bizId(run.getId())
                .bizNo(run.getRunNo())
                .operatorId(snapshot.userId())
                .operatorName(snapshot.username())
                .operatorRole(primaryRole(snapshot))
                .result(AuditResult.SUCCESS)
                .changeSummary("取消分析任务：" + fromStatus + " → CANCELLED")
                .build());
    }

    // =====================================================================
    // 内部：内部契约请求构造与工具
    // =====================================================================

    /** 领域模型 + 装配结果 → 内部契约请求（{@code items} 由 {@code AnalysisItemAssembler} 给出）。 */
    private static AiRunCreateRequest toAiRequest(AnalysisRunEntity run,
                                                  AnalysisScope scope,
                                                  AnalysisParams params,
                                                  CreateAnalysisRunRequest request,
                                                  List<AiRunItem> items) {
        return new AiRunCreateRequest(
                run.getId(),
                run.getTeamId(),
                run.getWarehouseId(),
                AiScope.from(scope),
                request.dateFrom(),
                request.dateTo(),
                AiParams.from(params),
                run.getAlgorithmVersion(),
                run.getPromptVersion(),
                run.getModelName(),
                items);
    }

    private static String primaryRole(TeamContext.Snapshot snapshot) {
        return snapshot.roles().isEmpty() ? null : snapshot.roles().get(0);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
