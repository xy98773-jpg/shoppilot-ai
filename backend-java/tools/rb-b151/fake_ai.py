#!/usr/bin/env python
"""B15.1 真栈用的「契约级假对端」（R107：真 service-ai 仍零端点，202 路径只能这样验）。

只实现 AI_SERVICE_CONTRACT §3.2 的 POST /internal/v1/analysis/runs：
- 默认 202 + 统一信封，回显请求里的 analysis_run_id（Java 侧会校验一致性）；
- 请求体 model_name == "trigger-ai-6002" 时返回 409 + AI-6002（用于验错误映射与失败补偿）。

同时兼容 Content-Length 与 Transfer-Encoding: chunked —— Spring 的
SimpleClientHttpRequestFactory 在请求体较小时用 Content-Length，但复核曾踩过 chunked 导致
「请求体为空 → 误判 ANL-7004」的坑，这里两种都解。
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_POST(self):
        body = self._read_body()
        try:
            payload = json.loads(body or b"{}")
        except json.JSONDecodeError:
            payload = {}
        request_id = self.headers.get("X-Request-Id", "req_fake")

        if self.path != "/internal/v1/analysis/runs":
            self._send(404, {"code": "AI-1003", "message": "Internal path not found",
                             "detail": {"path": self.path}, "request_id": request_id})
            return

        if payload.get("model_name") == "trigger-ai-6002":
            self._send(409, {"code": "AI-6002", "message": "task status not allowed",
                             "detail": None, "request_id": request_id})
            return

        self._send(202, {
            "code": "OK",
            "data": {
                "analysis_run_id": payload.get("analysis_run_id"),
                "execution_status": "ACCEPTED",
                "accepted_at": "2026-10-19T00:00:00.000Z",
            },
            "request_id": request_id,
        })

    def _read_body(self):
        transfer_encoding = (self.headers.get("Transfer-Encoding") or "").lower()
        if "chunked" in transfer_encoding:
            chunks = []
            while True:
                line = self.rfile.readline().strip()
                if not line:
                    continue
                size = int(line.split(b";")[0], 16)
                if size == 0:
                    self.rfile.readline()
                    break
                chunks.append(self.rfile.read(size))
                self.rfile.read(2)
            return b"".join(chunks)
        length = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(length) if length else b""

    def _send(self, status, obj):
        data = json.dumps(obj).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)
        print("FAKE_AI %s %s -> %s" % (self.command, self.path, status), flush=True)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1])
    print("FAKE_AI_LISTEN=%d" % port, flush=True)
    HTTPServer(("127.0.0.1", port), Handler).serve_forever()
