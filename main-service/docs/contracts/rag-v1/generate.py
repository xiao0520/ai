"""从 Pydantic 契约生成 JSON Schema、OpenAPI 和 Java DTO，支持 --check 防止漂移。"""

from __future__ import annotations

import argparse
import inspect
import json
import re
import sys
from pathlib import Path

from java_rules import ALIASES, RULES

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / "ai-service/src"))

from pydantic import BaseModel
from pydantic.json_schema import models_json_schema

from ai_service.contracts import (
    knowledge,
    mcp,
    knowledge_kafka,
    rag_artifacts,
    rag_chat,
    rag_types,
    retrieval,
)
from ai_service.contracts.chat import (
    ChatActor,
    ChatInput,
    ChatMessage,
    DeltaPayload,
    ExecutionPolicy,
    KeepalivePayload,
    ModelProfile,
    Usage,
)

OUT = Path(__file__).resolve().parent
JAVA = (
    ROOT
    / "main-service/ruoyi-modules/ruoyi-ai-core/src/main/java/org/ruoyi/domain/dto/knowledge/rag"
)
PACKAGE = "org.ruoyi.domain.dto.knowledge.rag"
SCHEMA_ID = "https://contracts.mjn-ai.invalid/rag-v1/schema.json"
GENERATED = (
    "// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。\n"
)

# 仅供 Python 执行或诊断接口使用的模型仍保留在 Schema/OpenAPI 中，
# 但不生成没有 Java 调用方的平行 DTO。
JAVA_EXCLUDED_TYPES = {
    "ChatInput",
    "DeltaPayload",
    "ExecutionPolicy",
    "KeepalivePayload",
    "ModelProfile",
    "RetrievalTestRequest",
    "RetrievalTestResponse",
    "Usage",
    "VersionedRequest",
}


def encode(value):
    return json.dumps(value, ensure_ascii=False, indent=2) + "\n"


def literal(value):
    return json.dumps(value, ensure_ascii=False)


def capital(name):
    return name[0].upper() + name[1:]


def build_schema():
    models = {
        ChatActor,
        ChatInput,
        ChatMessage,
        ExecutionPolicy,
        ModelProfile,
        DeltaPayload,
        KeepalivePayload,
        Usage,
    }
    for module in (rag_types, knowledge, retrieval, mcp, rag_chat, rag_artifacts, knowledge_kafka):
        models.update(
            value
            for _, value in inspect.getmembers(module, inspect.isclass)
            if issubclass(value, BaseModel)
            and value.__module__ == module.__name__
            and value is not rag_types.RagModel
        )
    _, schema = models_json_schema(
        [(model, "validation") for model in sorted(models, key=lambda m: m.__name__)]
    )
    for name in RULES:
        schema["$defs"][name]["x-invariant-validator"] = (
            "Python model_validator / Java isContractConsistent; see RAG机器契约说明.md"
        )
    return {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "$id": SCHEMA_ID,
        **schema,
    }


def nonnull(prop):
    choices = prop.get("anyOf", [])
    remaining = [item for item in choices if item.get("type") != "null"]
    if len(remaining) == 1 and len(choices) == 2:
        return remaining[0], True
    return prop, False


