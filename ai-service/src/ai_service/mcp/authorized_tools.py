"""按 Java 单次授权清单连接 MCP，并向 LangChain 暴露受限工具。"""

from __future__ import annotations

import asyncio
import json
from collections import defaultdict
from collections.abc import AsyncIterator
from contextlib import AsyncExitStack, asynccontextmanager
from urllib.parse import urlsplit

import httpx2
from fastmcp import Client
from fastmcp.client.transports import StreamableHttpTransport
from jsonschema import Draft202012Validator, ValidationError
from langchain.mcp import MCPAdapter
from langchain_core.tools import BaseTool, StructuredTool
from mcp.types import TextContent

from ai_service.contracts.mcp import McpToolGrant
from ai_service.infra.settings import Settings

_MAX_TOOLS = 20
_MAX_CALLS = 3
_MAX_ARGUMENT_BYTES = 16_384
_MAX_RESULT_BYTES = 16_384


class McpToolRejected(Exception):
    """工具授权、发现或执行失败；不携带上游异常及密钥。"""

    def __init__(self) -> None:
        super().__init__("TOOL_REJECTED")


class McpCredentialMissing(McpToolRejected):
    """Java 下发的 MCP 凭证别名未在 Python 环境配置。"""


class McpToolTimeout(McpToolRejected):
    """MCP 连接、发现或执行超过本次授权超时。"""


class _CallBudget:
    """一个请求的所有工具共享调用次数上限。"""

    def __init__(self) -> None:
        self._remaining = _MAX_CALLS
        self._lock = asyncio.Lock()

    async def consume(self) -> None:
        """原子扣减本次聊天的工具调用额度，超限时拒绝。"""
        async with self._lock:
            if self._remaining <= 0:
                raise McpToolRejected
            self._remaining -= 1


def _is_timeout(error: BaseException) -> bool:
    """识别 FastMCP/HTTP 客户端包装后的超时，避免误报为权限拒绝。"""
    pending = [error]
    visited: set[int] = set()
    while pending:
        current = pending.pop()
        if id(current) in visited:
            continue
        visited.add(id(current))
        if isinstance(current, (TimeoutError, httpx2.TimeoutException)):
            return True
        if isinstance(current, BaseExceptionGroup):
            pending.extend(current.exceptions)
        if current.__cause__ is not None:
            pending.append(current.__cause__)
    return False


def _server_key(grant: McpToolGrant) -> tuple[str, str, str, str | None, int]:
    """按服务连接参数分组授权工具，共用同一 MCP 会话。"""
    return (
        grant.server_id,
        grant.endpoint,
        grant.auth_type,
        grant.credential_key,
        grant.connect_timeout_seconds,
    )


def _validate_endpoint(endpoint: str) -> None:
    """拒绝含用户信息、查询串或片段的非标准 HTTP 端点。"""
    parsed = urlsplit(endpoint)
    if (
        parsed.scheme not in ("http", "https")
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
    ):
        raise McpToolRejected


def _has_external_reference(value: object) -> bool:
    """递归查找远程 Schema 引用，避免校验时访问未授权地址。"""
    if isinstance(value, dict):
        reference = value.get("$ref")
        if isinstance(reference, str) and not reference.startswith("#/"):
            return True
        return any(_has_external_reference(item) for item in value.values())
    if isinstance(value, list):
        return any(_has_external_reference(item) for item in value)
    return False


def _schema(grant: McpToolGrant) -> dict[str, object]:
    """校验 Java 固定的输入 Schema，只接受本地 JSON 引用。"""
    try:
        schema = json.loads(grant.input_schema)
        Draft202012Validator.check_schema(schema)
    except ValueError, TypeError:
        raise McpToolRejected from None
    if not isinstance(schema, dict) or _has_external_reference(schema):
        raise McpToolRejected
    return schema


