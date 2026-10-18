"""B13 独立复核探针（项目负责人自建，不采信开发自报）。

只探测「SSOT 明文要求、但开发 C1/C2 与真栈 P1~P9 未覆盖」的边界。
不修改任何代码，纯 HTTP 行为观测。
"""
from __future__ import annotations

import sys

sys.path.insert(0, "src")

from fastapi.testclient import TestClient  # noqa: E402

from shoppilot_ai.app.core import config  # noqa: E402
from shoppilot_ai.app.core.config import Settings  # noqa: E402
from shoppilot_ai.app.main import app  # noqa: E402

TOKEN = "rb-b13-review-token"
TK = "X-Internal-Token"
RID = "X-Request-Id"
ENVELOPE = {"code", "message", "detail", "request_id"}

results: list[tuple[str, bool, str]] = []


def chk(name: str, ok: bool, actual: str) -> None:
    results.append((name, ok, actual))
    print(f"{'PASS' if ok else 'FAIL'} {name} => {actual}")


client = TestClient(app, raise_server_exceptions=False)
config.settings = Settings(ai_internal_token=TOKEN)

print("=" * 78)
print("PR1  §5「内部统一错误响应体」是否覆盖 404（契约外路径）")
print("=" * 78)
r = client.post("/internal/v1/analysis:validate", headers={TK: TOKEN, RID: "req_pr1"})
body = r.json()
print(f"  raw: http={r.status_code} body={body}")
chk("PR1-404-状态码", r.status_code == 404, str(r.status_code))
chk("PR1-404-四字段信封", set(body.keys()) == ENVELOPE, str(sorted(body.keys())))
chk("PR1-404-响应头X-Request-Id", r.headers.get(RID) == "req_pr1", str(r.headers.get(RID)))

print("=" * 78)
print("PR2  §5 是否覆盖 405（方法不允许）")
print("=" * 78)
r = client.post("/internal/v1/health", headers={RID: "req_pr2"})
body = r.json()
print(f"  raw: http={r.status_code} body={body}")
chk("PR2-405-状态码", r.status_code == 405, str(r.status_code))
chk("PR2-405-四字段信封", set(body.keys()) == ENVELOPE, str(sorted(body.keys())))

print("=" * 78)
print("PR3  health 豁免是精确匹配：尾斜杠变体是否被误拒")
print("=" * 78)
r = client.get("/internal/v1/health/", headers={RID: "req_pr3"})
print(f"  raw: http={r.status_code} body={r.text[:120]} follow_redirects={r.history}")
chk("PR3-health尾斜杠非401", r.status_code != 401, str(r.status_code))

print("=" * 78)
print("PR4  HTTP 头名大小写不敏感（契约未写，但 RFC 7230 强制）")
print("=" * 78)
r = client.post("/internal/v1/analysis:validate", headers={"x-internal-token": TOKEN})
print(f"  raw: http={r.status_code} body={r.text[:120]}")
chk("PR4-小写头名可鉴权通过", r.status_code != 401, str(r.status_code))

print("=" * 78)
print("PR5  令牌严格比较：前后空格不得被 trim 放宽")
print("=" * 78)
r = client.post("/internal/v1/analysis:validate", headers={TK: f" {TOKEN}"})
print(f"  raw: http={r.status_code} body={r.text[:120]}")
chk("PR5-带前导空格拒绝", r.status_code == 401, str(r.status_code))
r = client.post("/internal/v1/analysis:validate", headers={TK: TOKEN + " "})
chk("PR5-带尾随空格拒绝", r.status_code == 401, str(r.status_code))

print("=" * 78)
print("PR6  /internal/v1 前缀本身与更深路径都被覆盖")
print("=" * 78)
for path in ("/internal/v1", "/internal/v1/", "/internal/v1/analysis/runs/1",
             "/internal/v1/analysis/runs/1/result"):
    r = client.get(path)
    chk(f"PR6{path}-401", r.status_code == 401, str(r.status_code))

print("=" * 78)
print("PR7  非 /internal/v1 路径不被误拦（middleware 作用域）")
print("=" * 78)
for path in ("/openapi.json", "/docs", "/"):
    r = client.get(path, follow_redirects=False)
    print(f"  raw: {path} http={r.status_code}")
    chk(f"PR7{path}-非401", r.status_code != 401, str(r.status_code))

print("=" * 78)
print("PR8  401 的 detail 必须是 object（§5 表：detail 类型 object）")
print("=" * 78)
r = client.post("/internal/v1/analysis:validate")
d = r.json().get("detail")
chk("PR8-401detail是dict", isinstance(d, dict), f"{type(d).__name__} {d!r}")

print("=" * 78)
print("PR9  message 必须是英文技术描述（§5 表）")
print("=" * 78)
m = r.json().get("message", "")
chk("PR9-message为ASCII英文", all(ord(c) < 128 for c in m), repr(m))

print("=" * 78)
fail = sum(1 for _, ok, _ in results if not ok)
print(f"RB13_REVIEW_TOTAL={len(results)}  FAIL={fail}")
print("RB13_REVIEW_RESULT=" + ("ALL PASS" if fail == 0 else "HAS FAIL"))
