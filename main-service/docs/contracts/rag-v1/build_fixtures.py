"""维护脱敏共享样例，包含任务、授权、检索、流事件及拒绝场景。"""

import json
from copy import deepcopy
from pathlib import Path

V = {}
T = "2026-09-15T12:00:00+08:00"
H = "a" * 64
TOKEN = "synthetic-ticket-0001"
BASE = {"contractVersion": "rag-v1", "requestId": "request-0001"}


def add(name, **value):
    V[name] = deepcopy(value)
    return deepcopy(value)


ref = add("ProfileRef", id="11", revision=1)
embedding = add(
    "EmbeddingProfile",
    profile=ref,
    providerCode="doubao",
    modelName="doubao-embedding-vision",
    apiHost="https://model.example.invalid/api/v3/",
    apiMode="ARK_MULTIMODAL_EMBEDDINGS",
    credentialKey="embedding-test-key",
    dimensions=2048,
    documentInputMode="TEXT_SINGLE",
    queryInputMode="TEXT_SINGLE",
    normalized=True,
    maxInputTokens=4096,
    timeoutSeconds=60,
)
rerank = add(
    "RerankProfile",
    profile={"id": "12", "revision": 1},
    providerCode="test-provider",
    modelName="synthetic-rerank",
    apiHost="https://rerank.example.invalid/",
    adapterCode="SYNTHETIC_TEST",
    credentialKey="rerank-test-key",
    inputMode="TEXT",
    maxDocumentTokens=2048,
    timeoutSeconds=60,
)
add(
    "VisionProfile",
    profile={"id": "15", "revision": 1},
    providerCode="test-provider",
    modelName="synthetic-vision",
    apiHost="https://vision.example.invalid/",
    apiMode="SYNTHETIC_VISION",
    credentialKey="vision-test-key",
    maxInputTokens=4096,
    timeoutSeconds=60,
)
rerank_policy = add(
    "DocumentRerankPolicy",
    mode="PROFILE",
    textProfile=rerank,
    multimodalProfile=None,
)
document_processing = add(
    "DocumentProcessingSnapshot",
    configurationRevision=1,
    processingMode="TEXT",
    textEmbedding=embedding,
    multimodalEmbedding=None,
    visionProfile=None,
    rerankPolicy=rerank_policy,
)
rewrite = add(
    "RagChatModelProfile",
    profile={"id": "13", "revision": 1},
    modelName="synthetic-chat",
    providerCode="test-provider",
    apiHost="https://model.example.invalid/",
    apiMode="CHAT_COMPLETIONS",
    credentialKey="chat-test-key",
    maxInputTokens=8000,
    maxOutputTokens=1000,
    timeoutSeconds=60,
)
index = add(
    "IndexSnapshot",
    knowledgeId="101",
    indexVersion="201",
    collectionName="kb_101_v20260924-01",
    denseVectorName="dense",
    denseVisionVectorName=None,
    sparseVectorName="sparse",
    distance="COSINE",
    embedding=embedding,
    multimodalEmbedding=None,
    parserRevision="parser-v1",
    splitterRevision="split-v1",
    tokenizerRevision="tokenizer-test-v1",
    sparseRevision="bm25-test-v1",
    sparseCorpusRevision="corpus-v1",
    chunkSizeTokens=512,
    chunkOverlapTokens=64,
    configHash=H,
    manifestRevision=1,
)
measurement = add(
    "TokenMeasurement", unit="TOKENS", quality="ACTUAL", inputTokens=8, outputTokens=2
)
add("RequestMeasurement", unit="DOCUMENT_PAIRS", quality="ACTUAL", quantity=3)
add("UnknownMeasurement", unit="UNKNOWN", reason="NOT_REPORTED")
usage = add(
    "ExecutionUsageItem",
    stage="EMBEDDING",
    callId="call-0001",
    attemptId="attempt-0001",
    profile=ref,
    measurement=measurement,
)
add(
    "InternalError",
    requestId="request-0001",
    errorCode="SCOPE_EXPIRED",
    retryable=False,
)
add("VersionedRequest", **BASE)
source = add(
    "SourceRef",
    knowledgeId="101",
    indexVersion="201",
    documentId="301",
    documentVersionId="401",
    generationId="501",
    chunkId="chunk-0001",
    contentHash=H,
)
locator = add("PdfPageLocator", kind="PDF_PAGE", pageNumber=1)
add("TextRangeLocator", kind="TEXT_RANGE", startOffset=0, endOffset=24)
add("DocxBlockLocator", kind="DOCX_BLOCK", blockIndex=0)
add(
    "PresentationBlockLocator",
    kind="PRESENTATION_BLOCK",
    container="PPTX",
    slideNumber=1,
    shapeIndex=0,
    rowIndex=None,
)
add(
    "SpreadsheetRangeLocator",
    kind="SPREADSHEET_RANGE",
    container="XLSX",
    sheetName="制度",
    rowStart=1,
    rowEnd=2,
    columnStart=1,
    columnEnd=3,
)
add(
    "ImageLocator",
    kind="IMAGE",
    container="PDF",
    imageIndex=0,
    pageNumber=1,
    slideNumber=None,
    startOffset=None,
    endOffset=None,
    sheetName=None,
    rowNumber=None,
    columnNumber=None,
    contentHash=H,
    mediaType="image/png",
)
add(
    "TableRangeLocator",
    kind="TABLE_RANGE",
    tableIndex=0,
    rowStart=0,
    rowEnd=2,
    columnStart=0,
    columnEnd=2,
)
location = add(
    "SourceLocation",
    source=source,
    title="脱敏测试资料",
    titlePath=["第一章"],
    locator=locator,
)
knowledge_retrieval_settings = add(
    "KnowledgeRetrievalSettings",
    denseEnabled=True,
    sparseEnabled=False,
    recallCount=10,
    rrfK=60,
    rerankEnabled=False,
    rerankProfile=None,
    rerankInputCount=10,
    rerankOutputCount=5,
    relevanceThreshold=None,
)
scope = add(
    "KnowledgeScope",
    knowledgeId="101",
    indexVersion="201",
    permissionRevision=1,
    manifestRevision=1,
    retrievalSettings=knowledge_retrieval_settings,
)
retrieval = add(
    "RetrievalProfile",
    profile={"id": "14", "revision": 1},
    mode="DENSE_BM25_RRF_RERANK",
    denseTopK=10,
    sparseTopK=10,
    rrfK=60,
    rerankTopK=10,
    finalTopK=5,
    neighborWindow=1,
    maxContextChunks=10,
)
rag = add(
    "RagExecutionScope",
    contractVersion="rag-v1",
    mode="KNOWLEDGE_QA",
    scopeSnapshotId="601",
    expiresAt=T,
    knowledgeScopes=[scope],
    retrievalProfile=retrieval["profile"],
    retrievalSnapshot=retrieval,
    indexSnapshots=[index],
    contextBudgetTokens=4000,
)
ticket = add(
    "ArtifactTicket",
    objectId="source-object-001",
    operation="READ_SOURCE",
    url="https://objects.example.invalid/source?ticket=synthetic",
    token=TOKEN,
    expiresAt=T,
    contentHash=H,
    maxBytes=1048576,
)
parse = add(
    "ParseStats",
    pageCount=1,
    blockCount=1,
    characterCount=24,
    imageCount=0,
    ocrCharacterCount=0,
    skippedBlockCount=0,
    skippedImageCount=0,
    truncated=False,
)
result = add(
    "IngestionResult",
    jobType="INGESTION",
    manifestObjectId="manifest-object-001",
    manifestHash=H,
    chunkCount=1,
    denseCount=1,
    sparseCount=1,
    visionCount=0,
    vectorDimensions=2048,
    visionVectorDimensions=None,
    parseStats=parse,
    usageItems=[usage],
)
add(
    "ArtifactTicketRequest",
    **BASE,
    attemptId="attempt-0001",
    generationId="501",
    workerId="worker-0001",
    operation="READ_SOURCE",
    objectId="source-object-001",
)
add("ArtifactTicketResponse", ticket=ticket)
add("ArtifactUploadResponse", objectId="manifest-object-001", contentHash=H, sizeBytes=4096)
add(
    "ManifestPageRequest",
    **BASE,
    knowledgeId="101",
    indexVersion="201",
    manifestRevision=1,
    cursor=None,
    pageSize=100,
)
member = add(
    "ManifestMember", documentId="301", documentVersionId="401", generationId="501"
)
add(
    "ManifestPageResponse",
    scopeSnapshotId="601",
    knowledgeId="101",
    indexVersion="201",
    manifestRevision=1,
    members=[member],
    nextCursor=None,
    expiresAt=T,
)
add("ValidateCandidatesRequest", **BASE, candidates=[source], purpose="MODEL_CONTEXT")
add(
    "CandidateValidationResponse",
    scopeSnapshotId="601",
    allowedCandidates=[source],
    validationTicket=TOKEN,
    expiresAt=T,
)
add("RetrievalTestRequest", **BASE, query="资料的主要内容是什么？", rag=rag)
chunk = add(
    "RetrievedChunk",
    location=location,
    text="这是一份脱敏测试资料。",
    denseScore=0.8,
    sparseScore=0.4,
    rrfScore=0.025,
    rerankScore=0.9,
)
stage = add(
    "RetrievalStageStats",
    stage="DENSE",
    status="COMPLETED",
    durationMs=12,
    inputCount=1,
    outputCount=1,
)
add(
    "RetrievalTestResponse",
    requestId="request-0001",
    scopeSnapshotId="601",
    chunks=[chunk],
    validationTicket=TOKEN,
    executionUsage=[usage],
    stages=[stage],
)
message = add("ChatMessage", role="user", content="你好")
chat_input = add("ChatInput", messages=[message])
actor = add("ChatActor", userId="21", roles=["employee"])
model = add(
    "ModelProfile",
    id=15,
    revision=1,
    modelName="synthetic-chat",
    providerCode="test-provider",
    apiHost="https://model.example.invalid/",
    apiMode="CHAT_COMPLETIONS",
    credentialKey="chat-test-key",
    maxInputTokens=8000,
    maxOutputTokens=1000,
)
mcp_tool = add(
    "McpToolGrant",
    serverId="31",
    serverCode="weather",
    endpoint="https://mcp.example.invalid/mcp",
    authType="NONE",
    credentialKey=None,
    connectTimeoutSeconds=10,
    toolId="32",
    toolName="get_forecast",
    qualifiedName="weather:get_forecast",
    inputSchema='{"type":"object","properties":{"city":{"type":"string"}}}',
    timeoutSeconds=30,
)
policy = add(
    "ExecutionPolicy",
    maxInputTokens=8000,
    maxOutputTokens=1000,
    timeoutSeconds=60,
)
rag_policy = {**policy, "maxTotalTokens": None}
add(
    "RagChatStreamRequest",
    requestId="request-0001",
    clientRequestId="client-0001",
    sessionId="session-0001",
    actor=actor,
    input=chat_input,
    modelProfile=model,
    executionPolicy=rag_policy,
    rag=rag,
    tools=[mcp_tool],
)
chat_usage = add("Usage", inputTokens=8, outputTokens=2, totalTokens=10)
add("DeltaPayload", text="你好")
add("KeepalivePayload", requestId="request-0001")
citation = add(
    "Citation", citationId="cite-1", location=location, excerpt="这是一份脱敏测试资料。"
)
add("RagCitationPayload", requestId="request-0001", citation=citation)
add(
    "RagCompletedPayload",
    requestId="request-0001",
    content="资料示例。[cite-1]",
    usage=chat_usage,
    answerStatus="ANSWERED",
    citations=[citation],
    validationTicket=TOKEN,
    executionUsage=[usage],
)
add("RagFailedPayload", requestId="request-0001", errorCode="VECTOR_UNAVAILABLE", executionUsage=[usage])
entry = add(
    "ArtifactManifestEntry",
    pointId="00000000-0000-4000-8000-000000000001",
    chunkOrdinal=0,
    tokenCount=24,
    modality="TEXT",
    mediaHash=None,
    location=location,
)
add(
    "ArtifactManifest",
    contractVersion="rag-v1",
    jobId="701",
    attemptId="attempt-0001",
    generationId="501",
    knowledgeId="101",
    indexVersion="201",
    documentId="301",
    documentVersionId="401",
    configHash=H,
    sourceHash=H,
    vectorDimensions=2048,
    visionVectorDimensions=None,
    createdAt=T,
    entries=[entry],
)

