package com.shoppilot.identity.application.dto;

/**
 * 刷新令牌请求体（{@code POST /api/v1/auth/refresh}）。
 *
 * <p>{@code refreshToken} 可空：未在请求体提供时，服务端回退读取 {@code Set-Cookie} 中的
 * {@code refresh_token}（浏览器场景）。二者皆无 → {@code AUTH-1006}（401）。
 *
 * @param refreshToken {@code rt_} 前缀的不透明令牌
 */
public record RefreshRequest(String refreshToken) {
}
