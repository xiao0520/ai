"""按 Java 下发的厂商编码选择聊天 Provider 策略。

职责：
  - ChatProviderFactory — 从生命周期级策略注册表解析厂商 Provider。
  - RoutedChatProvider — 每次请求按 provider_code + api_mode 动态路由到具体适配器。
  - 厂商编码归一化 — 大小写不敏感、去除空白，拒绝空字符串。

路由规则：
  - OpenAI 兼容厂商（doubao / qianwen / kimi / moonshot / zhipu / deepseek）统一使用 CHAT_COMPLETIONS。
  - 方舟额外支持 RESPONSES 模式（仅已完成兼容验证的厂商注册 Responses 适配器）。
  - 未注册的组合不允许调用，直接抛出 ModelConfigurationError。
"""

from __future__ import annotations

from collections.abc import AsyncIterator, Mapping
from types import MappingProxyType

from ai_service.contracts.chat import InternalChatStreamRequest
from ai_service.providers.errors import ModelConfigurationError
from ai_service.providers.protocol import ChatStreamProvider, ProviderStreamChunk

OPENAI_COMPATIBLE_PROVIDER_CODES = (
    "doubao",
    "qianwen",
    "kimi",
    "moonshot",
    "zhipu",
    "deepseek",
)
API_MODE_CHAT_COMPLETIONS = "CHAT_COMPLETIONS"
API_MODE_RESPONSES = "RESPONSES"


class ChatProviderFactory:
    """从生命周期级策略注册表解析厂商 Provider，不重复创建网络客户端。"""

    def __init__(self, providers: Mapping[tuple[str, str], ChatStreamProvider]) -> None:
        normalized_providers: dict[tuple[str, str], ChatStreamProvider] = {}
        for (provider_code, api_mode), provider in providers.items():
            normalized_key = self._normalized_key(provider_code, api_mode)
            if normalized_key in normalized_providers:
                raise ValueError("Duplicate model provider and API mode")
            normalized_providers[normalized_key] = provider
        if not normalized_providers:
            raise ValueError("At least one model provider is required")
        self._providers: Mapping[tuple[str, str], ChatStreamProvider] = MappingProxyType(
            normalized_providers
        )

    def create(self, provider_code: str, api_mode: str) -> ChatStreamProvider:
        """按厂商与 API 模式返回已初始化策略，未注册的组合不允许调用。"""
        provider = self._providers.get(self._normalized_key(provider_code, api_mode))
        if provider is None:
            raise ModelConfigurationError("The model provider is not supported")
        return provider

    @staticmethod
    def _normalized_code(provider_code: str) -> str:
        """将 Java 下发的 provider_code 归一化为小写去空白，拒绝空字符串。

        归一化确保 "Doubao"、" doubao "、"DOUBAO" 等变体都能命中同一个适配器。"""
        normalized_code = provider_code.strip().lower()
        if not normalized_code:
            raise ModelConfigurationError("The model provider code is empty")
        return normalized_code

    @classmethod
    def _normalized_key(cls, provider_code: str, api_mode: str) -> tuple[str, str]:
        """将 (provider_code, api_mode) 组合归一化为稳定的注册表查找键。

        api_mode 统一转大写以确保 "chat_completions" 和 "CHAT_COMPLETIONS" 等价。"""
        normalized_mode = api_mode.strip().upper()
        if not normalized_mode:
            raise ModelConfigurationError("The model API mode is empty")
        return cls._normalized_code(provider_code), normalized_mode


# ============================================================================
# 路由聊天 Provider
# ============================================================================


class RoutedChatProvider:
    """将每次聊天请求路由至厂商策略，同时维持统一的领域 Provider 协议。"""

    def __init__(self, factory: ChatProviderFactory) -> None:
        self._factory = factory

    def stream(self, request: InternalChatStreamRequest) -> AsyncIterator[ProviderStreamChunk]:
        """依据发布快照中的 providerCode 和 apiMode 执行对应模型流。"""
        provider = self._factory.create(
            request.model_profile.provider_code, request.model_profile.api_mode
        )
        return provider.stream(request)

    async def close(self) -> None:
        """请求级路由没有独占资源，共享客户端由 FastAPI lifespan 关闭。"""