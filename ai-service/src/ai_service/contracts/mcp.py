"""Java 下发的单次 MCP 工具授权快照。"""

from __future__ import annotations

import json
from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from ai_service.contracts.rag_types import RagModel


class McpToolGrant(RagModel):
    """只描述本次可执行工具；凭证仅以别名传递。"""

    # 服务连接与鉴权均受本次 Java 授权快照约束。
    server_id: Annotated[str, Field(pattern=r"^[1-9][0-9]{0,18}$")]
    server_code: Annotated[str, Field(min_length=1, max_length=100)]
    endpoint: Annotated[str, Field(pattern=r"^https?://[^\s?#]+$", max_length=2048)]
    auth_type: Literal["NONE", "BEARER"]
    credential_key: Annotated[str, Field(pattern=r"^[A-Za-z0-9_.-]{1,100}$")] | None
    connect_timeout_seconds: Annotated[int, Field(ge=1, le=60)]
    # 工具身份、参数结构与调用时限绑定到本次授权，不接受模型自行扩展。
    tool_id: Annotated[str, Field(pattern=r"^[1-9][0-9]{0,18}$")]
    tool_name: Annotated[str, Field(min_length=1, max_length=128)]
    qualified_name: Annotated[str, Field(min_length=1, max_length=255)]
    input_schema: Annotated[str, Field(min_length=2, max_length=100_000)]
    timeout_seconds: Annotated[int, Field(ge=1, le=120)]

    @model_validator(mode="after")
    def validate_grant(self) -> Self:
        """认证配置和工具参数必须在 Java 签发的快照中自洽。"""
        if (self.auth_type == "BEARER") != (self.credential_key is not None):
            raise ValueError("VALIDATION")
        try:
            schema = json.loads(self.input_schema)
        except json.JSONDecodeError as error:
            raise ValueError("VALIDATION") from error
        if not isinstance(schema, dict) or schema.get("type") != "object":
            raise ValueError("VALIDATION")
        return self
