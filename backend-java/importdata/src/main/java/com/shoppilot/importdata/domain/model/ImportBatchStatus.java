package com.shoppilot.importdata.domain.model;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 导入批次状态（{@code import_batches.status}，权威见 {@code DESIGN_BASIS.md §9.5}）。
 *
 * <p><b>10 值</b>，与 {@code V1.0.5__importdata.sql} 的 {@code ck_imp_status} 一一对应；
 * 状态流转与状态机图见 {@code IMPORT_WORKFLOW.md §13}（步骤 4 及其后属 M3-T03 起）。
 *
 * <h2>★ 幂等槽位语义（{@code DATABASE_DESIGN §6-E.4}）</h2>
 * 文件级幂等的「槽位」建在<b>可空列 {@code dedupe_hash}</b> 上（R31/R32 裁定：物理列为
 * {@code dedupe_hash}，非 {@code file_hash}）。槽位不是"有哈希就占用"，而是
 * <b>按批次状态判定</b>：
 * <ul>
 *   <li><b>占用</b>（命中即重复 → {@code IMP-4004}）：{@link #UPLOADED} / {@link #PARSED} /
 *       {@link #MAPPED} / {@link #VALIDATED} / {@link #IMPORTING} / {@link #COMPLETED} /
 *       {@link #PARTIAL_SUCCESS}——文件仍"在流程中"或"已成功落地"；</li>
 *   <li><b>释放</b>（不阻挡重传）：{@link #FAILED} / {@link #CANCELLED} /
 *       {@link #DUPLICATE_REJECTED} / 被 {@code superseded_by_batch_id} 取代——
 *       流程已终止，用户修正后重传<b>不应</b>被幂等拦截。</li>
 * </ul>
 *
 * <p>依据优先级：{@code DATABASE_DESIGN §6-E.4}（数据库设计）优先于
 * {@code API_CONTRACT §11.3} 的"已存在成功批次"表述（同级冲突取数据库设计为准）。
 */
public enum ImportBatchStatus {

    /** 已上传（步骤 1-3 终态；M3-T02 新批次的初始状态）。 */
    UPLOADED,
    /** 已解析（步骤 4，M3-T03）。 */
    PARSED,
    /** 已映射（步骤 5，M3-T04）。 */
    MAPPED,
    /** 已校验（步骤 6，M3-T05）。 */
    VALIDATED,
    /** 导入中（步骤 9，M3-T06）。 */
    IMPORTING,
    /** 全部成功（终态）。 */
    COMPLETED,
    /** 部分成功（终态；{@code PARTIAL_SUCCESS} 可强制重导）。 */
    PARTIAL_SUCCESS,
    /** 失败（终态；释放槽位）。 */
    FAILED,
    /** 已取消（终态；释放槽位）。 */
    CANCELLED,
    /** 命中文件级幂等被拒（终态；释放槽位，且本批次 {@code dedupe_hash} 为 NULL）。 */
    DUPLICATE_REJECTED;

    /**
     * 占用幂等槽位的状态（命中即视为重复导入）。
     *
     * <p>{@code LinkedHashSet} 仅为日志/断言输出稳定，语义等同 {@code Set}。
     */
    private static final Set<ImportBatchStatus> OCCUPYING = Set.of(
            UPLOADED, PARSED, MAPPED, VALIDATED, IMPORTING, COMPLETED, PARTIAL_SUCCESS);

    /** 该状态是否占用幂等槽位（占用 = 同团队同哈希同类型再传会被 {@code IMP-4004} 拦下）。 */
    public boolean occupiesDedupeSlot() {
        return OCCUPYING.contains(this);
    }

    /** 占用槽位的状态名集合（供 Wrapper {@code IN} 查询）。 */
    public static Set<String> occupyingNames() {
        Set<String> names = new LinkedHashSet<>();
        OCCUPYING.forEach(status -> names.add(status.name()));
        return names;
    }
}
