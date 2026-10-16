package com.shoppilot.importdata.infrastructure.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 导入原始文件的本地落盘 + 流式 SHA-256（步骤 3 的基础设施）。
 *
 * <p><b>一次读取完成两件事</b>：8KB 缓冲边读边 {@code digest.update}，同时写入目标文件
 * ——因此 {@code file_hash} 与 {@code storage_path} 指向的内容<b>天然一致</b>
 * （不存在"先算哈希再另读一次落盘"导致哈希与文件不一致的窗口）。
 *
 * <p><b>流式</b>：绝不整文件读进内存（{@code IMPORT_WORKFLOW §8.5} 大文件处理；
 * 20MB 上限下也要求流式，避免 N 个并发上传即触发 OOM）。
 *
 * <p><b>不引入对象存储</b>（无 MinIO / S3 / OSS）：首期存本地
 * {@code {storage-dir}/{teamId}/{yyyyMM}/{batchNo}_{消毒后文件名}}。
 *
 * <h2>文件名消毒（防路径穿越）</h2>
 * 原始文件名来自客户端，<b>不可信</b>：先剥离任何目录成分（{@code /} 与 {@code \\}），
 * 再把 {@code ..} 与非 {@code [A-Za-z0-9._-]} 字符替换为 {@code _}，最后按 120 字符截断
 * （保留扩展名）。落盘目录只由服务端拼（{@code teamId} + 年月 + {@code batchNo}）。
 */
@Component
public class LocalImportFileStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalImportFileStorage.class);

    /** 单次读取缓冲区（8KB；流式哈希与落盘共用）。 */
    private static final int BUFFER_SIZE = 8 * 1024;

    /** 消毒后文件名的最大长度（保留扩展名）。 */
    private static final int MAX_FILE_NAME_LENGTH = 120;

    private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    private final Path root;

    public LocalImportFileStorage(@Value("${app.import.storage-dir:./data/import}") String storageDir) {
        this.root = Path.of(storageDir);
    }

    /**
     * 流式落盘并返回「字节数 + SHA-256 + 存储路径」。
     *
     * @param in               原始文件流（调用方负责关闭）
     * @param teamId           当前团队（服务端；目录隔离，绝不来自客户端）
     * @param batchNo          批次号（文件名前缀，保证同批次不互相覆盖）
     * @param originalFileName 客户端原始文件名（仅用于后缀与可读性，已被消毒）
     * @return 落盘结果
     */
    public StoredFile store(InputStream in, long teamId, String batchNo, String originalFileName) {
        Path target = resolveTarget(teamId, batchNo, originalFileName);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 是 JDK 强制实现的算法，不可达；仍 fail-fast 而非静默
            throw new IllegalStateException("运行环境不支持 SHA-256", ex);
        }
        long size = 0L;
        byte[] buffer = new byte[BUFFER_SIZE];
        try {
            Files.createDirectories(target.getParent());
            try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                    size += read;
                }
            }
        } catch (IOException ex) {
            // 落盘失败属基础设施故障：不给客户端回技术细节，交由全局兜底 500 COM-0002（堆栈只进日志）
            log.error("导入文件落盘失败 target={} teamId={} batchNo={}", target, teamId, batchNo, ex);
            throw new IllegalStateException("导入文件保存失败", ex);
        }
        String hash = HexFormat.of().formatHex(digest.digest());
        return new StoredFile(size, hash, target.toString());
    }

    /** 落盘目标路径：{@code {root}/{teamId}/{yyyyMM}/{batchNo}_{消毒文件名}}。 */
    private Path resolveTarget(long teamId, String batchNo, String originalFileName) {
        String month = LocalDate.now(ZoneOffset.UTC).format(YEAR_MONTH);
        return root.resolve(String.valueOf(teamId))
                .resolve(month)
                .resolve(batchNo + "_" + sanitizeFileName(originalFileName));
    }

    /**
     * 文件名消毒：剥目录 → 去 {@code ..} → 非白名单字符替 {@code _} → 截断。
     *
     * <p>包可见仅为单测可覆盖（本批未单独单测，见 {@code ImportUploadIntegrationTest}）。
     */
    static String sanitizeFileName(String original) {
        if (original == null || original.isBlank()) {
            return "upload";
        }
        String name = original.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replace("..", "_");
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (name.length() > MAX_FILE_NAME_LENGTH) {
            int dot = name.lastIndexOf('.');
            String ext = dot > 0 ? name.substring(dot) : "";
            name = name.substring(0, MAX_FILE_NAME_LENGTH - ext.length()) + ext;
        }
        return name.isBlank() ? "upload" : name;
    }

    /**
     * 落盘结果。
     *
     * @param size        实际写入字节数（与 {@code file_size} 一致）
     * @param sha256Hex   SHA-256 十六进制<b>小写</b>（契约 {@code API_CONTRACT §11.3}）
     * @param storagePath 落盘路径（相对或绝对，取决于配置的 {@code storage-dir}）
     */
    public record StoredFile(long size, String sha256Hex, String storagePath) {

        /** 便于日志（不含路径以外的技术细节）。 */
        @Override
        public String toString() {
            return "StoredFile{size=" + size + ", sha256=" + sha256Hex + ", path=" + storagePath + "}";
        }
    }
}
