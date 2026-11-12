"""保护 MCP 入口；与下游业务系统凭证分离。"""

from __future__ import annotations

from hmac import compare_digest

from fastmcp.server.auth import AccessToken, TokenVerifier


class StaticBearerVerifier(TokenVerifier):
    """验证由现有 ai-service 凭证别名解析出的服务令牌。"""

    def __init__(self, expected_token: str) -> None:
        super().__init__()
        self._expected_token = expected_token

    async def verify_token(self, token: str) -> AccessToken | None:
        """以固定时间比较令牌，失败时不返回密钥细节。"""
        if not compare_digest(token, self._expected_token):
            return None
        return AccessToken(token=token, client_id="ai-service", scopes=[])
