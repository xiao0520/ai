"""读取适配服务配置，避免将端点与密钥放进工具定义。"""

from __future__ import annotations

import os
from dataclasses import dataclass
from urllib.parse import urlsplit


@dataclass(frozen=True, slots=True)
class Settings:
    """服务监听、入站认证和本地 ERP 示例接口配置。"""

    mcp_token: str
    host: str = "127.0.0.1"
    port: int = 8765
    enable_demo_erp: bool = False
    demo_erp_base_url: str = ""
    demo_erp_token: str = ""
    upstream_timeout_seconds: float = 5.0

    def __post_init__(self) -> None:
        """拒绝缺密钥和无效的固定下游地址。"""
        if not self.mcp_token.strip():
            raise ValueError("INTEGRATION_MCP_TOKEN 必填")
        if not 1 <= self.port <= 65535:
            raise ValueError("INTEGRATION_MCP_PORT 必须为有效端口")
        if self.upstream_timeout_seconds <= 0:
            raise ValueError("上游超时必须大于零")
        if not self.enable_demo_erp:
            return
        parsed = urlsplit(self.demo_erp_base_url)
        if (
            parsed.scheme not in {"http", "https"}
            or not parsed.hostname
            or parsed.path not in {"", "/"}
            or parsed.query
            or parsed.fragment
            or parsed.username is not None
            or parsed.password is not None
        ):
            raise ValueError("ERP_DEMO_BASE_URL 必须为无路径和凭证的 HTTP(S) 地址")
        if not self.demo_erp_token.strip():
            raise ValueError("ERP_DEMO_TOKEN 必填")

    @classmethod
    def from_env(cls) -> Settings:
        """从部署环境读取配置；默认不注册任何演示工具。"""
        return cls(
            mcp_token=os.getenv("INTEGRATION_MCP_TOKEN", ""),
            host=os.getenv("INTEGRATION_MCP_HOST", "127.0.0.1"),
            port=int(os.getenv("INTEGRATION_MCP_PORT", "8765")),
            enable_demo_erp=os.getenv("INTEGRATION_MCP_ENABLE_DEMO_ERP", "").lower()
            in {"1", "true", "yes"},
            demo_erp_base_url=os.getenv("ERP_DEMO_BASE_URL", ""),
            demo_erp_token=os.getenv("ERP_DEMO_TOKEN", ""),
            upstream_timeout_seconds=float(os.getenv("INTEGRATION_UPSTREAM_TIMEOUT", "5")),
        )
