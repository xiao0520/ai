"""补充 JSON Schema 无法表达的字段关联约束，与 Python 校验器共享反例测试。"""

ALIASES = {
    # MyBatis 扫描 org.ruoyi.**.domain，需避开现有持久化实体 ChatMessage。
    "ChatMessage": "RagContractChatMessage",
}


RULES = {
    "IndexSnapshot": """
        if (denseVectorName == null || sparseVectorName == null || chunkOverlapTokens == null
            || chunkSizeTokens == null) { return true; }
        boolean nameCollision = denseVectorName.equals(sparseVectorName)
            || (denseVisionVectorName != null && (denseVisionVectorName.equals(denseVectorName)
                || denseVisionVectorName.equals(sparseVectorName)));
        return !nameCollision && (denseVisionVectorName == null) == (multimodalEmbedding == null)
            && chunkOverlapTokens < chunkSizeTokens;""",
    "DocumentRerankPolicy": """
        if (mode == null) { return true; }
        return mode == Mode.V_NONE
            ? textProfile == null && multimodalProfile == null
            : textProfile != null;""",
    "DocumentProcessingSnapshot": """
        if (processingMode == null || rerankPolicy == null) { return true; }
        if (processingMode == ProcessingMode.V_TEXT) {
            return multimodalEmbedding == null && visionProfile == null
                && rerankPolicy.multimodalProfile() == null;
        }
        return multimodalEmbedding != null && visionProfile != null
            && (rerankPolicy.mode() != DocumentRerankPolicy.Mode.V_PROFILE
                || rerankPolicy.multimodalProfile() != null);""",
    "TextRangeLocator": """
        return startOffset == null || endOffset == null || startOffset < endOffset;""",
    "ImageLocator": """
        if (container == null) { return true; }
        if (container == Container.V_PDF) {
            return pageNumber != null && slideNumber == null
                && startOffset == null && endOffset == null
                && sheetName == null && rowNumber == null && columnNumber == null;
        }
        if (container == Container.V_PPTX) {
            return pageNumber == null && slideNumber != null
                && startOffset == null && endOffset == null
                && sheetName == null && rowNumber == null && columnNumber == null;
        }
        if (container == Container.V_MARKDOWN) {
            return pageNumber == null && slideNumber == null
                && startOffset != null && endOffset != null && startOffset < endOffset
                && sheetName == null && rowNumber == null && columnNumber == null;
        }
        if (container == Container.V_XLSX) {
            return pageNumber == null && slideNumber == null
                && startOffset == null && endOffset == null && sheetName != null
                && ((rowNumber == null && columnNumber == null)
                    || (rowNumber != null && columnNumber != null));
        }
        return pageNumber == null && slideNumber == null
            && startOffset == null && endOffset == null
            && sheetName == null && rowNumber == null && columnNumber == null;""",
    "SpreadsheetRangeLocator": """
        return rowStart == null || rowEnd == null || columnStart == null || columnEnd == null
            || (rowStart < rowEnd && columnStart < columnEnd);""",
    "TableRangeLocator": """
        return rowStart == null || rowEnd == null || columnStart == null || columnEnd == null
            || (rowStart < rowEnd && columnStart < columnEnd);""",
    "RetrievalProfile": """
        return finalTopK == null || rerankTopK == null || maxContextChunks == null
            || (finalTopK <= rerankTopK && finalTopK <= maxContextChunks);""",
    "ArtifactManifestEntry": """
        if (modality == null) { return true; }
        return (modality == Modality.V_IMAGE) == (mediaHash != null);""",
    "ArtifactManifest": """
        if (entries == null || entries.stream().anyMatch(java.util.Objects::isNull)) { return true; }
        boolean hasImages = entries.stream().anyMatch(
            entry -> entry.modality() == ArtifactManifestEntry.Modality.V_IMAGE);
        return hasImages == (visionVectorDimensions != null)
            && entries.stream().map(ArtifactManifestEntry::pointId).distinct().count() == entries.size()
            && entries.stream().map(ArtifactManifestEntry::chunkOrdinal).distinct().count() == entries.size()
            && entries.stream().mapToInt(ArtifactManifestEntry::chunkOrdinal).min().orElse(0) == 0
            && entries.stream().mapToInt(ArtifactManifestEntry::chunkOrdinal).max().orElse(-1)
                == entries.size() - 1;""",
    "IngestionResult": """
        if (chunkCount == null || denseCount == null || sparseCount == null
            || visionCount == null || parseStats == null) { return true; }
        boolean visionShape = (visionCount == 0) == (visionVectorDimensions == null);
        return chunkCount > 0 && chunkCount.equals(denseCount) && chunkCount.equals(sparseCount)
            && visionCount <= chunkCount && visionShape
            && !(parseStats.imageCount() == 0 && visionCount != 0)
            && !Boolean.TRUE.equals(parseStats.truncated());""",
    "KnowledgeJobCommand": """
        if (knowledgeId == null || task == null || indexSnapshot == null) { return true; }
        if (!knowledgeId.equals(indexSnapshot.knowledgeId())) { return false; }
        String target = task instanceof KnowledgeDocumentTask documentTask
            ? documentTask.targetIndexVersion()
            : task instanceof KnowledgeCleanupTask cleanupTask
                ? cleanupTask.targetIndexVersion()
                : ((KnowledgeReconcileTask) task).targetIndexVersion();
        if (target != null && !target.equals(indexSnapshot.indexVersion())) { return false; }
        if (!(task instanceof KnowledgeDocumentTask)) {
            return documentProcessingSnapshot == null && executionBudget == null;
        }
        if (documentProcessingSnapshot == null || executionBudget == null
            || !documentProcessingSnapshot.textEmbedding().profile().equals(indexSnapshot.embedding().profile())) {
            return false;
        }
        var indexMultimodal = indexSnapshot.multimodalEmbedding();
        var documentMultimodal = documentProcessingSnapshot.multimodalEmbedding();
        return documentMultimodal == null || (indexMultimodal != null
            && indexMultimodal.profile().equals(documentMultimodal.profile()));""",
    "KnowledgeRetrievalSettings": """
        if (denseEnabled == null || sparseEnabled == null || recallCount == null
            || rerankEnabled == null || rerankInputCount == null || rerankOutputCount == null) {
            return true;
        }
        return (denseEnabled || sparseEnabled)
            && (!rerankEnabled || (rerankInputCount <= recallCount
                && rerankOutputCount <= rerankInputCount))
            && rerankEnabled == (rerankProfile != null)
            && (relevanceThreshold == null || denseEnabled || rerankEnabled);""",
    "RagExecutionScope": """
        if (retrievalProfile == null || retrievalSnapshot == null || knowledgeScopes == null
            || indexSnapshots == null || knowledgeScopes.stream().anyMatch(java.util.Objects::isNull)
            || indexSnapshots.stream().anyMatch(java.util.Objects::isNull)) { return true; }
        if (!retrievalProfile.equals(retrievalSnapshot.profile())) { return false; }
        if (knowledgeScopes.stream().anyMatch(s -> s.retrievalSettings() == null)) { return false; }
        record Member(String knowledge, String index, Integer revision) { }
        var scopes = knowledgeScopes.stream().map(s -> new Member(s.knowledgeId(), s.indexVersion(),
            s.manifestRevision())).collect(java.util.stream.Collectors.toSet());
        var indexes = indexSnapshots.stream().map(s -> new Member(s.knowledgeId(), s.indexVersion(),
            s.manifestRevision())).collect(java.util.stream.Collectors.toSet());
        return scopes.size() == knowledgeScopes.size() && indexes.size() == indexSnapshots.size()
            && scopes.equals(indexes)
            && knowledgeScopes.stream().map(KnowledgeScope::knowledgeId).distinct().count() == scopes.size();""",
    "RagCompletedPayload": """
        if (answerStatus == null || citations == null
            || citations.stream().anyMatch(java.util.Objects::isNull)) { return true; }
        boolean hasEvidence = answerStatus == AnswerStatus.V_ANSWERED || answerStatus == AnswerStatus.V_PARTIAL;
        return hasEvidence == !citations.isEmpty() && (citations.isEmpty() || validationTicket != null)
            && citations.stream().map(Citation::citationId).distinct().count() == citations.size();""",
}
