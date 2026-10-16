package com.shoppilot.importdata.infrastructure.parser;

import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.BizException;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * 按扩展名选择解析器（{@code IMPORT_WORKFLOW §8.1} 白名单：{@code .csv} / {@code .xlsx} / {@code .xls}）。
 *
 * <p>扩展名已在 M3-T02 步骤 2 校验过（非法扩展名不会建批次），故此处取不到解析器
 * 属「数据不一致」而非用户输入错误，按 {@code IMP-4002} 处理（不泄漏内部状态）。
 */
@Component
public class ImportParserFactory {

    private final List<ImportFileParser> parsers;

    public ImportParserFactory(List<ImportFileParser> parsers) {
        this.parsers = parsers;
    }

    /**
     * 按文件扩展名取解析器。
     *
     * @param fileName 原始文件名（取其最后一个 {@code .} 之后的扩展名，大小写不敏感）
     * @return 对应的解析器
     * @throws BizException 无匹配解析器 → {@code 400 IMP-4002}
     */
    public ImportFileParser of(String fileName) {
        String extension = extensionOf(fileName);
        for (ImportFileParser parser : parsers) {
            if (parser.supports(extension)) {
                return parser;
            }
        }
        throw new BizException(ErrorCodes.IMP_4002, "文件无法解析，请检查格式与编码");
    }

    static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 || dot == fileName.length() - 1
                ? ""
                : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
