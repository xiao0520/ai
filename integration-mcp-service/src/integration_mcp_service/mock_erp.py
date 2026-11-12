"""本地模拟 ERP HTTP API；不得作为真实 ERP 服务部署。"""

from __future__ import annotations

import json
import os
from hmac import compare_digest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class MockErpHandler(BaseHTTPRequestHandler):
    """只响应固定的演示订单，额外敏感字段用于验证输出裁剪。"""

    def do_GET(self) -> None:
        """校验 Bearer 后返回模拟订单。"""
        token = os.getenv("ERP_DEMO_TOKEN", "")
        header = self.headers.get("Authorization", "")
        if not token or not compare_digest(header, f"Bearer {token}"):
            self.send_error(401)
            return
        if self.path != "/api/orders/DEMO-1001":
            self.send_error(404)
            return
        body = json.dumps(
            {
                "orderNo": "DEMO-1001",
                "status": "SHIPPED",
                "customerPhone": "13800000000",
            }
        ).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def main() -> None:
    """在 127.0.0.1:8766 启动本地模拟接口。"""
    if not os.getenv("ERP_DEMO_TOKEN", "").strip():
        raise ValueError("ERP_DEMO_TOKEN 必填")
    server = ThreadingHTTPServer(("127.0.0.1", 8766), MockErpHandler)
    server.serve_forever()


if __name__ == "__main__":
    main()
