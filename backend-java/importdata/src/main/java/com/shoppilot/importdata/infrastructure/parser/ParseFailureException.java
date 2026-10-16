package com.shoppilot.importdata.infrastructure.parser;

import com.shoppilot.common.error.ErrorCodes;

/**
 * 解析失败（批次级，{@code IMPORT_WORKFLOW §6} 步骤 4：{@code UPLOADED → FAILED}）。
 *
 * <p><b>两种错误码（§8.2 / §8.3 / §8.4）</b>：
 * <ul>
 *   <li>{@link ErrorCodes#IMP_4002}：文件损坏、格式非法、工作表缺失、0 数据行 ——「文件无法解析，请检查格式与编码」；</li>
 *   <li>{@link ErrorCodes#IMP_4012}：编码不在支持列表（UTF-8 / GB18030 均无法解码）——「文件编码不受支持，请转为 UTF-8」。</li>
 * </ul>
 *
 * <p>抛出后由 {@code ImportParseService} 把批次推进 {@code FAILED} 并写
 * {@code error_code} / {@code error_message}（终态，不回退 {@code UPLOADED}，裁定 A）。
 */
public class ParseFailureException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String code;

    /**
     * @param code    错误码常量（{@code IMP-4002} / {@code IMP-4012}）
     * @param message 用户可见提示（契约原文措辞）
     */
    public ParseFailureException(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 静态工厂：解析失败（{@code IMP-4002}）。
     *
     * <p><b>用户可见 message 恒为契约原文</b>（ERROR_CODES §2.5 #3）；具体技术原因
     * （如「工作表缺失」「文件为空」）由调用方打日志，不回传客户端（§4.3 不泄露堆栈与 SQL）。
     */
    public static ParseFailureException unparsable() {
        return new ParseFailureException(ErrorCodes.IMP_4002, "文件无法解析，请检查格式与编码");
    }

    /** 静态工厂：编码不受支持（{@code IMP-4012}）。 */
    public static ParseFailureException unsupportedEncoding() {
        return new ParseFailureException(ErrorCodes.IMP_4012, "文件编码不受支持，请转为 UTF-8");
    }

    /** 错误码常量（供服务层推进 {@code FAILED} 时写入 {@code import_batches.error_code}）。 */
    public String code() {
        return code;
    }
}
