package org.ruoyi.domain.dto.chat;

import java.util.List;

/**
 * Java 调用 LangChain 聊天模型路由器的内部请求。
 *
 * @param requestId                  本次路由请求的唯一标识
 * @param candidateSnapshotHash      Java 候选快照摘要
 * @param question                   用户当前问题
 * @param features                   Java 核定的请求特征
 * @param candidates                 允许 Python 选择的模型候选
 * @param policy                     本次使用的确定性路由策略
 */
public record PythonChatRouteRequest(String requestId, String candidateSnapshotHash, String question,
                                     PythonChatRouteFeatures features,
                                     List<PythonChatRouteCandidate> candidates,
                                     PythonChatRoutePolicy policy) {
}