for name, original in {
    "RagChatActor": "ChatActor",
    "RagChatMessage": "ChatMessage",
    "RagChatInput": "ChatInput",
    "RagGenerationProfile": "ModelProfile",
    "RagGenerationUsage": "Usage",
}.items():
    V[name] = deepcopy(V[original])
V["RagExecutionPolicy"] = deepcopy(rag_policy)

kb = {"contractVersion": "knowledge-kafka-v1"}
command_index = deepcopy(V["IndexSnapshot"])
command_index["knowledgeId"] = "20"
command_index["indexVersion"] = "201"
doc_task = add("KnowledgeDocumentTask", jobType="INGESTION", documentId="21", documentVersionId="22", ossId="23", sourceHash=H, mediaType="text/plain", sourceName="制度说明.txt", fileSizeBytes=100, targetIndexVersion="201")
add("KnowledgeCleanupTask", jobType="CLEANUP", targetIndexVersion="201", targetGenerationId="25", reason="FAILED")
add("KnowledgeReconcileTask", jobType="RECONCILE", targetIndexVersion="201", retainedGenerationIds=["25"])
budget = add("KnowledgeExecutionBudget", maxTokens=200000)
add("KnowledgeJobCommand", **kb, messageId="message-1", requestId="request-1", jobId="24", knowledgeId="20", attemptId="attempt-1", generationId="25", attemptNumber=1, createdAt=T, deadline=T, task=doc_task, indexSnapshot=command_index, documentProcessingSnapshot=document_processing, executionBudget=budget)
started = add("KnowledgeStartedEvent", eventType="STARTED")
add("KnowledgeProgressEvent", eventType="PROGRESS", stage="PARSING", progress=20)
add("KnowledgeFailedEvent", eventType="FAILED", errorCode="EXECUTOR_NOT_READY", usageItems=[])
add("KnowledgeCancelledEvent", eventType="CANCELLED", usageItems=[])
add("KnowledgeArtifactsEvent", eventType="ARTIFACTS_READY", result=V["IngestionResult"])
maintenance_result = add("KnowledgeMaintenanceResult", jobType="CLEANUP", targetGenerationId="25", observedGenerationIds=["25"], deletedGenerationIds=["25"], observedPointCount=3, deletedPointCount=3)
add("KnowledgeMaintenanceEvent", eventType="MAINTENANCE_COMPLETED", result=maintenance_result)
add("KnowledgeJobEvent", **kb, messageId="event-1", requestId="request-1", jobId="24", attemptId="attempt-1", generationId="25", workerId="worker-1", sequence=1, event=started)
add("KnowledgeJobControl", **kb, messageId="control-1", jobId="24", attemptId="attempt-1", action="CANCEL", expiresAt=T)
add("KnowledgeDeadLetter", **kb, sourceTopic="knowledge.test", sourcePartition=0, sourceOffset=1, payloadHash=H, reason="INVALID_MESSAGE")

