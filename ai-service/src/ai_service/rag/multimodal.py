"""文档图片的 OCR/视觉理解阶段。

职责：
  - MultimodalDocumentProcessor — 按文档快照选择视觉模型，执行图片→文字转换。
  - TEXT 模式保持原文不调用视觉模型；MULTIMODAL 模式按图片内容去重执行视觉理解。
  - 将视觉输出（文字+描述）转为 ParsedBlock，附带到原文档块列表中。
  - _context / _usage — 为视觉模型提供文档上下文并上报用量。

关键约束：
  - 仅 processing_mode == "MULTIMODAL" 且 vision_profile 存在时调用视觉模型。
  - 输出 input_id 顺序必须与输入一致，否则拒绝为 INDEX_MISMATCH。
  - 视觉模型用量计入 ExecutionUsageItem（stage="VISION"），供预算核验和 Java 结算。
"""

from __future__ import annotations

from dataclasses import replace

from ai_service.contracts.knowledge_kafka import KnowledgeJobCommand
from ai_service.contracts.rag_types import ExecutionUsageItem
from ai_service.providers.errors import ModelConfigurationError
from ai_service.providers.vision_protocol import (
    VisionInput,
    VisionProviderFactory,
)
from ai_service.rag.models import (
    KnowledgeProcessingError,
    ParsedBlock,
    ParsedDocument,
    ParsedImage,
)

# ============================================================================
# 多模态文档处理器
# ============================================================================


class MultimodalDocumentProcessor:
    """按文档快照选择视觉模型，把图片转成可检索且可定位的结构块。"""

    def __init__(self, providers: VisionProviderFactory) -> None:
        self.providers = providers

    async def process(
        self, command: KnowledgeJobCommand, document: ParsedDocument
    ) -> tuple[ParsedDocument, tuple[ExecutionUsageItem, ...]]:
        """TEXT 模式保持原文；MULTIMODAL 模式按内容去重识别图片。"""
        processing = command.document_processing_snapshot
        if processing is None:
            raise KnowledgeProcessingError("INDEX_MISMATCH")
        if processing.processing_mode == "TEXT":
            if document.images:
                raise KnowledgeProcessingError("INDEX_MISMATCH")
            return document, ()
        profile = processing.vision_profile
        if profile is None:
            raise ModelConfigurationError("The vision profile is not configured")
        if not document.images:
            return document, ()

        provider = self.providers.provider_for(profile)
        unique_images_by_hash: dict[str, ParsedImage] = {}
        for image in document.images:
            unique_images_by_hash.setdefault(image.content_hash, image)
        unique_images = tuple(unique_images_by_hash.values())
        batch = await provider.analyze(
            profile,
            tuple(
                VisionInput(
                    input_id=image.media_id,
                    path=image.path,
                    media_type=image.media_type,
                    context=self._context(document.title, image.alt_text, image.title_path),
                )
                for image in unique_images
            ),
        )
        if [item.input_id for item in batch.outputs] != [
            image.media_id for image in unique_images
        ]:
            raise KnowledgeProcessingError("INDEX_MISMATCH")
        text_by_hash = {
            image.content_hash: output.text
            for image, output in zip(unique_images, batch.outputs, strict=True)
        }
        blocks = list(document.blocks)
        for image in document.images:
            blocks.append(
                ParsedBlock(
                    ordinal=len(blocks),
                    kind="IMAGE",
                    text=text_by_hash[image.content_hash],
                    title_path=image.title_path,
                    locator=image.locator,
                    context_text=image.alt_text,
                    modality="IMAGE",
                    media_id=image.media_id,
                    media_hash=image.content_hash,
                )
            )
        usage = self._usage(command, batch.input_tokens, batch.output_tokens)
        return (
            replace(document, blocks=tuple(blocks)),
            (usage,),
        )

    @staticmethod
    def _context(title: str, alt_text: str | None, title_path: tuple[str, ...]) -> str:
        """拼接视觉模型上下文：文档标题 + 章节路径 + 图片替代文字。

        帮助视觉模型将图片置于文档结构中理解，而非孤立识别。"""
        parts = [f"文档：{title}"]
        if title_path:
            parts.append("章节：" + " > ".join(title_path))
        if alt_text:
            parts.append("图片替代文字：" + alt_text)
        return "\n".join(parts)

    @staticmethod
    def _usage(
        command: KnowledgeJobCommand, input_tokens: int, output_tokens: int
    ) -> ExecutionUsageItem:
        """为视觉调用生成用量项；供应商未上报时回退为 UNKNOWN/NOT_REPORTED。

        用量记录绑定到当前 attempt 和 vision_profile，Java 端按此进行额度扣减。"""
        processing = command.document_processing_snapshot
        if processing is None or processing.vision_profile is None:
            raise KnowledgeProcessingError("INDEX_MISMATCH")
        profile = processing.vision_profile.profile
        measurement: dict[str, object]
        if input_tokens == 0 and output_tokens == 0:
            measurement = {"unit": "UNKNOWN", "reason": "NOT_REPORTED"}
        else:
            measurement = {
                "unit": "TOKENS",
                "quality": "ACTUAL",
                "inputTokens": input_tokens,
                "outputTokens": output_tokens,
            }
        return ExecutionUsageItem.model_validate(
            {
                "stage": "VISION",
                "callId": f"vision:{command.attempt_id}:{profile.id}:{profile.revision}",
                "attemptId": command.attempt_id,
                "profile": profile.model_dump(by_alias=True, mode="json"),
                "measurement": measurement,
            }
        )