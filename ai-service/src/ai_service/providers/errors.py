"""模型 Provider 层对执行用例暴露的脱敏异常。

职责：
  - 按语义区分六大异常类型 —— 配置/认证/限流/超时/请求/通用。
  - translate_api_status_error() 仅根据 HTTP 状态码翻译，不泄漏上游响应正文。
  - 所有异常继承 RuntimeError，调用方统一 catch 父类。

异常层级：
  ModelConfigurationError    — 模型快照无法匹配适配器或本地凭证。
  ModelProviderError          — 通用脱敏异常（父类）。
    ├── ModelProviderTimeoutError      — 408/504 超时。
    ├── ModelProviderAuthenticationError — 401/403 认证失败。
    ├── ModelProviderRateLimitError     — 429 限流。
    ├── ModelProviderBudgetError        — Java 预占 Token 预算不足。
    └── ModelProviderRequestError       — 400/404/409/422 请求错误。
"""

from openai import APIStatusError

# ============================================================================
# Provider 异常层级
# ============================================================================


class ModelConfigurationError(RuntimeError):
    """Java 模型快照无法匹配适配器或本地凭证时抛出的配置异常。"""


class ModelProviderError(RuntimeError):
    """上游模型调用失败时的脱敏异常，不向 Java 透传供应商详情。"""


class ModelProviderTimeoutError(ModelProviderError):
    """上游模型调用超过本次执行超时时抛出的异常。"""


class ModelProviderAuthenticationError(ModelProviderError):
    """上游模型凭证无效或无权调用时抛出的异常。"""


class ModelProviderRateLimitError(ModelProviderError):
    """上游模型触发限流或额度限制时抛出的异常。"""


class ModelProviderBudgetError(ModelProviderError):
    """本次 Java 预占的模型总 Token 预算不足。"""


class ModelProviderRequestError(ModelProviderError):
    """上游拒绝模型名、API 模式或请求参数时抛出的异常。"""


def translate_api_status_error(error: APIStatusError) -> ModelProviderError:
    """仅根据 HTTP 状态翻译稳定错误，不传递上游响应正文。"""
    if error.status_code in {401, 403}:
        return ModelProviderAuthenticationError("The model provider rejected authentication")
    if error.status_code == 429:
        return ModelProviderRateLimitError("The model provider rate limit was reached")
    if error.status_code in {408, 504}:
        return ModelProviderTimeoutError("The model provider timed out")
    if error.status_code in {400, 404, 409, 422}:
        return ModelProviderRequestError("The model provider rejected the request")
    return ModelProviderError("The model provider request failed")
