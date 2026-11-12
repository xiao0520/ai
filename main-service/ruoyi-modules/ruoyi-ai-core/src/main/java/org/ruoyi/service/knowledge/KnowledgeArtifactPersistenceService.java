package org.ruoyi.service.knowledge;

import lombok.RequiredArgsConstructor;
import org.ruoyi.domain.entity.knowledge.KnowledgeArtifactGeneration;
import org.ruoyi.mapper.knowledge.KnowledgeArtifactMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识库产物登记服务。
 *
 * <p>以 generation 唯一键幂等登记已上传清单。OSS 上传发生在调用方，本地事务只负责数据库登记，
 * 防止把不可回滚的网络操作误认为数据库事务的一部分。</p>
 */
@Service
@RequiredArgsConstructor
public class KnowledgeArtifactPersistenceService {
    private final KnowledgeArtifactMapper artifacts;

    /**
     * 查询已登记的产物批次。
     *
     * @param generationId 产物批次主键
     * @return 产物批次；不存在时返回 {@code null}
     */
    public KnowledgeArtifactGeneration find(Long generationId) {
        return artifacts.selectGeneration(generationId);
    }

    /**
     * 幂等登记已上传的产物清单。
     *
     * @param generation 待登记的产物批次
     * @return 唯一键对应的最终数据库记录
     */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeArtifactGeneration storeUploaded(KnowledgeArtifactGeneration generation) {
        artifacts.insertGenerationIgnore(generation);
        return artifacts.selectGeneration(generation.getGenerationId());
    }
}
