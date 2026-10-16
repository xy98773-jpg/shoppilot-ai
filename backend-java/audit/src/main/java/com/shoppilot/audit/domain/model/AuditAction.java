package com.shoppilot.audit.domain.model;

/**
 * 审计动作常量（{@code audit_logs.action}）——**35 条权威清单**。
 *
 * <p>权威来源：{@code DESIGN_BASIS.md §9.17} 的 {@code AuditAction} 行（成员资格唯一权威）；
 * 每条常量的<b>编号（#1–#35，稳定不变）、语义、{@code biz_type}、{@code result}</b>见
 * {@code DATABASE_DESIGN.md §6-J.1}。本枚举按 §6-J.1 的<b>编号顺序</b>声明，
 * javadoc 中的 {@code #n} 即该条编号（下游流程文档按编号引用，禁改）。
 *
 * <h2>三条收敛说明（§9.17 补充说明原文口径）</h2>
 * <ul>
 *   <li>{@link #MASTER_DATA_STATUS_CHANGE} <b>覆盖主数据的创建/更新/停用/启用</b>
 *       （商品、SKU、供应商、仓库四类），以 {@code audit_logs.biz_type}
 *       （{@code PRODUCT}/{@code SKU}/{@code SUPPLIER}/{@code WAREHOUSE}）区分，
 *       <b>不再按实体拆分独立常量</b>；</li>
 *   <li>{@link #AUDIT_LOG_EXPORT} 是<b>唯一被审计的查询类操作</b>（导出受角色与团队隔离约束，破例审计）；</li>
 *   <li>{@link #PURCHASE_ORDER_CONFLICT_ACK} 记录"重复采购冲突的显式确认"（{@code D-16}），
 *       与 {@link #PURCHASE_ORDER_CREATE} 分开记录。</li>
 * </ul>
 *
 * <p><b>演进纪律</b>：本枚举成员<b>只允许追加，不允许改名 / 删除 / 改语义</b>——
 * 编号与语义是跨批次、跨文档的稳定契约（M2-T11 起 35 条齐全，缺条会导致后续批次反复改动）。
 */
public enum AuditAction {

