# -*- coding: utf-8 -*-
"""R149-C2 补充探针：读掉请求但**不回包**的 TCP 服务（构造真实读超时 ANL-7003）。

为什么不能只 accept：
  500 SKU 的 validate 请求体较大，若对端不读，客户端会**写阻塞**（TCP 接收窗口满），
  而 SimpleClientHttpRequestFactory 的 readTimeout 只覆盖读、不覆盖写 ⇒ 请求整体挂死。
  故此处必须读完请求头 + Content-Length 指定的 body，再保持连接不响应。

黑洞地址（10.255.255.1）产生的是「connect timed out」，而 AiServiceClient.isReadTimeout
显式把 connect 超时排除在 7003 之外（归 7002）——所以必须用本服务才能走通 7003 分支。
用法：python stall_server.py <port>
"""
import socket
import sys

port = int(sys.argv[1])
server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
server.bind(("127.0.0.1", port))
server.listen(64)
held = []

while True:
    conn, _ = server.accept()
    conn.settimeout(1.0)
    # ★ 不能只按 Content-Length 读：RestClient 对较大 body 可能用 chunked 编码，
    #   只读头会让客户端**写阻塞**（readTimeout 不覆盖写）⇒ 请求整体挂死。
    #   因此持续排空到「连续 3 秒无新数据」或 8MB 上限，然后再也不回包。
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
    held.append(conn)  # 故意不关闭、不响应：让客户端读超时
