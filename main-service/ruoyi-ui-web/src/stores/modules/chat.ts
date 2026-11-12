import type { ChatMessageVo } from '@/api/chat/types';
import { defineStore } from 'pinia';
import { getChatList } from '@/api';
import { useUserStore } from './user';

export const useChatStore = defineStore('chat', () => {
  const userStore = useUserStore();

  // 用户头像
  const avatar = computed(() => {
    const userInfo = userStore.userInfo;
    return userInfo?.avatar || 'https://avatars.githubusercontent.com/u/32251822?s=96&v=4';
  });

  // 是否开启深度思考
  const isDeepThinking = ref<boolean>(false);

  const setDeepThinking = (value: boolean) => {
    isDeepThinking.value = value;
  };

  // 知识库ID
  const knowledgeId = ref<string>('');

  const setKnowledgeId = (id: string) => {
    knowledgeId.value = id;
  };

  // 按会话 ID 缓存聊天记录，切换会话时可直接回显。
  const chatMap = ref<Record<string, ChatMessageVo[]>>({});

  /** 将服务端消息映射为气泡组件字段，并拆出历史消息中的思考内容。 */
  const setChatMap = (id: string, data: ChatMessageVo[]) => {
    chatMap.value[id] = data?.map((item: ChatMessageVo) => {
      const isUser = item.role === 'user';
      const originalContent = item.content?.trim()
        || (item.callStatus === 'FAILED' ? failureContent(item.errorMessage) : '');

      const thinkContent = extractThkContent(originalContent);
      const afterThinkContent = extractThkContentAfter(originalContent);

      const result = {
        ...item,
        key: item.id,
        placement: isUser ? 'end' : 'start',
        isMarkdown: !isUser,
        // 气泡组件用 system 表示左侧助手消息。
        role: isUser ? 'user' : 'system',
        avatar: isUser
          ? avatar
          : 'https://cube.elemecdn.com/0/88/03b0d39583f48206768a7534e55bcpng.png',
        avatarSize: '32px',
        typing: false,
        reasoning_content: thinkContent,
        thinkingStatus: 'end',
        content: afterThinkContent,
        thinlCollapse: false,
        noStyle: !isUser,
      };

      return result;
    });
  };

  // 获取当前会话的聊天记录
  const requestChatList = async (sessionId: string) => {
    // 如果没有 token 则不查询聊天记录
    if (!userStore.token)
      return;
    try {
      const res = await getChatList({
        sessionId,
      });
      if (res.rows) {
        setChatMap(sessionId, res.rows);
      }
    }
    catch (error) {
      console.error('getChatList:', error);
    }
  };

  // 对思考中的内容回显做处理
  function extractThkContent(content: string) {
    const regex = /<think>(.*?)<\/think>/s;
    const matchResult = content.match(regex);
    return matchResult?.[1] ?? '';
  }

  // 兼容旧失败记录：这些记录的错误说明只存储在 errorMessage，正文为空。
  function failureContent(errorMessage?: string) {
    return `本次对话未完成：${errorMessage || '模型调用失败，请稍后重试'}`;
  }

  // 如果有 </think> 标签，则把 </think> 之后的 内容从 content 中返回
  function extractThkContentAfter(content: string) {
    if (!content.includes('</think>')) {
      return content;
    }

    // 查找 </think> 的位置
    const thinkEndIdx = content.indexOf('</think>');
    if (thinkEndIdx === -1) {
      return content;
    }

    // 直接截取 </think> 之后的所有内容
    const afterThink = content.substring(thinkEndIdx + 8); // 8 是 '</think>' 的长度
    return afterThink;
  }

  return {
    chatMap,
    requestChatList,
    isDeepThinking,
    setDeepThinking,
    knowledgeId,
    setKnowledgeId,
  };
});