cases = [
    {"name": name, "model": name, "valid": True, "schemaValid": True, "payload": value}
    for name, value in V.items()
]


def variant(name, model, path, value, valid=False, schema_valid=False):
    """按明确字段构造变体，保持反例意图可审查。"""
    data = json.loads(json.dumps(V[model]))
    target = data
    for key in path[:-1]:
        target = target[key]
    target[path[-1]] = value
    cases.append(
        {
            "name": name,
            "model": model,
            "valid": valid,
            "schemaValid": schema_valid or valid,
            "payload": data,
        }
    )


variant(
    "rag-chat-numeric-string-id", "RagChatStreamRequest", ["modelProfile", "id"], "15"
)
variant(
    "rag-chat-unknown-message-field",
    "RagChatStreamRequest",
    ["input", "messages", 0, "unexpected"],
    "value",
)
variant(
    "rag-chat-unknown-actor-field",
    "RagChatStreamRequest",
    ["actor", "unexpected"],
    "value",
)
variant("rag-profile-null-mode", "RagGenerationProfile", ["apiMode"], None)
variant("rag-actor-null-roles", "RagChatActor", ["roles"], None)
variant("rag-score-not-nan", "RetrievedChunk", ["denseScore"], "NaN")
variant("rag-score-not-infinity", "RetrievedChunk", ["denseScore"], "Infinity")
variant("profile-no-secret", "EmbeddingProfile", ["apiKey"], "synthetic-not-a-secret")
variant("embedding-dimensions-zero", "EmbeddingProfile", ["dimensions"], 0)
variant("embedding-api-mode-empty", "EmbeddingProfile", ["apiMode"], "")
variant("source-hash-invalid", "SourceRef", ["contentHash"], "badhash")
variant("scope-empty", "RagExecutionScope", ["knowledgeScopes"], [])
variant("scope-missing-indexes", "RagExecutionScope", ["indexSnapshots"], [])
variant("locator-page-zero", "PdfPageLocator", ["pageNumber"], 0)
variant("locator-extraneous-fields", "SourceLocation", ["locator", "blockIndex"], 0)
variant("image-locator-pdf-with-offset", "ImageLocator", ["startOffset"], 0, schema_valid=True)
variant("image-locator-pptx-without-slide", "ImageLocator", ["container"], "PPTX", schema_valid=True)
variant("spreadsheet-range-reversed", "SpreadsheetRangeLocator", ["rowEnd"], 1, schema_valid=True)
variant("manifest-text-with-media", "ArtifactManifestEntry", ["mediaHash"], H, schema_valid=True)
variant("ingestion-vision-without-dimensions", "IngestionResult", ["visionCount"], 1, schema_valid=True)
variant("candidate-no-user-query", "ValidateCandidatesRequest", ["userId"], "22")
variant("unknown-usage-no-zero", "UnknownMeasurement", ["quantity"], 0)
variant("unknown-error", "RagFailedPayload", ["errorCode"], "STACKTRACE")
variant(
    "index-overlap", "IndexSnapshot", ["chunkOverlapTokens"], 512, schema_valid=True
)
variant(
    "index-vector-name-collision",
    "IndexSnapshot",
    ["sparseVectorName"],
    "dense",
    schema_valid=True,
)
variant("range-reversed", "TextRangeLocator", ["startOffset"], 24, schema_valid=True)
variant(
    "table-range-reversed", "TableRangeLocator", ["columnStart"], 2, schema_valid=True
)
variant(
    "retrieval-limit-inconsistent",
    "RetrievalProfile",
    ["finalTopK"],
    11,
    schema_valid=True,
)
variant(
    "incomplete-dense-count", "IngestionResult", ["denseCount"], 0, schema_valid=True
)
variant(
    "truncated-ingestion",
    "IngestionResult",
    ["parseStats", "truncated"],
    True,
    schema_valid=True,
)
variant(
    "scope-profile-mismatch",
    "RagExecutionScope",
    ["retrievalProfile", "revision"],
    2,
    schema_valid=True,
)
variant(
    "scope-index-mismatch",
    "RagExecutionScope",
    ["indexSnapshots", 0, "indexVersion"],
    "202",
    schema_valid=True,
)
variant(
    "scope-missing-retrieval-settings",
    "RagExecutionScope",
    ["knowledgeScopes", 0, "retrievalSettings"],
    None,
)
variant(
    "document-processing-text-with-vision",
    "DocumentProcessingSnapshot",
    ["visionProfile"],
    V["VisionProfile"],
    schema_valid=True,
)
variant(
    "document-rerank-none-with-profile",
    "DocumentRerankPolicy",
    ["mode"],
    "NONE",
    schema_valid=True,
)
variant(
    "scope-duplicate",
    "RagExecutionScope",
    ["knowledgeScopes"],
    [scope, scope],
    schema_valid=True,
)
variant(
    "answer-missing-citation",
    "RagCompletedPayload",
    ["citations"],
    [],
    schema_valid=True,
)
variant(
    "answer-missing-ticket",
    "RagCompletedPayload",
    ["validationTicket"],
    None,
    schema_valid=True,
)
variant(
    "answer-duplicate-citation",
    "RagCompletedPayload",
    ["citations"],
    [citation, citation],
    schema_valid=True,
)
variant(
    "no-evidence-with-citation",
    "RagCompletedPayload",
    ["answerStatus"],
    "NO_EVIDENCE",
    schema_valid=True,
)
for name in (
    "TextRangeLocator",
    "DocxBlockLocator",
    "PresentationBlockLocator",
    "SpreadsheetRangeLocator",
    "TableRangeLocator",
    "ImageLocator",
):
    variant("location-" + name, "SourceLocation", ["locator"], V[name], valid=True)