def java_sources(schema):
    definitions = schema["$defs"]
    interfaces = {}
    implementations = {}
    # 判别接口独立生成；成员 DTO 可以实现多个使用位置的接口。
    for owner, model in definitions.items():
        for field, prop in model.get("properties", {}).items():
            prop, _ = nonnull(prop)
            if "discriminator" in prop:
                name = owner + capital(field)
                disc = prop["discriminator"]
                mapping = {
                    key: ref.rsplit("/", 1)[1] for key, ref in disc["mapping"].items()
                }
                interfaces[name] = (disc["propertyName"], mapping)
                for target in set(mapping.values()):
                    implementations.setdefault(target, []).append(name)

    sources = {}
    for name, (field, mapping) in interfaces.items():
        tags = ",\n".join(
            f"    @JsonSubTypes.Type(value = {target}.class, name = {literal(key)})"
            for key, target in mapping.items()
        )
        sources[name] = f"""{GENERATED}package {PACKAGE};

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** {field} 的封闭判别体，未知类型必须拒绝。 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = {literal(field)}, visible = true)
@JsonSubTypes({{
{tags}
}})
public sealed interface {name} permits {", ".join(sorted(set(mapping.values())))} {{
}}
"""

    for name, model in definitions.items():
        if model.get("type") != "object":
            raise ValueError(f"未支持的顶层结构: {name}")
        enums = {}

        def field_type(prop, field, name=name, enums=enums):
            prop, nullable = nonnull(prop)
            annotations = []
            if not nullable:
                annotations.append("@NotNull")
            if "$ref" in prop:
                return " ".join(
                    annotations + ["@Valid", prop["$ref"].rsplit("/", 1)[1]]
                )
            if "discriminator" in prop:
                return " ".join(annotations + ["@Valid", name + capital(field)])
            if "enum" in prop or (
                "const" in prop and not isinstance(prop["const"], bool)
            ):
                enum_name = capital(field)
                values = prop.get("enum", [prop.get("const")])
                # MIME 等枚举不能直接成为 Java 标识符，始终通过 JsonValue 保持线上的原值。
                enums[enum_name] = values
                return " ".join(annotations + [enum_name])
            kind = prop["type"]
            if kind == "string":
                if "pattern" in prop:
                    annotations.append(f"@Pattern(regexp = {literal(prop['pattern'])})")
                minimum, maximum = prop.get("minLength"), prop.get("maxLength")
                if minimum is not None or maximum is not None:
                    args = ([f"min = {minimum}"] if minimum is not None else []) + (
                        [f"max = {maximum}"] if maximum is not None else []
                    )
                    annotations.append("@CodePointLength(" + ", ".join(args) + ")")
                if prop.get("format") == "uri":
                    annotations.append(
                        "@Pattern(regexp = " + literal(r"^https?://[^\s]+$") + ")"
                    )
                java = (
                    "OffsetDateTime" if prop.get("format") == "date-time" else "String"
                )
            elif kind in ("integer", "number"):
                for bound, annotation, exclusive in (
                    ("minimum", "DecimalMin", False),
                    ("maximum", "DecimalMax", False),
                    ("exclusiveMinimum", "DecimalMin", True),
                    ("exclusiveMaximum", "DecimalMax", True),
                ):
                    if bound in prop:
                        annotations.append(
                            f'@{annotation}(value = "{prop[bound]}", inclusive = {str(not exclusive).lower()})'
                        )
                # 使用 BigInteger 避免旧聊天 schema 未设上界造成隐式溢出。
                java = (
                    (
                        "Integer"
                        if prop.get("maximum", 2**64) <= 2_147_483_647
                        else "BigInteger"
                    )
                    if kind == "integer"
                    else "Double"
                )
            elif kind == "boolean":
                if prop.get("const") is True:
                    annotations.append("@AssertTrue")
                java = "Boolean"
            elif kind == "array":
                minimum, maximum = prop.get("minItems"), prop.get("maxItems")
                args = ([f"min = {minimum}"] if minimum is not None else []) + (
                    [f"max = {maximum}"] if maximum is not None else []
                )
                if args:
                    annotations.append("@Size(" + ", ".join(args) + ")")
                java = "List<" + field_type(prop["items"], field + "Item") + ">"
            else:
                raise ValueError(f"未支持的字段: {name}.{field} {prop}")
            return " ".join(annotations + [java])

        fields = []
        defaults = []
        required = model.get("required", [])
        for field, prop in model.get("properties", {}).items():
            declaration = field_type(prop, field)
            annotation = f'@JsonProperty(value = "{field}", required = {str(field in required).lower()})'
            if field not in required and not nonnull(prop)[1]:
                annotation += (
                    " @JsonDeserialize(using = RagDefaultValueDeserializer.class)"
                )
                if "default" in prop:
                    default = prop["default"]
                    value = (
                        capital(field) + "." + enum_constant(default)
                        if field_type_is_enum(prop)
                        else literal(default)
                    )
                elif prop.get("type") == "array":
                    value = "List.of()"
                else:
                    raise ValueError(f"无默认值的可选字段: {name}.{field}")
                defaults.append(
                    f"        if ({field} == null) {{ {field} = {value}; }}"
                )
            if "BigInteger" in declaration:
                annotation += " @JsonSerialize(using = RagIntegerSerializer.class)"
            fields.append(f"    {annotation}\n    {declaration} {field}")
        enums_text = ""
        for enum_name, values in enums.items():
            entries = ",\n".join(
                f"        {enum_constant(value)}({literal(value)})" for value in values
            )
            enums_text += f"""\n    /** 保留契约定义的线枚举值。 */
    public enum {enum_name} {{
{entries};
        private final String value;
        {enum_name}(String value) {{ this.value = value; }}
        @JsonValue
        public String value() {{ return value; }}
        @JsonCreator
        public static {enum_name} fromValue(String value) {{
            for ({enum_name} candidate : values()) {{
                if (candidate.value.equals(value)) {{ return candidate; }}
            }}
            throw new IllegalArgumentException("未知契约枚举");
        }}
    }}
"""
        implements = (
            " implements " + ", ".join(sorted(implementations[name]))
            if name in implementations
            else ""
        )
        constructor = (
            "\n    public " + name + " {\n" + "\n".join(defaults) + "\n    }\n"
            if defaults
            else ""
        )
        if name in RULES:
            constructor += "\n    /** 补充 Schema 无法表达的字段关联校验；缺失字段由 Bean Validation 处理。 */\n"
            constructor += (
                '    @JsonIgnore\n    @AssertTrue(message = "契约字段关联不一致")\n'
            )
            constructor += (
                "    public boolean isContractConsistent() {"
                + RULES[name]
                + "\n    }\n"
            )
        ignore = (
            "\n@JsonIgnoreProperties(ignoreUnknown = true)"
            if model.get("additionalProperties") is not False
            else ""
        )
        alias = f'\n@Alias("{ALIASES[name]}")' if name in ALIASES else ""
        comment = model.get("description", name).replace("*/", "").replace("\n", " ")
        sources[name] = f"""{GENERATED}package {PACKAGE};

import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.ruoyi.domain.dto.knowledge.RagDefaultValueDeserializer;
import org.ruoyi.domain.dto.knowledge.RagIntegerSerializer;
import org.hibernate.validator.constraints.CodePointLength;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.util.List;

/** {comment} */
@JsonInclude(JsonInclude.Include.ALWAYS){ignore}{alias}
public record {name}(
{",\n".join(fields)}
){implements} {{
{constructor}{enums_text}}}
"""
    # 生成文件也遵守项目规范，仅保留实际使用的显式 import。
    candidates = (
        [
            "com.fasterxml.jackson.annotation." + item
            for item in (
                "JsonSubTypes",
                "JsonTypeInfo",
                "JsonProperty",
                "JsonIgnore",
                "JsonInclude",
                "JsonIgnoreProperties",
                "JsonValue",
                "JsonCreator",
            )
        ]
        + [
            "jakarta.validation.constraints." + item
            for item in (
                "NotNull",
                "Pattern",
                "Size",
                "DecimalMin",
                "DecimalMax",
                "AssertTrue",
            )
        ]
        + [
            "jakarta.validation.Valid",
            "com.fasterxml.jackson.databind.annotation.JsonSerialize",
            "com.fasterxml.jackson.databind.annotation.JsonDeserialize",
            "org.ruoyi.domain.dto.knowledge.RagIntegerSerializer",
            "org.ruoyi.domain.dto.knowledge.RagDefaultValueDeserializer",
            "org.hibernate.validator.constraints.CodePointLength",
            "org.apache.ibatis.type.Alias",
            "java.math.BigInteger",
            "java.time.OffsetDateTime",
            "java.util.List",
        ]
    )
    for name, content in sources.items():
        header, body = content.split(f"package {PACKAGE};\n", 1)
        body = re.sub(r"^import [^;]+;\n", "", body, flags=re.MULTILINE).lstrip("\n")
        imports = "\n".join(
            f"import {item};"
            for item in sorted(candidates)
            if re.search(r"\b" + item.rsplit(".", 1)[1] + r"\b", body)
        )
        sources[name] = header + f"package {PACKAGE};\n\n" + imports + "\n\n" + body
    return {name: content for name, content in sources.items() if name not in JAVA_EXCLUDED_TYPES}


