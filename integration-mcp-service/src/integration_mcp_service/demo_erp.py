"""仅供本地验收的模拟 ERP 订单查询工具，非真实 ERP 契约。"""

from __future__ import annotations

import re
from typing import Annotated

import httpx
from fastmcp import FastMCP
from mcp.types import ToolAnnotations
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from integration_mcp_service.settings import Settings

_ORDER_PATTERN = re.compile(r"DEMO-[0-9]{4,12}\Z")


class DemoOrderResult(BaseModel):
    """只保留允许模型读取的模拟订单字段。"""

    model_config = ConfigDict(extra="ignore", strict=True)

    order_no: str = Field(alias="orderNo", pattern=r"^DEMO-[0-9]{4,12}$")
    status: str = Field(pattern=r"^[A-Z_]{2,32}$")


class DemoErpError(Exception):
    """对模型可见的固定上游失败类别，不包含端点或凭证。"""


def register_demo_erp_tool(
    server: FastMCP,
    settings: Settings,
    transport: httpx.AsyncBaseTransport | None = None,
) -> None:
    """注册固定的只读示例工具；下游地址只能由部署配置指定。"""

    @server.tool(
        name="demo_erp_get_order_status",
        description="仅用于本地模拟：按 DEMO 订单号查询模拟 ERP 的订单状态。",
        annotations=ToolAnnotations(
            read_only_hint=True,
            destructive_hint=False,
            idempotent_hint=True,
            open_world_hint=False,
        ),
    )
    async def demo_erp_get_order_status(
        order_no: Annotated[
            str,
            Field(description="模拟订单号，例如 DEMO-1001", min_length=9, max_length=17),
        ],
    ) -> dict[str, str]:
        """验证订单号并返回裁剪后的模拟状态。"""
        if not _ORDER_PATTERN.fullmatch(order_no):
            raise DemoErpError("无效的模拟订单号")
        headers = {"Authorization": f"Bearer {settings.demo_erp_token}"}
        try:
            # 单次调用创建客户端，连接和响应均受固定超时约束。
            async with httpx.AsyncClient(
                base_url=settings.demo_erp_base_url,
                headers=headers,
                timeout=settings.upstream_timeout_seconds,
                transport=transport,
                follow_redirects=False,
            ) as client:
                async with client.stream("GET", f"/api/orders/{order_no}") as response:
                    if response.status_code == 404:
                        raise DemoErpError("模拟订单不存在")
                    if response.status_code in {401, 403}:
                        raise DemoErpError("模拟 ERP 认证失败")
                    if response.status_code != 200:
                        raise DemoErpError("模拟 ERP 暂不可用")

                    # 流式限制响应体大小，防止异常下游返回占用过多内存。
                    chunks: list[bytes] = []
                    size = 0
                    async for chunk in response.aiter_bytes():
                        size += len(chunk)
                        if size > 16_384:
                            raise DemoErpError("模拟 ERP 暂不可用")
                        chunks.append(chunk)
            result = DemoOrderResult.model_validate_json(b"".join(chunks))
        except DemoErpError:
            raise
        except httpx.TimeoutException:
            raise DemoErpError("模拟 ERP 查询超时") from None
        except (httpx.HTTPError, ValueError, ValidationError):
            raise DemoErpError("模拟 ERP 暂不可用") from None

        # 验证下游返回的订单号，避免错误数据混入模型上下文。
        if result.order_no != order_no:
            raise DemoErpError("模拟 ERP 返回无效数据")
        return {"orderNo": result.order_no, "status": result.status}