    /** #1 登录成功（{@code biz_type=USER}，result=SUCCESS）。 */
    AUTH_LOGIN_SUCCESS,
    /** #2 登录失败（{@code biz_type=USER}，result=FAILURE，{@code error_code=AUTH-1002}）。 */
    AUTH_LOGIN_FAILED,
    /** #3 用户禁用/启用（{@code biz_type=USER}，result=SUCCESS）。 */
    USER_STATUS_CHANGE,
    /** #4 团队成员角色变更（{@code biz_type=TEAM_MEMBER}，result=SUCCESS）。 */
    TEAM_MEMBER_ROLE_CHANGE,
    /** #5 团队成员移出（{@code biz_type=TEAM_MEMBER}，result=SUCCESS）。 */
    TEAM_MEMBER_REMOVE,
    /** #6 仓库/供应商/商品/SKU 的创建/更新/停用/启用（{@code biz_type=WAREHOUSE/SUPPLIER/PRODUCT/SKU}，result=SUCCESS）。 */
    MASTER_DATA_STATUS_CHANGE,
    /** #7 补货策略变更（{@code biz_type=REPLENISHMENT_POLICY}，result=SUCCESS）。 */
    REPLENISHMENT_POLICY_UPSERT,
    /** #8 导入批次创建（{@code biz_type=IMPORT_BATCH}，result=SUCCESS）。 */
    IMPORT_BATCH_CREATE,
    /** #9 导入落库完成（{@code biz_type=IMPORT_BATCH}，result=SUCCESS/FAILURE）。 */
    IMPORT_BATCH_COMPLETE,
    /** #10 强制重导（{@code biz_type=IMPORT_BATCH}，result=SUCCESS）。 */
    IMPORT_BATCH_FORCE_REIMPORT,
    /** #11 库存手工调整（{@code biz_type=INVENTORY_BALANCE}，result=SUCCESS）。 */
    INVENTORY_MANUAL_ADJUST,
    /** #12 库存盘点调整（{@code biz_type=INVENTORY_BALANCE}，result=SUCCESS）。 */
    INVENTORY_COUNT_ADJUST,
    /** #13 库存冲销（{@code biz_type=INVENTORY_TRANSACTION}，result=SUCCESS）。 */
    INVENTORY_REVERSE,
    /** #14 库存对账不一致（{@code biz_type=INVENTORY_BALANCE}，result=FAILURE，系统任务 {@code operator_id=NULL}）。 */
    INVENTORY_RECONCILE_MISMATCH,
    /** #15 分析任务创建（{@code biz_type=ANALYSIS_RUN}，result=SUCCESS）。 */
    ANALYSIS_RUN_CREATE,
    /** #16 分析任务终态（{@code biz_type=ANALYSIS_RUN}，result=SUCCESS；{@code FAILED}/{@code TIMEOUT} 记 FAILURE）。 */
    ANALYSIS_RUN_FINISH,
    /** #17 销售数据 upsert（{@code biz_type=SALES_DAILY}，result=SUCCESS，含被覆盖行数）。 */
    SALES_DAILY_UPSERT,
    /** #18 建议数量修改（{@code biz_type=REPLENISHMENT_ITEM}，result=SUCCESS）。 */
    REPLENISHMENT_QTY_UPDATE,
    /** #19 建议提交审核（{@code biz_type=REPLENISHMENT_ITEM}，result=SUCCESS）。 */
    REPLENISHMENT_SUBMIT,
    /** #20 建议审批通过（{@code biz_type=REPLENISHMENT_ITEM}，result=SUCCESS）。 */
    REPLENISHMENT_APPROVE,
    /** #21 建议驳回（{@code biz_type=REPLENISHMENT_ITEM}，result=SUCCESS）。 */
    REPLENISHMENT_REJECT,
    /** #22 建议转采购（{@code biz_type=REPLENISHMENT_ITEM}，result=SUCCESS）。 */
    REPLENISHMENT_CONVERT,
    /** #23 采购单创建（{@code biz_type=PURCHASE_ORDER}，result=SUCCESS）。 */
    PURCHASE_ORDER_CREATE,
    /** #24 采购单确认（审批）（{@code biz_type=PURCHASE_ORDER}，result=SUCCESS）。 */
    PURCHASE_ORDER_CONFIRM,
    /** #25 采购单取消（{@code biz_type=PURCHASE_ORDER}，result=SUCCESS）。 */
    PURCHASE_ORDER_CANCEL,
    /** #26 到货单确认（入库）（{@code biz_type=GOODS_RECEIPT}，result=SUCCESS）。 */
    GOODS_RECEIPT_CONFIRM,
    /** #27 到货单作废（{@code biz_type=GOODS_RECEIPT}，result=SUCCESS）。 */
    GOODS_RECEIPT_CANCEL,
    /** #28 团队成员加入（{@code biz_type=TEAM_MEMBER}，result=SUCCESS）。 */
    TEAM_MEMBER_ADD,
    /** #29 分析任务取消（{@code biz_type=ANALYSIS_RUN}，result=SUCCESS）。 */
    ANALYSIS_RUN_CANCEL,
    /** #30 建议撤回（撤回待审）（{@code biz_type=REPLENISHMENT_ITEM}，result=SUCCESS）。 */
    REPLENISHMENT_WITHDRAW,
    /** #31 建议作废（审批前撤销）（{@code biz_type=REPLENISHMENT_ITEM}，result=SUCCESS）。 */
    REPLENISHMENT_CANCEL,
    /** #32 采购单标记已下单（{@code biz_type=PURCHASE_ORDER}，result=SUCCESS）。 */
    PURCHASE_ORDER_ORDERED,
    /** #33 到货单冲销（{@code biz_type=GOODS_RECEIPT}，result=SUCCESS）。 */
    GOODS_RECEIPT_REVERSE,
    /** #34 审计/业务数据导出（{@code biz_type=AUDIT_LOG}，result=SUCCESS；唯一被审计的查询类操作）。 */
    AUDIT_LOG_EXPORT,
    /** #35 转采购前重复采购冲突的显式确认（{@code biz_type=PURCHASE_ORDER}，result=SUCCESS；{@code D-16}）。 */
    PURCHASE_ORDER_CONFLICT_ACK;

    /**
     * 权威清单的常量条数（{@code DESIGN_BASIS §9.17} / {@code DATABASE_DESIGN §6-J.1}）。
     *
     * <p>供测试与文档校准使用；新增常量必须**先改权威文档**再追加成员（文档一致性红线）。
     */
    public static int expectedCount() {
        return 35;
    }
}