def enum_constant(value):
    return "V_" + re.sub(r"[^A-Za-z0-9]", "_", value).upper()


def field_type_is_enum(prop):
    return "enum" in prop or "const" in prop


def build_openapi(schema):
    def ref(name):
        return {"$ref": f"./schema.json#/$defs/{name}"}

    paths = {}
    operations = [
        (
            "/internal/knowledge/jobs/{id}/artifact-tickets",
            "issueArtifactTicket",
            "ArtifactTicketRequest",
            "ArtifactTicketResponse",
            "Python → Java",
        ),
        (
            "/internal/knowledge/scopes/{snapshotId}/manifest-pages",
            "pageScopeManifest",
            "ManifestPageRequest",
            "ManifestPageResponse",
            "Python → Java",
        ),
        (
            "/internal/knowledge/scopes/{snapshotId}/validate-candidates",
            "validateScopeCandidates",
            "ValidateCandidatesRequest",
            "CandidateValidationResponse",
            "Python → Java",
        ),
        (
            "/internal/ai/v1/knowledge/retrieval-tests",
            "testKnowledgeRetrieval",
            "RetrievalTestRequest",
            "RetrievalTestResponse",
            "Java → Python",
        ),

        (
            "/internal/ai/v1/chat/rag/stream",
            "streamRagChat",
            "RagChatStreamRequest",
            None,
            "Java → Python",
        ),
    ]
    header_schemas = {
        "X-AI-Signature-Version": {"type": "string", "const": "2"},
        "X-AI-Timestamp": {"type": "string", "pattern": "^(0|[1-9][0-9]*)$"},
        "X-AI-Nonce": {"type": "string", "pattern": "^[A-Za-z0-9_-]{16,128}$"},
        "X-AI-Signature": {"type": "string", "pattern": "^[a-f0-9]{64}$"},
    }
    implemented_operations = {
        "issueArtifactTicket",
        "pageScopeManifest",
        "validateScopeCandidates",
        "testKnowledgeRetrieval",
        "streamRagChat",
    }
    for path, operation, request, response, direction in operations:
        parameters = [
            {"in": "header", "name": name, "required": True, "schema": value}
            for name, value in header_schemas.items()
        ]
        for name in ("id", "snapshotId"):
            if "{" + name + "}" in path:
                parameters.append(
                    {
                        "in": "path",
                        "name": name,
                        "required": True,
                        "schema": {"type": "string", "pattern": "^[1-9][0-9]{0,18}$"},
                    }
                )
        if response:
            success = {
                "description": "成功；直接返回 DTO，不包装 Java R 对象。",
                "content": {"application/json": {"schema": ref(response)}},
            }
        else:
            events = {
                "delta": "DeltaPayload",
                "keepalive": "KeepalivePayload",
                "usage": "Usage",
                "citation": "RagCitationPayload",
                "completed": "RagCompletedPayload",
                "failed": "RagFailedPayload",
            }
            success = {
                "description": "SSE；每次运行最多一个 completed/failed 终态。",
                "content": {"text/event-stream": {"schema": {"type": "string"}}},
                "x-sse-events": {name: ref(value) for name, value in events.items()},
            }
        responses = {"200": success}
        for code, description in {
            "400": "请求不合法",
            "403": "内部认证或授权拒绝",
            "409": "租约、版本或幂等冲突",
            "422": "字段校验失败",
            "503": "依赖暂不可用",
        }.items():
            responses[code] = {
                "description": description,
                "content": {"application/json": {"schema": ref("InternalError")}},
            }
        paths[path] = {
            "post": {
                "operationId": operation,
                "tags": [direction],
                "x-implemented": operation in implemented_operations,
                "description": (
                    "Java 已实现任务绑定的 READ_SOURCE 与 WRITE_MANIFEST 票据。"
                    if operation == "issueArtifactTicket"
                    else "Java 已实现授权清单分页。"
                    if operation == "pageScopeManifest"
                    else "Java 已实现候选有效性与当前权限复核。"
                    if operation == "validateScopeCandidates"
                    else "Python 已实现 RRF、Java 候选复核和知识库级分组 Rerank。"
                    if operation == "testKnowledgeRetrieval"
                    else "仅定义待实现的 RAG 协议。普通聊天现有协议保持不变。"
                    if response is None
                    else "仅定义契约，接口及状态机待后续实现。"
                ),
                "security": [{"InternalServiceToken": []}],
                "parameters": parameters,
                "requestBody": {
                    "required": True,
                    "content": {"application/json": {"schema": ref(request)}},
                },
                "responses": responses,
            }
        }
    paths["/internal/knowledge/jobs/{id}/manifests/{generationId}"] = {
        "put": {
            "operationId": "writeArtifactManifest",
            "tags": ["Python → Java"],
            "x-implemented": True,
            "description": "使用 WRITE_MANIFEST 短期 Bearer 能力上传当前 generation 的不可变清单。",
            "security": [{"ArtifactTicketBearer": []}],
            "parameters": [
                {"in": "path", "name": "id", "required": True,
                 "schema": {"type": "string", "pattern": "^[1-9][0-9]{0,18}$"}},
                {"in": "path", "name": "generationId", "required": True,
                 "schema": {"type": "string", "pattern": "^[1-9][0-9]{0,18}$"}},
            ],
            "requestBody": {"required": True, "content": {
                "application/json": {"schema": {"type": "string", "format": "binary"}}
            }},
            "responses": {
                "200": {"description": "清单已幂等保存。", "content": {
                    "application/json": {"schema": ref("ArtifactUploadResponse")}
                }},
                "403": {"description": "票据无效、过期或任务绑定已失效"},
                "409": {"description": "任务批次冲突或同一 generation 清单内容冲突"},
                "422": {"description": "清单字段与任务快照不一致"},
                "503": {"description": "对象存储暂不可用"},
            },
        }
    }
    paths["/internal/knowledge/jobs/{id}/artifacts/{objectId}"] = {
        "get": {
            "operationId": "readSourceArtifact",
            "tags": ["Python → Java"],
            "x-implemented": True,
            "description": "使用 artifact-tickets 返回的短期 Bearer 能力读取当前任务绑定的源文件。",
            "security": [{"ArtifactTicketBearer": []}],
            "parameters": [
                {"in": "path", "name": "id", "required": True,
                 "schema": {"type": "string", "pattern": "^[1-9][0-9]{0,18}$"}},
                {"in": "path", "name": "objectId", "required": True,
                 "schema": {"type": "string", "pattern": "^[1-9][0-9]{0,18}$"}},
            ],
            "responses": {
                "200": {"description": "源文件原始字节。", "content": {
                    "application/octet-stream": {"schema": {"type": "string", "format": "binary"}}
                }},
                "403": {"description": "票据无效、过期或任务绑定已失效"},
                "409": {"description": "任务 attempt、generation 或执行者已变化"},
            },
        }
    }
    return {
        "openapi": "3.1.0",
        "info": {
            "title": "RAG Java/Python 内部契约",
            "version": "rag-v1",
            "description": "DTO 与结构契约；不表示路由已实现。签名语义见 内部签名规范.md。",
        },
        "paths": paths,
        "components": {
            "securitySchemes": {
                "InternalServiceToken": {
                    "type": "apiKey",
                    "in": "header",
                    "name": "X-AI-Service-Token",
                },
                "ArtifactTicketBearer": {"type": "http", "scheme": "bearer"},
            },
            "schemas": {name: ref(name) for name in sorted(schema["$defs"])},
        },
    }



