"""从 Pydantic 导出合同审查契约及 Java/Python 共用样例。"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from pydantic.json_schema import models_json_schema

from ai_service.contracts.contract_review import (
    ContractReviewCompletedPayload,
    ContractReviewExecutionRequest,
    ContractReviewFailedPayload,
    ContractReviewProgressPayload,
    ContractReviewResult,
    ContractReviewUsagePayload,
)

ROOT = Path(__file__).resolve().parents[2]
DESTINATION = ROOT / "main-service/docs/contracts/contract-review-v1"


def contract_files() -> dict[str, str]:
    """请求和结果从权威模型校验后导出，样例不携带真实凭证或业务数据。"""
    fixtures = json.loads((DESTINATION.parent / "rag-v1/fixtures.json").read_text(encoding="utf-8"))
    chat = next(
        item["payload"]
        for item in fixtures
        if item["valid"] and item["model"] == "RagChatStreamRequest"
    )
    # 合同样例只引用当前查询契约，避免旧的共享样例夹带已移除字段。
    original_scope = chat["rag"]
    scope = {
        key: original_scope[key]
        for key in (
            "contractVersion",
            "mode",
            "scopeSnapshotId",
            "expiresAt",
            "retrievalProfile",
            "indexSnapshots",
            "contextBudgetTokens",
        )
    }
    scope["retrievalSnapshot"] = {
        key: value
        for key, value in original_scope["retrievalSnapshot"].items()
        if key != "queryRewrite"
    }
    scope["knowledgeScopes"] = [
        {
            **item,
            "retrievalSettings": {
                "denseEnabled": True,
                "sparseEnabled": True,
                "recallCount": 30,
                "rrfK": 60,
                "rerankEnabled": False,
                "rerankProfile": None,
                "rerankInputCount": 10,
                "rerankOutputCount": 5,
                "relevanceThreshold": None,
            },
        }
        for item in original_scope["knowledgeScopes"]
    ]
    request = ContractReviewExecutionRequest.model_validate_json(
        json.dumps(
            {
                "schemaVersion": "1.0",
                "requestId": "contract-review-0001",
                "taskId": "2076944338398593026",
                "userId": "9",
                "fileName": "sample.pdf",
                "mediaType": "application/pdf",
                "contractType": "PURCHASE",
                "partyPosition": "PARTY_A",
                "systemPrompt": "从采购方立场审查，重点关注付款、交付验收和违约责任。",
                "modelProfile": chat["modelProfile"],
                "executionPolicy": {**chat["executionPolicy"], "maxTotalTokens": 100000},
                "rag": scope,
            }
        )
    )
    result = ContractReviewResult.model_validate(
        {
            "taskId": request.task_id,
            "information": {"title": "测试采购合同", "partyA": "测试甲方"},
            "findings": [
                {
                    "findingId": "F-001",
                    "category": "BREACH_LIABILITY",
                    "severity": "HIGH",
                    "title": "逾期交付责任约定不足",
                    "description": "应结合履约风险明确违约金与损失赔偿关系。",
                    "quote": "逾期交付支付违约金。",
                    "location": {
                        "pageNumber": 1,
                        "blockIndex": 0,
                        "startOffset": 0,
                        "endOffset": 10,
                    },
                    "suggestion": "补充违约金计算方式及损失赔偿约定。",
                    "basis": "基于条款完整性分析，未引用外部法规。",
                    "evidences": [],
                }
            ],
            "summary": {"high": 1, "medium": 0, "low": 0},
        }
    )
    models = (
        ContractReviewExecutionRequest,
        ContractReviewResult,
        ContractReviewProgressPayload,
        ContractReviewCompletedPayload,
        ContractReviewFailedPayload,
        ContractReviewUsagePayload,
    )
    _, schema = models_json_schema([(model, "validation") for model in models])
    schema["$schema"] = "https://json-schema.org/draft/2020-12/schema"
    payloads = {
        "schema.json": schema,
        "fixtures.json": {
            "request": request.model_dump(by_alias=True, mode="json"),
            "result": result.model_dump(by_alias=True, mode="json"),
            "extractionRequest": request.model_copy(
                update={
                    "operation": "EXTRACT",
                    "rag": None,
                }
            ).model_dump(by_alias=True, mode="json"),
        },
    }
    return {
        name: json.dumps(value, ensure_ascii=False, indent=2) + "\n"
        for name, value in payloads.items()
    }


def main() -> None:
    """默认刷新生成文件，--check 仅检查契约是否发生未同步变化。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    for name, content in contract_files().items():
        target = DESTINATION / name
        if args.check:
            if not target.is_file() or target.read_text(encoding="utf-8") != content:
                raise SystemExit(f"合同契约需要重新导出：{name}")
        else:
            DESTINATION.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8")


if __name__ == "__main__":
    main()
