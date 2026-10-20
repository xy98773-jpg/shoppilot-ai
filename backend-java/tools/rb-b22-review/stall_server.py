#!/usr/bin/env python3
"""M4-B22 复核用 stall 服务：读掉请求但**不回包**，用于构造真实读超时（ANL-7003）。

为什么不能只 accept 不读：500 SKU 的请求体较大，对端不读会让客户端**写阻塞**
（TCP 接收窗口满），而 SimpleClientHttpRequestFactory 的 readTimeout 只覆盖读、
不覆盖写 ⇒ 请求整体挂死（HTTP 000），构造不出 504。

故：排空请求数据到「连续 3 秒无新数据」或 8MB 上限，然后保持连接不响应。
用法：stall_server.py <port>
"""
import socket
import sys

port = int(sys.argv[1])
srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(("127.0.0.1", port))
srv.listen(64)
held = []

while True:
    conn, _ = srv.accept()
    conn.settimeout(1.0)
    total = 0
    idle = 0
    while total < 8_000_000 and idle < 3:
        try:
            chunk = conn.recv(65536)
        except OSError:
            idle += 1
            continue
        if not chunk:
            break
        total += len(chunk)
        idle = 0
    held.append(conn)  # 不关闭、不响应
