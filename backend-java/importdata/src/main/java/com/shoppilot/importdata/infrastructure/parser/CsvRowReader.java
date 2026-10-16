package com.shoppilot.importdata.infrastructure.parser;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * CSV 逐行读取器（RFC 4180 语义子集，<b>流式</b>：一次只在内存里保留一行的字符）。
 *
 * <h2>必须支持的三种形态（Excel 另存为 CSV 会用到全部三种）</h2>
 * <ul>
 *   <li>普通字段：{@code a,b,c}</li>
 *   <li>引号包裹（可含逗号 / 换行）：{@code "a,1","b\nc"}</li>
 *   <li>引号内转义引号（{@code ""} 表示一个 {@code "}）：{@code "say ""hi"""}</li>
 * </ul>
 *
 * <p><b>空行策略</b>：整行为空（无字符，或仅 {@code \r}）时<b>跳过</b>，不产生空数据行
 * ——否则文件末尾换行与中间空行会被误计为「缺列」的 ERROR 行（§8.4 列数不一致行才应记 ERROR）。
 */
final class CsvRowReader {

    private final Reader reader;

    /** 单字符回退槽：-2 表示无；其余为已读取待重放的字符（含 -1 = EOF）。 */
    private int pending = -2;

    private boolean eofReached;

    CsvRowReader(Reader reader) {
        this.reader = reader;
    }

    /**
     * 读取下一行；流已结束返回 {@code null}。
     *
     * @return 该行的单元格列表（至少 1 个元素）
     */
    List<String> nextRow() throws IOException {
        if (eofReached) {
            return null;
        }
        // 跳过纯空行（连续的 \r\n / \n / \r）
        int c;
        do {
            c = read();
            if (c == -1) {
                eofReached = true;
                return null;
            }
        } while (c == '\n' || c == '\r');

        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;

        while (true) {
            if (quoted) {
                if (c == -1) {
                    // 未闭合引号：宽容处理，按已读内容结束本行
                    row.add(field.toString());
                    eofReached = true;
                    return row;
                }
                if (c == '"') {
                    int next = read();
                    if (next == '"') {
                        field.append('"');
                        c = read();
                        continue;
                    }
                    quoted = false;
                    c = next;
                    continue;
                }
                field.append((char) c);
                c = read();
                continue;
            }

            // 未进入引号状态：仅当字段尚未累积任何字符时，" 才表示引号包裹的开始
            if (c == '"' && field.length() == 0) {
                quoted = true;
                c = read();
                continue;
            }
            if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
                c = read();
                continue;
            }
            if (c == '\r') {
                int next = read();
                if (next != '\n') {
                    unread(next);
                }
                row.add(field.toString());
                return row;
            }
            if (c == '\n') {
                row.add(field.toString());
                return row;
            }
            if (c == -1) {
                row.add(field.toString());
                eofReached = true;
                return row;
            }
            field.append((char) c);
            c = read();
        }
    }

    private int read() throws IOException {
        if (pending != -2) {
            int c = pending;
            pending = -2;
            return c;
        }
        return reader.read();
    }

    private void unread(int c) {
        pending = c;
    }
}
