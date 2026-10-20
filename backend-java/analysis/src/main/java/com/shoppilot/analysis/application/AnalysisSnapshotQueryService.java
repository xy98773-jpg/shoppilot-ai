package com.shoppilot.analysis.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.shoppilot.analysis.infrastructure.persistence.entity.AnalysisItemEntity;
import com.shoppilot.analysis.infrastructure.persistence.entity.AnalysisRunEntity;
import com.shoppilot.analysis.infrastructure.persistence.mapper.AnalysisItemMapper;
import com.shoppilot.analysis.infrastructure.persistence.mapper.AnalysisRunMapper;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 分析快照的<b>跨模块只读</b>入口（M5-T02）。
 *
 * <h2>为什么需要它（{@code R126} 的同型债必须止住）</h2>
 * <p>{@code replenishment} 需要由分析明细派生补货建议，但
 * {@code MODULE_BOUNDARIES §8.2 E-2} 明确禁止它直接注入 {@code AnalysisItemMapper}
 * ——那会让「analysis 的表结构」成为两个模块的耦合点（与 {@code R126} 同一类债）。
 * 因此由 analysis 侧提供一个<b>只读</b>方法，返回内部只读 DTO；调用方不接触实体与 Mapper。
 *
 * <h2>方法契约</h2>
 * <ul>
 *   <li>{@code listGenerationSource(runId)}：一次调用拿齐「run 元信息 + 可生成建议的快照行」，
 *       避免「先查 run 状态、再查明细」两次跨模块往返；</li>
 *   <li>快照行<b>在此过滤 {@code qty_suggest > 0}</b>（{@code DESIGN_BASIS §10.5}：
 *       {@code Q_base <= 0} 不生成建议、只留风险），调用方拿到即可直接落库；</li>
 *   <li>run 不存在或<b>跨团队</b>时返回 {@code null}（租户线使两者同表现，不泄漏他团队是否存在）；
 *       状态是否可生成<b>不在此判定</b>——那是调用方的业务规则，错误码也应由调用方选择。</li>
 * </ul>
 *
 * <p>★ 本类<b>只读</b>：不写任何表、不写审计。查询走租户线，因此天然限定当前团队。
 */
@Service
public class AnalysisSnapshotQueryService {

    private final AnalysisRunMapper runMapper;
    private final AnalysisItemMapper itemMapper;

    public AnalysisSnapshotQueryService(AnalysisRunMapper runMapper, AnalysisItemMapper itemMapper) {
        this.runMapper = runMapper;
        this.itemMapper = itemMapper;
    }

    /**
     * 读取「可生成补货建议」的分析快照。
     *
     * @param runId 分析运行 ID（当前团队）
     * @return run 元信息 + {@code qty_suggest > 0} 的明细（按 id 升序，保证落库顺序稳定）；
     *         run 不存在或跨团队返回 {@code null}
     */
    @Transactional(readOnly = true)
    public GenerationSource listGenerationSource(Long runId) {
        if (runId == null) {
            return null;
        }
        AnalysisRunEntity run = runMapper.selectById(runId);
        if (run == null) {
            return null;
        }
        List<AnalysisItemEntity> items = itemMapper.selectList(
                Wrappers.<AnalysisItemEntity>lambdaQuery()
                        .eq(AnalysisItemEntity::getRunId, runId)
                        .gt(AnalysisItemEntity::getQtySuggest, BigDecimal.ZERO)
                        .orderByAsc(AnalysisItemEntity::getId));
        return new GenerationSource(run.getId(), run.getTeamId(), run.getStatus(),
                items.stream().map(GenerationItem::of).toList());
    }

    /** run 元信息 + 可生成建议的快照行（内部只读 DTO，不暴露实体）。 */
    public record GenerationSource(Long runId, Long teamId, String status, List<GenerationItem> items) {
    }

    /** 单条快照的生成所需字段（{@code ANALYSIS_CONTRACT §9.2} 的来源列）。 */
    public record GenerationItem(Long id,
                                 Long skuId,
                                 Long productId,
                                 Long warehouseId,
                                 BigDecimal qtySuggest,
                                 String riskLevel,
                                 String llmExplanation,
                                 Integer leadTimeDays) {

        static GenerationItem of(AnalysisItemEntity entity) {
            return new GenerationItem(entity.getId(), entity.getSkuId(), entity.getProductId(),
                    entity.getWarehouseId(), entity.getQtySuggest(), entity.getRiskLevel(),
                    entity.getLlmExplanation(), entity.getLeadTimeDays());
        }
    }
}
