package org.ruoyi.enums;

/** 聊天模型选择模式。 */
public enum ChatModelSelectionMode {

    /** 使用用户明确指定且经 Java 鉴权的单个模型。 */
    FIXED,

    /** 由 Java 限定候选，Python LangChain 路由，Java 最终复核后使用实际模型。 */
    AUTO
}