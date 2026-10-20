package com.shoppilot.common.web;

import com.shoppilot.common.error.ErrorCodeHttpStatus;
import com.shoppilot.common.error.ErrorCodes;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理（基础设施，{@code @RestControllerAdvice}）。
 *
 * <p><b>严格对齐 {@code API_CONTRACT.md §2.3 / §1.3} 与 {@code ERROR_CODES.md §2}</b>：
 * <ul>
 *   <li><b>HTTP 状态语义化</b>：状态码由错误码查 {@link ErrorCodeHttpStatus} 得到（§1.3 使用表），
 *       不再「一律 200」；</li>
 *   <li>字段级校验失败 → <b>400 + {@code COM-0001} + {@code details[]}</b>（§3，details 元素含
 *       {@code field}/{@code message}，{@code field} 为 snake_case）；</li>
 *   <li>无匹配路由/资源不存在 → <b>404 + {@code COM-0003}</b>；</li>
 *   <li>未捕获异常兜底 → <b>500 + {@code COM-0002}</b>（<b>不得</b>用 {@code COM-0009}；
 *       {@code COM-0009} 恒为 409 幂等冲突，见 {@code ERROR_CODES.md §2 使用规范}）；</li>
 *   <li>可归类异常必须归类（405/415/413 等），{@code COM-0002} 只留给真正的未预期错误。</li>
 * </ul>
 *
 * <p>所有响应经 {@link ApiResponse} 统一包络（含 {@code requestId} 与 ISO-8601 {@code timestamp}）。
 * 异常一律记日志（含 {@code request_id} MDC，契约 §7），5xx 记 {@code ERROR}、4xx 记 {@code WARN}。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常：HTTP 状态由错误码映射决定（如 TEAM-2001 → 403，契约 §1.3）。 */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException ex) {
        int status = ErrorCodeHttpStatus.resolve(ex.getErrorCode());
        if (status >= 500) {
            log.error("业务异常(5xx) code={} status={} message={}", ex.getErrorCode(), status, ex.getMessage(), ex);
        } else {
            log.warn("业务异常 code={} status={} message={}", ex.getErrorCode(), status, ex.getMessage());
        }
        return ResponseEntity.status(status)
                .body(ApiResponse.fail(ex.getErrorCode(), ex.getMessage(), ex.getDetails()));
    }

    /**
     * 唯一约束冲突：<b>409 + {@code CAT-3002}</b>（契约 §10.3，主数据编码团队内唯一）。
     *
     * <p>MyBatis-Spring 会把 MySQL {@code errno 1062 / SQLState 23000}（重复键）翻译成
     * {@link DuplicateKeyException}。若不做此归类，唯一约束冲突会落到 {@code Exception} 兜底 →
     * {@code 500 COM-0002}，把「可归类的业务冲突」误报为系统故障（{@code ERROR_CODES §2 使用规范}：
     * 能归类必须归类）。此处统一翻译为 {@code 409 CAT-3002}，并尽力从约束名解析出冲突字段写入
     * {@code details[]}（如 {@code uk_prod_team_code → product_code}）。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiResponse<Void>> handleDuplicateKey(DuplicateKeyException ex) {
        String field = resolveDuplicateField(ex.getMessage());
        List<ApiErrorDetail> details = field == null
                ? null
                : List.of(ApiErrorDetail.of(field, "已存在相同编码"));
        log.warn("唯一约束冲突 code={} field={}", ErrorCodes.CAT_3002, field);
        return ResponseEntity.status(ErrorCodeHttpStatus.resolve(ErrorCodes.CAT_3002))
                .body(ApiResponse.fail(ErrorCodes.CAT_3002, "编码在该团队内已存在", details));
    }

    /** 请求体字段级校验失败：400 + COM-0001 + details[]（契约 §3）。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        List<ApiErrorDetail> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> ApiErrorDetail.of(toSnakeCase(fe.getField()), safeMessage(fe.getDefaultMessage())))
                .toList();
        log.warn("参数校验失败 details={}", details.size());
        return badRequest(details, "请求参数校验失败");
    }

    /** 方法参数（@RequestParam / @PathVariable）校验失败：400 + COM-0001 + details[]。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        List<ApiErrorDetail> details = ex.getConstraintViolations().stream()
                .map(v -> ApiErrorDetail.of(toSnakeCase(lastNode(v)), safeMessage(v.getMessage())))
                .toList();
        log.warn("参数约束校验失败 details={}", details.size());
        return badRequest(details, "请求参数校验失败");
    }

    /** 必填查询参数缺失：400 + COM-0001（归入字段级校验，遵守「能归类必须归类」）。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException ex) {
        List<ApiErrorDetail> details = List.of(
                ApiErrorDetail.of(toSnakeCase(ex.getParameterName()), "缺少必填参数"));
        log.warn("缺少必填参数 param={}", ex.getParameterName());
        return badRequest(details, "请求参数校验失败");
    }

    /**
     * 查询参数类型转换失败：400 + {@code COM-0001}（{@code R142}，{@code B20.4}）。
     *
     * <p>典型触发：{@code GET /runs/{id}/items?page=abc}——{@code page} 声明为 {@code int}，
     * Spring 在参数绑定阶段抛 {@link MethodArgumentTypeMismatchException}。此前本类无对应处理器，
     * 该异常落到 {@link #handleUnexpected(Exception)} 兜底 → {@code 500 COM-0002}，
     * 与 {@code API_CONTRACT §2.3}「字段级校验失败 → 400 + COM-0001」不符，且日志记 ERROR 噪声。
     *
     * <p>★ 口径（{@code ERROR_CODES §2.1} COM-0001）：{@code details[].field} 取参数名并转
     * <b>snake_case</b>（与 {@link #handleValidation} 的字段路径口径一致）；
     * {@code rejectedValue} 回显客户端原始字符串（非敏感值）。
     * <p>★ 作用域是<b>全局</b>的（任何 {@code @RequestParam} 的 {@code int}/{@code long}/枚举转换失败），
     * 属预期收益而非副作用——契约对所有端点都要求这一语义。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        Object rawValue = ex.getValue();
        List<ApiErrorDetail> details = List.of(new ApiErrorDetail(
                toSnakeCase(ex.getName()), "参数类型不合法",
                rawValue == null ? null : String.valueOf(rawValue)));
        log.warn("查询参数类型不合法 param={} value={}", ex.getName(), rawValue);
        return badRequest(details, "请求参数校验失败");
    }

    /** 请求体不可解析（JSON 语法错误等）：400 + COM-0001。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException ex) {
        log.warn("请求体不可解析: {}", ex.getMessage());
        return badRequest(List.of(ApiErrorDetail.of("body", "请求体格式错误，无法解析")), "请求参数校验失败");
    }

    /** 无匹配路由 / 静态资源不存在：404 + COM-0003（契约 §1.3）。 */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiResponse<Void>> handleNotFound(Exception ex) {
        log.warn("资源不存在: {}", ex.getMessage());
        return ResponseEntity.status(ErrorCodeHttpStatus.resolve(ErrorCodes.COM_0003))
                .body(ApiResponse.fail(ErrorCodes.COM_0003, "您访问的资源不存在或已被移除", null));
    }

    /** HTTP 方法不支持：405 + COM-0004（ERROR_CODES §2）。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.warn("HTTP 方法不支持: {}", ex.getMessage());
        return ResponseEntity.status(ErrorCodeHttpStatus.resolve("COM-0004"))
                .body(ApiResponse.fail("COM-0004", "请求方法不被支持", null));
    }

    /** Content-Type 不支持：415 + COM-0006（ERROR_CODES §2）。 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaType(HttpMediaTypeNotSupportedException ex) {
        log.warn("Content-Type 不支持: {}", ex.getMessage());
        return ResponseEntity.status(ErrorCodeHttpStatus.resolve("COM-0006"))
                .body(ApiResponse.fail("COM-0006", "请求的 Content-Type 不被支持", null));
    }

    /** 上传体积超限：413 + COM-0008（ERROR_CODES §2）。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUpload(MaxUploadSizeExceededException ex) {
        log.warn("上传体积超限: {}", ex.getMessage());
        return ResponseEntity.status(ErrorCodeHttpStatus.resolve("COM-0008"))
                .body(ApiResponse.fail("COM-0008", "上传内容超过大小限制", null));
    }

    /**
     * 未捕获异常兜底：500 + {@code COM-0002}。
     *
     * <p>{@code ERROR_CODES.md §2 使用规范}：未分类异常统一映射 {@code COM-0002}，
     * 且响应体<b>不得</b>含堆栈/SQL 等技术细节；堆栈只进服务端日志（带 {@code request_id}）。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        log.error("未捕获异常", ex);
        return ResponseEntity.status(ErrorCodeHttpStatus.resolve(ErrorCodes.COM_0002))
                .body(ApiResponse.fail(ErrorCodes.COM_0002, "系统繁忙，请稍后重试；若持续出现请联系管理员", null));
    }

    private ResponseEntity<ApiResponse<Void>> badRequest(List<ApiErrorDetail> details, String message) {
        return ResponseEntity.status(ErrorCodeHttpStatus.resolve(ErrorCodes.COM_0001))
                .body(ApiResponse.fail(ErrorCodes.COM_0001, message, details));
    }

    private static String safeMessage(String message) {
        return message == null || message.isBlank() ? "参数不合法" : message;
    }

    private static String lastNode(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        int idx = path.lastIndexOf('.');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    /**
     * 契约 §2.3：{@code details[].field} 用 <b>snake_case</b> 字段路径（如 {@code sku_code}），
     * 而 Java 侧为 camelCase，此处做一次确定性转换（{@code skuCode → sku_code}）。
     */
    static String toSnakeCase(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        StringBuilder sb = new StringBuilder(name.length() + 8);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0 && sb.charAt(sb.length() - 1) != '_') {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** MySQL 唯一键名 → 冲突字段（snake_case）；仅列已知主数据唯一键，其余返回 {@code null}。 */
    private static final Map<String, String> UNIQUE_KEY_TO_FIELD = Map.of(
            "uk_wh_team_code", "code",
            "uk_sup_team_code", "code",
            "uk_prod_team_code", "product_code",
            "uk_sku_team_code", "sku_code");

    /** 提取 MySQL「Duplicate entry '...' for key 'uk_...'」中的键名。 */
    private static final Pattern DUP_KEY = Pattern.compile("for key '([^']+)'");

    /**
     * 从重复键异常消息解析出冲突字段；已知键才给出字段名（未知键返回 {@code null}，此时 details 省略）。
     *
     * <p>MySQL 消息形如：{@code Duplicate entry 'P-BAG-001' for key 'products.uk_prod_team_code'}，
     * 键名可能带表名前缀，故只取最后一段（{@code uk_prod_team_code}）。
     */
    private static String resolveDuplicateField(String message) {
        if (message == null) {
            return null;
        }
        Matcher m = DUP_KEY.matcher(message);
        if (!m.find()) {
            return null;
        }
        String key = m.group(1);
        int dot = key.lastIndexOf('.');
        if (dot >= 0) {
            key = key.substring(dot + 1);
        }
        return UNIQUE_KEY_TO_FIELD.get(key);
    }
}
