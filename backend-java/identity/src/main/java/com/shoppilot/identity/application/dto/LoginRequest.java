package com.shoppilot.identity.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录请求体（{@code API_CONTRACT §11.1}）。
 *
 * @param username 登录名或邮箱（必填）
 * @param password 明文口令（必填；HTTPS 传输，服务端 BCrypt 校验）
 */
public record LoginRequest(
        @NotBlank(message = "不能为空")
        @Size(max = 128, message = "长度不能超过 128")
        String username,

        @NotBlank(message = "不能为空")
        @Size(max = 128, message = "长度不能超过 128")
        String password) {
}
