package com.shoppilot.inventory.domain;

import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.ApiErrorDetail;
import com.shoppilot.common.web.BizException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 库存流水的业务来源类型（{@code DATABASE_DESIGN §6-C.3} / {@code §5.10}）。
 *
 * <p>与 {@link InventoryTxnType}（库存动作）<b>正交</b>：一张到货单可能产生「入库 + 冲销」两种动作，
 * 但来源都是 {@code GOODS_RECEIPT}。
 *
 * <p>取值必须与 {@code V1.0.6__inventory.sql} 的
 * {@code ck_invtxn_biz_type CHECK (biz_type IN (...))} <b>逐字一致</b>；
 * 调用方传入的字符串在此先做成员校验，避免把非法值送到 DB 换一个 {@code ERROR 3819}
 * （{@code 3819} 会被全局异常处理器译成 500，而「传了不认识的 biz_type」本质是请求参数非法）。
 */
public enum InventoryBizType {

    /** 到货单（{@code purchasing}）。 */
    GOODS_RECEIPT,
    /** 盘点单。 */
    COUNT,
    /** 手工调整（无单据，{@code biz_ref_id} 可空）。 */
    MANUAL,
    /** 数据导入批次（{@code import_batches.id}）。 */
    IMPORT,
    /** 销售（首期预留）。 */
    SALE,
    /** 冲销（{@code biz_ref_id = 被冲销的流水 id}）。 */
    REVERSAL;

    /**
     * 解析调用方传入的 {@code biz_type}；空值 / 未知值一律 {@code COM-0001}（400）。
     *
     * <p>大小写不敏感（统一转大写匹配），但返回值恒为枚举名，保证落库值与 CHECK 集合逐字一致。
     */
    public static InventoryBizType parse(String raw) {
        String names = Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(" / "));
        if (raw == null || raw.isBlank()) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(ApiErrorDetail.of("biz_type", "biz_type 必填，须为 " + names)));
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        Optional<InventoryBizType> hit = Arrays.stream(values())
                .filter(value -> value.name().equals(normalized))
                .findFirst();
        return hit.orElseThrow(() -> BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                List.of(ApiErrorDetail.of("biz_type", "biz_type 须为 " + names + "，实测：" + raw))));
    }
}
