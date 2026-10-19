#!/usr/bin/env python3
"""M4-B20.1 复核用出站抓包反代。

把 Java 发往真实 service-ai 的请求体落盘（JSONL），再转发到上游。
目的：**独立取到 Java 真实发出的 items**，而不是采信开发自报的 V4 证据。

★ 必须同时支持 Content-Length 与 chunked 两种请求体：
  Java 侧 RestClient（JDK HttpURLConnection）发 JSON 时可能不带 Content-Length，
  而是 `Transfer-Encoding: chunked`。首轮只读 Content-Length 导致 body 为空、
  上游返回 400，Java 侧表现为 ANL-7003（项目负责人脚本缺陷，非开发缺陷）。

用法：ai_proxy.py <upstream_port> <listen_port> <capture_file>
"""
import http.client
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

UPSTREAM_PORT = int(sys.argv[1])
LISTEN_PORT = int(sys.argv[2])
CAPTURE = sys.argv[3]


def read_chunked(rfile) -> bytes:
    """读 chunked 请求体（逐块 size + CRLF，最后 0 块结束）。"""
    out = b""
    while True:
        line = rfile.readline().strip()
        if not line:
            break
        try:
            size = int(line.split(b";")[0], 16)
        except ValueError:
            break
        if size == 0:
            # 读掉 trailer
            while True:
                trailer = rfile.readline()
                if trailer in (b"\r\n", b"\n", b""):
                    break
            break
        out += rfile.read(size)
        rfile.readline()  # 吃掉块尾 CRLF
    return out


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _capture(self, body: bytes) -> None:
        if not body:
            payload = {"_empty_body": True}
        else:
            try:
                payload = json.loads(body.decode("utf-8"))
            except Exception:
                payload = {"_unparsable": body.decode("utf-8", "replace")[:2000]}
        with open(CAPTURE, "a", encoding="utf-8") as fh:
            fh.write(json.dumps({
                "path": self.path,
                "method": self.command,
                "body": payload,
            }, ensure_ascii=False) + "\n")

    def _forward(self) -> None:
        te = (self.headers.get("Transfer-Encoding") or "").lower()
        if "chunked" in te:
            body = read_chunked(self.rfile)
        else:
            length = int(self.headers.get("Content-Length") or 0)
            body = self.rfile.read(length) if length else b""
        if self.command == "POST":
            self._capture(body)

        conn = http.client.HTTPConnection("127.0.0.1", UPSTREAM_PORT, timeout=120)
        headers = {k: v for k, v in self.headers.items()
                   if k.lower() not in ("host", "content-length", "connection", "transfer-encoding")}
        headers["Content-Length"] = str(len(body))
        conn.request(self.command, self.path, body=body, headers=headers)
        resp = conn.getresponse()
        data = resp.read()
        self.send_response(resp.status)
        for k, v in resp.getheaders():
            if k.lower() in ("transfer-encoding", "content-length", "connection"):
                continue
            self.send_header(k, v)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)
        conn.close()

    do_GET = _forward
    do_POST = _forward
    do_PUT = _forward
    do_DELETE = _forward

    def log_message(self, fmt, *args):  # 静默
        pass


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", LISTEN_PORT), Handler).serve_forever()
