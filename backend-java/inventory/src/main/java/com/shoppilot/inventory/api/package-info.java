/**
 * {@code inventory} 模块的对外 HTTP 层（{@code MODULE_BOUNDARIES §2.5}）。
 *
 * <p><b>本批（M3-B6 / M3-T09）刻意不建任何端点</b>：本批只交付「唯一写入口」——
 * {@link com.shoppilot.inventory.application.InventoryWriteService} 及其 DTO / 实体 / Mapper 与
 * {@code V1.0.7} 修正迁移。清单与导出端点在 {@code M3-T10/T14}、调整与冲销端点在 {@code M3-T11}
 * （= {@code B7}），届时本包才有 {@code *Controller}。
 *
 * <p>提前建包而不是等到 B7，是为了让「四层结构（api / application / domain / infrastructure）」
 * 在模块建立时就固定下来，避免后续批次把 Controller 塞进 {@code application} 包。
 */
package com.shoppilot.inventory.api;