for name in ("RequestMeasurement", "UnknownMeasurement"):
    variant("usage-" + name, "ExecutionUsageItem", ["measurement"], V[name], valid=True)
variant("scope-empty-manifest", "ManifestPageResponse", ["members"], [], valid=True)
variant(
    "scope-denied-candidates",
    "CandidateValidationResponse",
    ["allowedCandidates"],
    [],
    valid=True,
)
variant(
    "large-string-id", "SourceRef", ["documentId"], "9223372036854775807", valid=True
)
variant("unicode-codepoint-boundary", "SourceRef", ["chunkId"], "😀" * 128, valid=True)
variant("unicode-too-long", "SourceRef", ["chunkId"], "😀" * 129)
for name in ("NO_EVIDENCE", "NEEDS_CLARIFICATION"):
    data = deepcopy(V["RagCompletedPayload"])
    data.update(answerStatus=name, citations=[], validationTicket=None)
    cases.append(
        {
            "name": "answer-" + name,
            "model": "RagCompletedPayload",
            "valid": True,
            "schemaValid": True,
            "payload": data,
        }
    )
for model, fields in (
    ("RagGenerationProfile", ["apiMode", "maxOutputTokens"]),
    ("RagChatActor", ["roles"]),
    ("RagGenerationUsage", ["inputTokens", "outputTokens", "totalTokens"]),
):
    payload = deepcopy(V[model])
    expected = deepcopy(payload)
    for field in fields:
        del payload[field]
        expected[field] = (
            "CHAT_COMPLETIONS"
            if field == "apiMode"
            else ([] if field == "roles" else None)
        )
    cases.append(
        {
            "name": "defaults-" + model,
            "model": model,
            "valid": True,
            "schemaValid": True,
            "payload": payload,
            "expectedPayload": expected,
        }
    )
variant("kafka-invalid-KnowledgeJobCommand-knowledgeId", "KnowledgeJobCommand", ["knowledgeId"], "21", schema_valid=True)

for model, field, invalid in (
    ("KnowledgeJobCommand", "jobId", 24),
    ("KnowledgeJobCommand", "attemptNumber", 0),
    ("KnowledgeJobCommand", "unexpected", "rejected"),
    ("KnowledgeJobEvent", "sequence", 0),
    ("KnowledgeJobEvent", "generationId", 25),
    ("KnowledgeJobControl", "action", "DELETE"),
    ("KnowledgeFailedEvent", "errorCode", "UNKNOWN"),
    ("KnowledgeDeadLetter", "sourceOffset", -1),
):
    variant("kafka-invalid-"+model+"-"+field, model, [field], invalid)

Path(__file__).with_name("fixtures.json").write_text(
    json.dumps(cases, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
)
print(f"生成 {len(cases)} 个共享样例。")