def build_kafka_manifest():
    """机器可读的 Kafka 传输映射；结构仍复用同一 JSON Schema。"""
    return {
        "format": "knowledge-kafka-manifest-v1",
        "contractVersion": "knowledge-kafka-v1",
        "delivery": "at-least-once",
        "businessStateOwner": "Java",
        "authentication": "Broker service credentials and topic ACL; localhost PLAINTEXT for development only",
        "executionImplemented": False,
        "messages": [
            {"topic": topic, "producer": producer, "consumer": consumer, "key": key,
             "payload": {"$ref": "schema.json#/$defs/" + model}}
            for topic, producer, consumer, key, model in [
                ("knowledge.ingestion.command.v1", "Java", "Python", "jobId", "KnowledgeJobCommand"),
                ("knowledge.job.result.v1", "Python", "Java", "jobId", "KnowledgeJobEvent"),
                ("knowledge.job.control.v1", "Java", "Python (broadcast)", "jobId", "KnowledgeJobControl"),
                ("knowledge.dlq.v1", "Java/Python", "Operations", "original key or null", "KnowledgeDeadLetter"),
            ]
        ],
        "commandAck": "after confirmed terminal result or DLQ marker",
        "resultAck": "after Java database commit or confirmed DLQ marker",
        "identity": ["jobId", "attemptId", "generationId"],
        "sequenceScope": "one worker execution owner within an attempt",
        "replayPolicy": "first accepted workerId owns the attempt; other executions are stale; lease expiry creates a new attempt",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check", action="store_true", help="只检查生成物是否与模型一致"
    )
    args = parser.parse_args()
    schema = build_schema()
    generated_java_sources = java_sources(schema)
    files = {
        OUT / "schema.json": encode(schema),
        OUT / "openapi.json": encode(build_openapi(schema)),
        OUT / "kafka.json": encode(build_kafka_manifest()),
        OUT / "java-types.json": encode(sorted(generated_java_sources)),
    }
    files.update(
        {
            JAVA / (name + ".java"): content
            for name, content in generated_java_sources.items()
        }
    )
    stale = []
    for path, content in files.items():
        content = content.rstrip() + "\n"
        if args.check:
            if not path.exists() or path.read_text(encoding="utf-8") != content:
                stale.append(str(path.relative_to(ROOT)))
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
    for path in JAVA.glob("*.java"):
        if path not in files:
            # 不自动删除文件，避免覆盖用户添加的实现。
            stale.append("额外文件需人工核对: " + str(path.relative_to(ROOT)))
    if stale:
        raise SystemExit("契约生成物不一致：\n" + "\n".join(stale))
    print(
        f"{'校验' if args.check else '生成'}完成：{len(schema['$defs'])} 个模型，{len(generated_java_sources)} 个 Java 类型，7 条内部路由（5 POST + 1 PUT + 1 GET）。"
    )


if __name__ == "__main__":
    main()