def _authorized_tool(
    grant: McpToolGrant,
    adapter: MCPAdapter,
    remote: BaseTool,
    schema: dict[str, object],
    budget: _CallBudget,
    secret: str | None,
) -> BaseTool:
    """包装远端工具，对每次调用重新执行授权参数与输出约束。"""

    async def invoke(**arguments: object) -> str:
        """限制参数、调用次数及响应，屏蔽 SDK 异常与凭证。"""
        try:
            encoded = json.dumps(arguments, ensure_ascii=False).encode("utf-8")
            if len(encoded) > _MAX_ARGUMENT_BYTES:
                raise McpToolRejected
            Draft202012Validator(schema).validate(arguments)
            await budget.consume()
            result = await asyncio.wait_for(
                adapter.client.call_tool(grant.tool_name, arguments, timeout=grant.timeout_seconds),
                timeout=grant.timeout_seconds,
            )
            if result.is_error or any(not isinstance(item, TextContent) for item in result.content):
                raise McpToolRejected
            content = "\n".join(
                item.text for item in result.content if isinstance(item, TextContent)
            )
            if not content and result.structured_content is not None:
                content = json.dumps(result.structured_content, ensure_ascii=False)
            if len(content.encode("utf-8")) > _MAX_RESULT_BYTES or (secret and secret in content):
                raise McpToolRejected
            return content
        except TimeoutError:
            raise McpToolTimeout from None
        except ValidationError, TypeError, ValueError:
            raise McpToolRejected from None
        except McpToolRejected:
            raise
        except Exception as error:  # noqa: BLE001
            # SDK 异常可能包含带密钥的 URL 或请求头，只向调用方暴露稳定错误码。
            if _is_timeout(error):
                raise McpToolTimeout from None
            raise McpToolRejected from None

    return StructuredTool.from_function(
        name=f"mcp_{grant.tool_id}",
        description=f"{grant.qualified_name}: {remote.description or grant.tool_name}",
        args_schema=remote.args_schema,
        coroutine=invoke,
    )


@asynccontextmanager
async def authorized_mcp_tools(
    grants: list[McpToolGrant], settings: Settings
) -> AsyncIterator[list[BaseTool]]:
    """仅把本次 Java 授权且与服务端实时 Schema 一致的工具交给模型。

    每次调用都重新校验参数并限制次数、超时和结果大小。调用期保持连接，退出时
    关闭所有 Server 会话；空清单不会建立网络连接。
    """
    if len(grants) > _MAX_TOOLS or len({item.tool_id for item in grants}) != len(grants):
        raise McpToolRejected
    # 同一 Server 的工具共用连接，但不同认证或超时配置不能混用。
    grouped: dict[tuple[str, str, str, str | None, int], list[McpToolGrant]] = defaultdict(list)
    for grant in grants:
        _validate_endpoint(grant.endpoint)
        grouped[_server_key(grant)].append(grant)
    if len({key[0] for key in grouped}) != len(grouped):
        raise McpToolRejected

    budget = _CallBudget()
    allowed: list[BaseTool] = []
    async with AsyncExitStack() as stack:
        for key, server_grants in grouped.items():
            _, endpoint, auth_type, credential_key, connect_timeout = key
            try:
                secret = None
                if auth_type == "BEARER":
                    if credential_key is None:
                        raise McpToolRejected
                    try:
                        secret = settings.credential_for(credential_key).get_secret_value()
                    except ValueError:
                        raise McpCredentialMissing from None
                headers = {"Authorization": f"Bearer {secret}"} if secret else None
                client = Client(
                    StreamableHttpTransport(endpoint, headers=headers),
                    timeout=connect_timeout,
                    init_timeout=connect_timeout,
                )
                adapter = await stack.enter_async_context(MCPAdapter(client))
                raw_tools = {tool.name: tool for tool in await adapter.client.list_tools()}
                adapted_tools = {tool.name: tool for tool in await adapter.list_tools()}
                for grant in server_grants:
                    # 实时发现结果必须与 Java 下发的 Schema 和只读声明一致。
                    raw = raw_tools.get(grant.tool_name)
                    adapted = adapted_tools.get(grant.tool_name)
                    schema = _schema(grant)
                    if (
                        raw is None
                        or adapted is None
                        or raw.input_schema != schema
                        or raw.annotations is None
                        or raw.annotations.read_only_hint is not True
                        or raw.annotations.destructive_hint is True
                    ):
                        raise McpToolRejected
                    allowed.append(
                        _authorized_tool(grant, adapter, adapted, schema, budget, secret)
                    )
            except McpToolRejected:
                raise
            except TimeoutError:
                raise McpToolTimeout from None
            except Exception as error:  # noqa: BLE001
                # 连接或发现异常可能包含认证头，不保留上游异常链。
                if _is_timeout(error):
                    raise McpToolTimeout from None
                raise McpToolRejected from None
        yield allowed
