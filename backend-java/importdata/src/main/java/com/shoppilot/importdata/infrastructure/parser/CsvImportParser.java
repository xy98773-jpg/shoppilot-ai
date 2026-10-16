package com.shoppilot.importdata.infrastructure.parser;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * CSV 解析器（{@code IMPORT_WORKFLOW §8.2 CSV 编码问题} + {@code §8.5 流式}）。
 *
 * <h2>编码探测（§8.2 系统行为列原文）</h2>
 * 「读取前若干字节探测 BOM（{@code EF BB BF} → UTF-8 BOM）；无 BOM 时依次按
 * <b>UTF-8 → GB18030</b> 尝试解码并校验成功率；探测到 BOM 则剥离……无法识别或解码失败 → 批次
 * {@code FAILED}」，错误码 {@code IMP-4002}（解析失败）/ {@code IMP-4012}（编码不受支持）。
 *
 * <p><b>★ 实现要点</b>：必须用 {@link CodingErrorAction#REPORT} 的 {@link CharsetDecoder}——
 * 默认的 {@code REPLACE} 会把非法字节静默替换成 {@code U+FFFD}，导致「GBK 文件被当作 UTF-8
 * 读出乱码」这类<b>静默错误</b>。REPORT 才能抛出 {@link MalformedInputException} 触发回退。
 *
 * <h2>回退的重放问题</h2>
 * 第一次（UTF-8）可能已推送了部分行才失败，故回退前调用
 * {@link ImportFileParser.ParseSink#reset()} 清空接收器，再按 GB18030 从头重放
 * （文件在磁盘上，重放是顺序 I/O，不违背「流式」——内存占用仍与行数无关）。
 *
 * <h2>列数不一致（§8.4）</h2>
 * 「该行记 {@code ERROR COM-0001}，其余行继续」——即<b>不是解析失败</b>；
 * 由 {@link ParserRows#fit} 对齐列数，校验层按 §8.4 记 ERROR。
 */
@Component
public class CsvImportParser implements ImportFileParser {

    /** UTF-8 BOM：{@code EF BB BF}（§8.2）。 */
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private static final Charset GB18030 = Charset.forName("GB18030");

    @Override
    public boolean supports(String extension) {
        return "csv".equals(extension);
    }

    @Override
    public void stream(Path file, ParseOptions options, ParseSink sink) throws ParseFailureException {
        if (file == null || !Files.isRegularFile(file)) {
            throw ParseFailureException.unparsable();
        }
        int bomLength = detectBomLength(file);

        Charset explicit = options.encoding() == null || options.encoding().isBlank()
                ? null
                : charsetOf(options.encoding());

        if (explicit != null) {
            // parse_options 已明确编码（再解析 / 校验场景）：不做回退，失败即 IMP-4012
            try {
                readAll(file, explicit, bomLength, options, sink);
            } catch (MalformedInputRuntimeException ex) {
                throw ParseFailureException.unsupportedEncoding();
            } catch (IOException ex) {
                throw ParseFailureException.unparsable();
            }
            sink.meta(explicit.name(), List.of(), null);
            return;
        }

        // 自动探测：UTF-8 → GB18030（§8.2）
        try {
            readAll(file, StandardCharsets.UTF_8, bomLength, options, sink);
            sink.meta("UTF-8", List.of(), null);
        } catch (MalformedInputRuntimeException firstFailure) {
            sink.reset();
            try {
                readAll(file, GB18030, bomLength, options, sink);
                sink.meta("GB18030", List.of(), null);
            } catch (MalformedInputRuntimeException secondFailure) {
                throw ParseFailureException.unsupportedEncoding();
            } catch (IOException ex) {
                throw ParseFailureException.unparsable();
            }
        } catch (IOException ex) {
            throw ParseFailureException.unparsable();
        }
    }

    // =====================================================================

    private static int detectBomLength(Path file) throws ParseFailureException {
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(file))) {
            byte[] head = raw.readNBytes(UTF8_BOM.length);
            for (int i = 0; i < UTF8_BOM.length; i++) {
                if (head.length <= i || head[i] != UTF8_BOM[i]) {
                    return 0;
                }
            }
            return UTF8_BOM.length;
        } catch (IOException ex) {
            throw ParseFailureException.unparsable();
        }
    }

    private void readAll(Path file, Charset charset, int skipBytes, ParseOptions options, ParseSink sink)
            throws IOException, MalformedInputRuntimeException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            if (skipBytes > 0 && in.skip(skipBytes) != skipBytes) {
                throw new IOException("BOM 剥离失败");
            }
            CharsetDecoder decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            Reader reader = new InputStreamReader(in, decoder);
            RowDispatcher dispatcher = new RowDispatcher(options, sink, true);
            CsvRowReader rows = new CsvRowReader(reader);
            List<String> row;
            while ((row = rows.nextRow()) != null) {
                dispatcher.accept(row);
            }
            if (!dispatcher.headerResolved()) {
                // 空文件 / 表头行号越界 → §8.4「0 行」语义
                throw new IOException("文件无表头行");
            }
        } catch (MalformedInputException ex) {
            // 受检异常不便穿透 stream() 边界：包装后由调用方按编码回退处理
            throw new MalformedInputRuntimeException(ex);
        }
    }

    private static Charset charsetOf(String name) {
        try {
            return Charset.forName(name.trim());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** {@link MalformedInputException} 的运行时包装（跨越 {@code stream} 的受检异常边界）。 */
    private static final class MalformedInputRuntimeException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        MalformedInputRuntimeException(MalformedInputException cause) {
            super(cause);
        }
    }
}
