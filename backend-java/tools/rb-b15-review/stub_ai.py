#!/usr/bin/env python
"""项目负责人独立复核用的「契约级假对端」（B15 复核，非产品代码）。

★ 用途与边界
  service-ai 的 `app/api/analysis.py` 在本批仍是骨架（R107，零端点），
  真实对端无法返回 202。为了独立验证 Java 侧 createRun 的**成功路径**
  （幂等四态 / ANL-7001 / run_no / 审计），本脚本按
  `AI_SERVICE_CONTRACT.md §3.2` 的最小合法响应构造一个假对端。
  它只证明「Java 在契约对端下的行为」，**不证明 Python 实现**（那是 B19/B20）。

★ 模式（由 M 模式文件逐请求读取，便于同一进程内切换）
  ok        202 ACCEPTED，analysis_run_id 原样回显
  ai6002    409 {"code":"AI-6002"}
  unknown   500 {"code":"AI-9999"}
  nonjson   200 非 JSON 文本
  route404  404 {"code":"AI-1003"}（模拟内部路径不存在）
  http500   500 非契约体（空 JSON）

★ 记录
  每个请求追加一行 JSON 到日志文件：path / mode / token / X-Request-Id / body。
"""
import json
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MODE_FILE = sys.argv[1]
LOG_FILE = sys.argv[2]
PORT = int(sys.argv[3]) if len(sys.argv) > 3 else 18361


def current_mode():
    try:
        with open(MODE_FILE, encoding="utf-8") as fh:
            return fh.read().strip() or "ok"
    except OSError:
        return "ok"


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):  # 静默，避免污染 stdout
        pass

    def _send(self, status, payload, ctype="application/json"):
        data = payload.encode("utf-8") if isinstance(payload, str) else json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path.rstrip("/") == "/internal/v1/health":
            self._send(200, {"status": "ok"})
        else:
            self._send(404, {"code": "AI-1003", "message": "Not Found"})

    def _read_body(self):
        """读请求体：★ JDK SimpleClientHttpRequestFactory 在未设固定长度时会用 chunked。"""
        if "chunked" in (self.headers.get("Transfer-Encoding") or "").lower():
            chunks = []
            while True:
                line = self.rfile.readline().strip()
                if not line:
                    break
                size = int(line.split(b";")[0], 16)
                if size == 0:
                    self.rfile.readline()
                    break
                chunks.append(self.rfile.read(size))
                self.rfile.readline()
            return b"".join(chunks).decode("utf-8", "replace")
        length = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(length).decode("utf-8", "replace") if length else ""

    def do_POST(self):
        raw = self._read_body()
        mode = current_mode()
        rid = self.headers.get("X-Request-Id") or "req_stub_missing"
        try:
            body = json.loads(raw) if raw else {}
        except ValueError:
            body = {}
        with open(LOG_FILE, "a", encoding="utf-8") as fh:
            fh.write(json.dumps({
                "path": self.path,
                "mode": mode,
                "token": self.headers.get("X-Internal-Token"),
                "requestId": self.headers.get("X-Request-Id"),
                "body": body,
            }, ensure_ascii=False) + "\n")

        if self.path == "/internal/v1/analysis:validate":
            self._send(200, {
                "code": "OK", "message": "success",
                "data": {"analyzable": True, "data_quality": {}},
                "requestId": rid,
            })
            return

        if self.path != "/internal/v1/analysis/runs":
            self._send(404, {"code": "AI-1003", "message": "Not Found", "requestId": rid})
            return

        if mode == "ok":
            self._send(202, {
                "code": "OK", "message": "accepted",
                "data": {
                    "analysis_run_id": body.get("analysis_run_id"),
                    "execution_status": "ACCEPTED",
                    "accepted_at": "2026-10-19T07:00:00.000Z",
                },
                "requestId": rid,
            })
        elif mode == "ai6002":
            self._send(409, {"code": "AI-6002", "message": "task state not allowed", "requestId": rid})
        elif mode == "unknown":
            self._send(500, {"code": "AI-9999", "message": "boom", "requestId": rid})
        elif mode == "nonjson":
            self._send(200, "this-is-not-json", "text/plain")
        elif mode == "route404":
            self._send(404, {"code": "AI-1003", "message": "Not Found", "requestId": rid})
        else:
            self._send(500, {"code": "AI-9001", "message": "unexpected", "requestId": rid})


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
