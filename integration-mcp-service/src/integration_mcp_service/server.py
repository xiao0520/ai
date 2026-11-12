"""统一注册经过审核的 HTTP 业务工具。"""

from __future__ import annotations

import httpx
from fastmcp import FastMCP

from integration_mcp_service.auth import StaticBearerVerifier
from integration_mcp_service.demo_erp import register_demo_erp_tool
from integration_mcp_service.settings import Settings


def create_server(
    settings: Settings,
    demo_erp_transport: httpx.AsyncBaseTransport | None = None,
) -> FastMCP:
    """创建一个可容纳多个系统工具的 MCP Server。"""
    server = FastMCP(
        "Company HTTP Adapter",
        auth=StaticBearerVerifier(settings.mcp_token),
        strict_input_validation=True,
        mask_error_details=True,
    )
    if settings.enable_demo_erp:
        register_demo_erp_tool(server, settings, demo_erp_transport)
    return server


def main() -> None:
    """启动 Streamable HTTP 服务，默认仅监听本机。"""
    settings = Settings.from_env()
    server = create_server(settings)
    server.run(transport="http", host=settings.host, port=settings.port)
