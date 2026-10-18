package com.shoppilot.importdata.application;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * <b>导入链的编码键标准化工具</b>（唯一实现）。
 *
 * <h2>口径依据（{@code R85} 裁定）</h2>
 * {@code IMPORT_WORKFLOW §8.6} 末行：「<b>编码大小写一律不敏感等价</b>」，适用范围明文包含
 * 「所有『文件内重复』判定键 <b>+</b> 所有 {@code code} 字段的<b>引用解析</b>」。
 *
 * <p>物理依据：{@code skus.sku_code} / {@code warehouses.code} / {@code suppliers.code} 的唯一键
 * {@code (team_id, <code>)} 受 MySQL 的 {@code utf8mb4_0900_ai_ci} 约束 ⇒
 * <b>物理上不可能</b>共存 {@code SKU-A} 与 {@code sku-a} 两条记录；且所有引用解析 SQL
 * （{@code in("sku_code", …)}、{@code WHERE sku_code = ?}）都走该排序规则，
 * 因此<b>「引用解析大小写不敏感」是既有既成事实</b>。
 *
 * <h2>为什么必须是单一实现</h2>
 * 判定键若按大小写敏感比较，会与上述既成事实<b>分叉</b>：同批两行 {@code SKU-A} / {@code sku-a}
 * 校验期判不出重复，随后被写服务幂等键当成「同内容重放」而<b>静默只落一行</b>
 * （现象：{@code success_rows=2} 但余额只加了一次）。
 * 若每个业务类型各写一份折叠逻辑，迟早又会出现某一处漏改——本类存在的意义就是<b>禁止</b>这种分叉。
 *
 * <h2>规则细节</h2>
 * <ul>
 *   <li><b>{@code trim} 在前</b>：{@code §8.6} 口径含 trim，且 CSV/Excel 解析器<b>不对单元格做 trim</b>，
 *       判定键必须与引用解析使用同一套标准化；</li>
 *   <li><b>必须 {@link Locale#ROOT}</b>：土耳其语环境下 {@code "i".toUpperCase()} 用默认语言
 *       会得到「带点大写 I」，与 {@code utf8mb4_0900_ai_ci} 的折叠结果不一致
 *       ⇒ 同一编码在不同机器上判定不同（缺陷复发）。</li>
 * </ul>
 *
 * <p>❌ <b>不得</b>改成「库内原样区分大小写」——那需要把排序规则改为 {@code utf8mb4_bin}，
 * 口径已定为不敏感。
 *
 * <p>本类为包级可见（无 {@code public}）：只在 {@code importdata} 模块内部使用，
 * 不对外承诺 API。
 */
final class ImportCodeKeys {

    private ImportCodeKeys() {
    }

    /**
     * 编码标准化的<b>唯一</b>实现：{@code trim()} 之后再 {@code toUpperCase(Locale.ROOT)}。
     *
     * <p>所有「文件内重复」判定键与所有编码查表都必须走本方法；
     * 禁止任何一处自行 {@code toUpperCase()} / {@code equalsIgnoreCase()} /
     * {@code String.CASE_INSENSITIVE_ORDER}。
     *
     * @param raw 原始单元格值或库中编码（可含空白，可为 {@code null}）
     * @return 标准化后的比较键；{@code null} 入参返回空串
     */
    static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 把 catalog 返回的「原始大小写键」表折叠成规范化键的表。
     *
     * <p>存在的理由：{@code in("sku_code", …)} 在 {@code utf8mb4_0900_ai_ci} 下能匹配
     * {@code sku-a}，但 {@code SkuQueryService#getIdsByCodes} 返回表的键是<b>库里存的写法</b>
     * （{@code ids.put(row.getSkuCode(), …)}）。不折叠就用文件里的原始写法 {@code get}，
     * 小写编码必然查不到 ⇒ 该行被误判成「引用的SKU不存在」（{@code CAT-3001}）。
     *
     * <p>用 {@link TreeMap} 仅使键序稳定（便于日志/调试），语义与 {@code HashMap} 相同。
     *
     * @param raw catalog 侧返回的表（键为库中原样编码）；{@code null} 视为空表
     * @return 折叠后的表（键为规范化编码）
     */
    static Map<String, Long> fold(Map<String, Long> raw) {
        Map<String, Long> folded = new TreeMap<>();
        if (raw != null) {
            raw.forEach((code, id) -> folded.put(normalize(code), id));
        }
        return folded;
    }
}