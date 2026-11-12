<!-- 每个回话对应的聊天内容 -->
<script setup lang="ts">
import type { AnyObject } from 'typescript-api-pro';
import type { BubbleProps } from 'vue-element-plus-x/types/Bubble';
import type { BubbleListInstance } from 'vue-element-plus-x/types/BubbleList';
import type { ThinkingStatus } from 'vue-element-plus-x/types/Thinking';
import type { ToolCallInfo } from './types';
import type { ChatCitationVo, ChatKnowledgeOptionVO, SendDTO } from '@/api/chat/types';
import { useHookFetch } from 'hook-fetch/vue';
import { nextTick } from 'vue';
import { useRoute } from 'vue-router';
import { send } from '@/api';
import { getChatCitation, getChatKnowledgeOptions } from '@/api/chat';
import ChatSender from '@/components/ChatSender/index.vue';
import { useAgentStore } from '@/stores/modules/agent';
import { useChatStore } from '@/stores/modules/chat';
import { useModelStore } from '@/stores/modules/model';
import { useUserStore } from '@/stores/modules/user';
import { codeXRender } from '@/utils/markdownRenderers';
import ToolCallCard from './components/ToolCallCard.vue';

type MessageItem = BubbleProps & {
  key: number;
  role: 'ai' | 'user' | 'system';
  avatar: string;
  thinkingStatus?: ThinkingStatus;
  thinlCollapse?: boolean;
  reasoning_content?: string;
  class?: string;
  id?: number;
  citations?: ChatCitationVo[];
  modelName?: string;
  modelSelectionMode?: 'FIXED' | 'AUTO';
  routeReasonCode?: string;
};

/** Java 聊天 SSE 的失败数据体，只展示后端已脱敏的用户提示。 */
interface BrowserErrorPayload {
  code?: string;
  error: string;
}

interface BrowserDonePayload {
  done?: boolean;
  messageId?: number;
  modelId?: number;
  modelName?: string;
  modelMode?: 'FIXED' | 'AUTO';
  routeReasonCode?: string;
}

type TypingTarget = 'content' | 'reasoning_content';

const route = useRoute();
const chatStore = useChatStore();
const modelStore = useModelStore();
const agentStore = useAgentStore();
const userStore = useUserStore();

// 用户头像
const avatar = computed(() => {
  const userInfo = userStore.userInfo;
  return userInfo?.avatar || 'https://avatars.githubusercontent.com/u/32251822?s=96&v=4';
});

const inputValue = ref('');
const chatSenderRef = ref<InstanceType<typeof ChatSender> | null>(null);
const bubbleItems = ref<MessageItem[]>([]);
const bubbleListRef = ref<BubbleListInstance | null>(null);
const knowledgeOptions = ref<ChatKnowledgeOptionVO[]>([]);
const knowledgeOptionsLoading = ref(false);

/** 刷新当前用户可选择的已就绪知识库；失败不影响普通聊天。 */
async function loadKnowledgeOptions() {
  knowledgeOptionsLoading.value = true;
  try {
    knowledgeOptions.value = await getChatKnowledgeOptions();
    if (
      chatStore.knowledgeId
      && !knowledgeOptions.value.some(item => String(item.id) === chatStore.knowledgeId)
    ) {
      chatStore.setKnowledgeId('');
    }
  }
  catch {
    knowledgeOptions.value = [];
    chatStore.setKnowledgeId('');
  }
  finally {
    knowledgeOptionsLoading.value = false;
  }
}

/** 统一把选择器值转换为字符串 ID，清空时切回普通聊天。 */
function selectKnowledge(value: string | number | undefined) {
  chatStore.setKnowledgeId(value === undefined ? '' : String(value));
}

/** 直接将上游 SSE 传来的 chunk 整块追加到消息，不做字符级排队。 */
function enqueueTypingText(text: string, target: TypingTarget) {
  const message = bubbleItems.value[bubbleItems.value.length - 1];
  if (!message || !text) {
    return;
  }
  message[target] = `${message[target] ?? ''}${text}`;
  message.typing = true;
  bubbleItems.value = [...bubbleItems.value];
  void nextTick(() => bubbleListRef.value?.scrollToBottom());
}

function stopTypingQueue() {
  const message = bubbleItems.value[bubbleItems.value.length - 1];
  if (message) {
    message.typing = false;
  }
}

function waitForTypingQueue() {
  return Promise.resolve();
}

// 独立的工具调用事件列表
const toolCallEvents = ref<ToolCallInfo[]>([]);
// 工具调用事件计数器（用于生成唯一 key）
let toolCallKeyCounter = 0;

// 是否有工具调用事件
const hasToolCallEvents = computed(() => toolCallEvents.value.length > 0);

const copyIconMap = ref<Record<number, string>>({}); // 记录每条消息的复制按钮图标
const editingMessageKeys = ref<number[]>([]); // 跟踪多个编辑中的消息
const editedContents = ref<Record<number, string>>({}); // 存储每条消息的临时编辑内容

/** 匹配回答正文中的单个或成组引用标记，例如 [C1]、[C1,C2]、【C1】。 */
const INLINE_CITATION_PATTERN
  = /\[\s*C\d+(?:\s*[,，、;；]\s*C\d+)*\s*\]|［\s*C\d+(?:\s*[,，、;；]\s*C\d+)*\s*］|【\s*C\d+(?:\s*[,，、;；]\s*C\d+)*\s*】/gi;
const CITATION_ID_PATTERN = /C\d+/gi;

/**
 * 隐藏回答正文中的引用编号，下方引用来源仍用于查看原文。
 * 只移除当前消息实际存在的引用 ID，避免误删普通正文或代码内容。
 */
function visibleMessageContent(item: MessageItem): string {
  const content = typeof item.content === 'string' ? item.content : '';
  if (!content || item.role !== 'system' || !item.citations?.length) {
    return content;
  }

  const citationIds = new Set(item.citations.map(citation => citation.citationId.trim().toUpperCase()));
  return content
    .replace(INLINE_CITATION_PATTERN, (marker) => {
      const markerIds = marker.match(CITATION_ID_PATTERN)?.map(id => id.toUpperCase()) ?? [];
      return markerIds.length > 0 && markerIds.every(id => citationIds.has(id)) ? '' : marker;
    })
    .replace(/[ \t]+([，。！？；：、,.!?;:])/g, '$1');
}

const {
  stream,
  loading: isLoading,
  cancel,
} = useHookFetch({
  request: send,
  onError: () => {
    ElMessage.error('对话连接失败，请稍后重试');
  },
});

// 组件挂载初始化
onMounted(() => {
  bubbleItems.value.forEach((item) => {
    copyIconMap.value[item.key] = 'CopyDocument';
  });
  void loadKnowledgeOptions();
});

// 记录进入思考中
let isThinking = false;

// 切换会话优先使用本地缓存；URL 指向未加载的会话时再请求历史记录。
watch(
  () => route.params?.id,
  async (_id_) => {
    if (_id_) {
      stopTypingQueue();
      // 切换会话时清空工具调用事件
      toolCallEvents.value = [];
      toolCallKeyCounter = 0;

      if (_id_ !== 'not_login') {
        // 判断的当前会话id是否有聊天记录，有缓存则直接赋值展示
        if (chatStore.chatMap[`${_id_}`] && chatStore.chatMap[`${_id_}`].length) {
          bubbleItems.value = chatStore.chatMap[`${_id_}`] as MessageItem[];
          // 滚动到底部
          setTimeout(() => {
            bubbleListRef.value?.scrollToBottom();
          }, 350);
          return;
        }

        // 无缓存则请求聊天记录
        await chatStore.requestChatList(`${_id_}`);
        // 请求聊天记录后，赋值回显，并滚动到底部
        bubbleItems.value = chatStore.chatMap[`${_id_}`] as MessageItem[];

        // 滚动到底部
        setTimeout(() => {
          bubbleListRef.value?.scrollToBottom();
        }, 350);
      }

      // 新会话跳转前暂存的草稿只发送一次，避免路由再次触发时重复提问。
      const v = localStorage.getItem('chatContent');
      if (v) {
        // 发送消息
        setTimeout(() => {
          startSSE(v);
        }, 350);

        localStorage.removeItem('chatContent');
      }
    }
  },
  { immediate: true, deep: true },
);

// 封装错误处理逻辑
function handleError() {
  ElMessage.error('对话请求失败，请稍后重试');
}

/** 创建本轮气泡并消费流；结束或失败时统一恢复助手消息状态。 */
async function startSSE(chatContent: string) {
  if (!userStore.token) {
    userStore.ensureLogin('/chat', '登录后即可继续当前对话');
    return;
  }
  if (modelStore.modelList.length === 0) {
    await modelStore.requestModelList();
  }
  const modelMode = modelStore.selectionMode;
  const modelId = modelMode === 'FIXED' ? modelStore.currentModelInfo?.id : undefined;
  if (modelStore.modelList.length === 0 || (modelMode === 'FIXED' && !modelId)) {
    ElMessage.warning('当前角色未分配可用的聊天模型，请联系管理员授权');
    return;
  }
  const sessionId = route.params?.id;
  if (!sessionId || sessionId === 'not_login') {
    ElMessage.warning('请先创建会话后再发送消息');
    return;
  }

  try {
    stopTypingQueue();
    // 清空上一次的工具调用事件
    toolCallEvents.value = [];
    toolCallKeyCounter = 0;
    // 添加用户输入的消息
    inputValue.value = '';
    addMessage(chatContent, true);
    addMessage('', false);

    // 这里有必要调用一下 BubbleList 组件的滚动到底部 手动触发 自动滚动
    bubbleListRef.value?.scrollToBottom();

    // 获取最后一条用户消息（后端做了长期记忆缓存，只需发送最新的用户消息）
    const lastUserMessage = bubbleItems.value.filter((item: any) => item.role === 'user').pop();

    // 标记是否收到第一个有效数据 chunk（用于清除 loading 状态）
    let hasReceivedFirstContent = false;

    // 构造发送请求体
    const payload: SendDTO = {
      modelMode,
      modelId,
      agentId: agentStore.currentAgentInfo?.id || undefined,
      chatMode: chatStore.knowledgeId ? 'KNOWLEDGE' : 'GENERAL',
      knowledgeId: chatStore.knowledgeId || undefined,
      content: lastUserMessage?.content ?? '',
      sessionId: String(sessionId),
    };

    for await (const chunk of stream(payload)) {
      // 处理数据块 - chunk.result 可能是字符串或对象
      // 返回 true 表示流结束
      const isStreamEnd = handleDataChunk(chunk.result as AnyObject | string);

      // 在收到第一个有效数据后清除 loading 状态（跳过连接状态事件）
      if (!hasReceivedFirstContent && chunk.result !== ':connected' && chunk.result !== ':disconnected' && !isStreamEnd) {
        const lastMessage = bubbleItems.value[bubbleItems.value.length - 1];
        if (lastMessage) {
          lastMessage.loading = false;
          bubbleItems.value = [...bubbleItems.value];
        }
        hasReceivedFirstContent = true;
      }

      if (isStreamEnd) {
        break; // 提前结束流处理
      }
      // 等待 Vue 更新 DOM，实现真正的流式渲染
      await nextTick();
    }
    await waitForTypingQueue();
  }
  catch {
    stopTypingQueue();
    handleError();
    // 出错时也要清除 loading 状态
    if (bubbleItems.value.length) {
      const lastMessage = bubbleItems.value[bubbleItems.value.length - 1];
      lastMessage.loading = false;
      bubbleItems.value = [...bubbleItems.value];
    }
  }
  finally {
    // 停止打字器状态
    if (bubbleItems.value.length) {
      const lastMessage = bubbleItems.value[bubbleItems.value.length - 1];
      lastMessage.typing = false;
      // 无条件重置 loading（停止打字动画）
      lastMessage.loading = false;
      // 重置思考状态：如果还在思考中，标记为已完成
      if (lastMessage.thinkingStatus === 'thinking') {
        lastMessage.thinkingStatus = 'end';
      }
      // 重置isThinking标志
      isThinking = false;
      bubbleItems.value = [...bubbleItems.value];
    }
  }
}

/** 判断解码后的 SSE 数据是否为 Java 返回的受控失败终态。 */
function isBrowserErrorPayload(value: unknown): value is BrowserErrorPayload {
  return typeof value === 'object'
    && value !== null
    && 'error' in value
    && typeof (value as { error?: unknown }).error === 'string';
}

/** 将失败结果写入占位助手消息，避免用户只看到没有内容的气泡。 */
function showStreamError(payload: BrowserErrorPayload) {
  stopTypingQueue();
  const message = payload.error || '模型调用失败，请稍后重试';
  ElMessage.error(message);
  const lastMessage = bubbleItems.value[bubbleItems.value.length - 1];
  if (!lastMessage) {
    return;
  }
  lastMessage.content = `本次对话未完成：${message}`;
  lastMessage.citations = [];
  lastMessage.loading = false;
  lastMessage.typing = false;
  lastMessage.thinkingStatus = 'end';
  bubbleItems.value = [...bubbleItems.value];
}

function isChatCitation(value: unknown): value is ChatCitationVo {
  return typeof value === 'object'
    && value !== null
    && typeof (value as ChatCitationVo).citationId === 'string'
    && typeof (value as ChatCitationVo).title === 'string'
    && typeof (value as ChatCitationVo).excerpt === 'string';
}

function currentAssistantMessage() {
  return bubbleItems.value[bubbleItems.value.length - 1];
}

// 候选引用可先展示；完成事件中的最终引用列表会覆盖它们。
function appendCandidateCitation(value: ChatCitationVo) {
  const message = currentAssistantMessage();
  if (!message)
    return;
  const values = message.citations ?? [];
  if (!values.some(item => item.citationId === value.citationId)) {
    message.citations = [...values, value];
    bubbleItems.value = [...bubbleItems.value];
  }
}

function replaceFinalCitations(values: ChatCitationVo[]) {
  const message = currentAssistantMessage();
  if (!message)
    return;
  message.citations = values;
  if (values[0]?.messageId)
    message.id = values[0].messageId;
  bubbleItems.value = [...bubbleItems.value];
}

function applyDoneMessageId(value: BrowserDonePayload) {
  const message = currentAssistantMessage();
  if (!message)
    return;
  if (typeof value.messageId === 'number')
    message.id = value.messageId;
  if (typeof value.modelName === 'string')
    message.modelName = value.modelName;
  if (value.modelMode === 'AUTO' || value.modelMode === 'FIXED')
    message.modelSelectionMode = value.modelMode;
  if (typeof value.routeReasonCode === 'string')
    message.routeReasonCode = value.routeReasonCode;
  bubbleItems.value = [...bubbleItems.value];
}

async function openCitation(citation: ChatCitationVo) {
  if (!citation.messageId) {
    ElMessage.info('回答完成后即可查看引用详情');
    return;
  }
  try {
    const value = await getChatCitation(citation.messageId, citation.citationId);
    await ElMessageBox.alert(value.excerpt, value.title, {
      confirmButtonText: '关闭',
      customClass: 'citation-dialog',
    });
  }
  catch {
    ElMessage.error('引用已失效或当前无权访问');
  }
}
/** 同时兼容原始 SSE 文本和 hook-fetch 解码对象；返回 true 表示终止本轮流。 */
function handleDataChunk(chunk: AnyObject | string): boolean {
  try {
    let dataObj: AnyObject | null = null;
    let eventType = '';
    let rawDataStr = '';

    if (typeof chunk === 'string') {
      if (chunk === ':connected' || chunk === ':disconnected') {
        return false;
      }

      const lines = chunk.split('\n');
      for (const line of lines) {
        if (line.startsWith('event:')) {
          eventType = line.substring(6).trim();
        }
        else if (line.startsWith('data:')) {
          rawDataStr = line.substring(5).trim();
          try {
            dataObj = JSON.parse(rawDataStr);
          }
          catch {
            ElMessage.error('聊天响应格式异常，请稍后重试');
          }
        }
      }

      if (dataObj && eventType === 'error' && isBrowserErrorPayload(dataObj)) {
        showStreamError(dataObj);
        return true;
      }
      if (eventType === 'citation' && isChatCitation(dataObj)) {
        appendCandidateCitation(dataObj);
        return false;
      }
      if (eventType === 'citations' && Array.isArray(dataObj)) {
        replaceFinalCitations(dataObj.filter(isChatCitation));
        return false;
      }
      if (eventType === 'done' || dataObj?.done === true) {
        if (dataObj)
          applyDoneMessageId(dataObj);
        return true;
      }

      if (eventType === 'mcp' && dataObj) {
        handleMcpEvent(dataObj);
        return false;
      }

      if (dataObj && eventType === 'content') {
        const content = dataObj.content || '';
        if (content) {
          handleContentChunk(content);
        }
        const reasoningContent = dataObj.reasoning_content || '';
        if (reasoningContent) {
          const lastMessage = bubbleItems.value[bubbleItems.value.length - 1];
          if (lastMessage) {
            lastMessage.thinkingStatus = 'thinking';
            lastMessage.loading = true;
            lastMessage.thinlCollapse = true;
            enqueueTypingText(reasoningContent, 'reasoning_content');
            bubbleItems.value = [...bubbleItems.value];
          }
        }
      }
    }
    else if (typeof chunk === 'object' && chunk !== null) {
      // hook-fetch 可能只保留 data 字段，引用数组与候选引用按结构识别。
      if (Array.isArray(chunk)) {
        replaceFinalCitations(chunk.filter(isChatCitation));
        return false;
      }
      if (isChatCitation(chunk)) {
        appendCandidateCitation(chunk);
        return false;
      }
      if (isBrowserErrorPayload(chunk)) {
        showStreamError(chunk);
        return true;
      }
      if (chunk.done === true) {
        return true;
      }

      const reasoningChunk = chunk?.choices?.[0]?.delta?.reasoning_content;
      if (reasoningChunk) {
        const lastMessage = bubbleItems.value[bubbleItems.value.length - 1];
        if (lastMessage) {
          lastMessage.thinkingStatus = 'thinking';
          lastMessage.loading = true;
          lastMessage.thinlCollapse = true;
          enqueueTypingText(reasoningChunk, 'reasoning_content');
          bubbleItems.value = [...bubbleItems.value];
        }
      }

      const parsedChunk = chunk?.choices?.[0]?.delta?.content;
      if (parsedChunk) {
        handleContentChunk(parsedChunk);
      }

      const directContent = chunk?.content;
      if (directContent) {
        handleContentChunk(directContent);
      }
    }
  }
  catch {
    ElMessage.error('聊天响应解析失败，请稍后重试');
  }

  return false;
}

/** 工具开始/完成事件可能分开发送，按工具名合并尚未完成的卡片。 */
function handleMcpEvent(dataObj: AnyObject) {
  console.log('[SSE] MCP 事件:', dataObj);

  try {
    const content = typeof dataObj.content === 'string'
      ? JSON.parse(dataObj.content)
      : dataObj.content;

    const toolName = content.name || 'Unknown Tool';
    const toolStatus = content.status || 'pending';
    const toolResult = content.result || null;

    if (toolStatus === 'pending') {
      const toolInfo: ToolCallInfo = {
        key: ++toolCallKeyCounter,
        name: toolName,
        status: 'pending',
        result: null,
        timestamp: Date.now(),
      };
      toolCallEvents.value = [...toolCallEvents.value, toolInfo];
    }
    else {
      const index = toolCallEvents.value.findIndex(
        t => t.name === toolName && t.status === 'pending',
      );
      if (index >= 0) {
        const updatedEvents = [...toolCallEvents.value];
        updatedEvents[index] = {
          ...updatedEvents[index],
          status: toolStatus,
          result: toolResult,
          timestamp: Date.now(),
        };
        toolCallEvents.value = updatedEvents;
      }
      else {
        const toolInfo: ToolCallInfo = {
          key: ++toolCallKeyCounter,
          name: toolName,
          status: toolStatus,
          result: toolResult,
          timestamp: Date.now(),
        };
        toolCallEvents.value = [...toolCallEvents.value, toolInfo];
      }
    }

    console.log('[SSE] 工具调用列表:', toolCallEvents.value);
  }
  catch (err) {
    console.error('[SSE] MCP 事件解析失败:', err);
  }
}

/** 识别到 think 标签后跨 chunk 保留思考状态，将正文与思考内容写入不同字段。 */
function handleContentChunk(content: string) {
  const lastIndex = bubbleItems.value.length - 1;
  const lastMessage = bubbleItems.value[lastIndex];
  if (!lastMessage) {
    return;
  }

  let currentText = content;

  if (!isThinking && currentText.includes('<think')) {
    const thinkIdx = currentText.indexOf('<think');
    if (thinkIdx > 0) {
      const beforeThink = currentText.substring(0, thinkIdx);
      enqueueTypingText(beforeThink, 'content');
    }
    currentText = currentText.substring(thinkIdx + 7);
    isThinking = true;
    lastMessage.thinkingStatus = 'thinking';
    lastMessage.loading = true;
    lastMessage.thinlCollapse = true;
  }

  if (isThinking && currentText.includes('</think')) {
    const thinkEndIdx = currentText.indexOf('</think');
    if (thinkEndIdx > 0) {
      const thinkContent = currentText.substring(0, thinkEndIdx);
      enqueueTypingText(thinkContent, 'reasoning_content');
    }
    currentText = currentText.substring(thinkEndIdx + 8);
    isThinking = false;
    lastMessage.thinkingStatus = 'end';
    lastMessage.loading = false;
  }

  if (currentText) {
    if (isThinking) {
      enqueueTypingText(currentText, 'reasoning_content');
    }
    else {
      enqueueTypingText(currentText, 'content');
    }
  }
}

async function cancelSSE() {
  cancel();
  stopTypingQueue();
  if (bubbleItems.value.length) {
    bubbleItems.value[bubbleItems.value.length - 1].typing = false;
  }
}

onUnmounted(() => {
  stopTypingQueue();
});

function copyToClipboard(text: string, key: number) {
  navigator.clipboard
    .writeText(text)
    .then(() => {
      copyIconMap.value[key] = 'Check';
      setTimeout(() => {
        copyIconMap.value[key] = 'CopyDocument';
      }, 2000);
    })
    .catch((err) => {
      console.error('复制失败:', err);
      ElMessage.error('复制失败，请手动复制');
    });
}

function addMessage(message: string, isUser: boolean) {
  const i = bubbleItems.value.length;
  const obj: MessageItem = {
    key: i,
    avatar: isUser
      ? avatar.value
      : 'https://cube.elemecdn.com/0/88/03b0d39583f48206768a7534e55bcpng.png',
    avatarSize: '32px',
    role: isUser ? 'user' : 'system',
    placement: isUser ? 'end' : 'start',
    isMarkdown: !isUser,
    loading: !isUser,
    content: message || '',
    reasoning_content: '',
    thinkingStatus: 'start',
    thinlCollapse: false,
    noStyle: !isUser,
  };
  bubbleItems.value.push(obj);
}

function handleChange(_payload: { value: boolean; status: ThinkingStatus }) {}

function startEditing(item: MessageItem) {
  if (!editingMessageKeys.value.includes(item.key)) {
    editingMessageKeys.value.push(item.key);
    editedContents.value[item.key] = item.content || '';
  }
  item.noStyle = true;
  item.class = 'editing-bubble';
}

function cancelEditingByKey(key: number) {
  const item = bubbleItems.value.find(i => i.key === key);
  if (item) {
    item.noStyle = false;
    item.class = '';
  }
  editingMessageKeys.value = editingMessageKeys.value.filter(k => k !== key);
  delete editedContents.value[key];
}

function sendMessageByKey(key: number) {
  const newContent = editedContents.value[key];
  if (newContent) {
    startSSE(newContent);
    cancelEditingByKey(key);
  }
}
</script>

<template>
  <div class="chat-with-id-container">
    <div class="chat-warp">
      <!-- 工具调用事件区域 -->
      <Transition name="tool-events-fade">
        <div v-if="hasToolCallEvents" class="tool-events-wrapper">
          <ToolCallCard
            v-for="tool in toolCallEvents"
            :key="tool.key"
            :tool-info="tool"
          />
        </div>
      </Transition>

      <BubbleList ref="bubbleListRef" :list="bubbleItems" max-height="calc(100vh - 240px)">
        <template #header="{ item }">
          <Thinking
            v-if="item.reasoning_content"
            v-model="item.thinlCollapse"
            :content="item.reasoning_content"
            :status="item.thinkingStatus"
            class="thinking-chain-warp"
            @change="handleChange"
          />
        </template>

        <template #content="{ item }">
          <XMarkdown
            v-if="item.content && item.role === 'system'"
            :markdown="visibleMessageContent(item)"
            :code-x-render="codeXRender"
            class="markdown-body"
            :themes="{ light: 'github-light', dark: 'github-dark' }"
            default-theme-mode="dark"
          />
          <div v-if="item.role === 'system' && item.modelName" class="model-used-label">
            {{ item.modelSelectionMode === 'AUTO' ? `智能选择：${item.modelName}` : item.modelName }}
          </div>
          <div v-if="item.role === 'system' && item.citations?.length" class="citation-list">
            <span class="citation-label">引用来源：</span>
            <el-button
              v-for="citation in item.citations"
              :key="citation.citationId"
              link
              type="primary"
              class="citation-link"
              @click="openCitation(citation)"
            >
              [{{ citation.citationId }}] {{ citation.title }}
            </el-button>
          </div>
          <div v-if="item.content && item.role === 'user'" class="userContent">
            <div class="user-bubble" :class="{ editing: editingMessageKeys.includes(item.key) }">
              <template v-if="!editingMessageKeys.includes(item.key)">
                <div class="user-content">
                  {{ item.content }}
                </div>
              </template>

              <template v-else>
                <div class="edit-card">
                  <el-input
                    v-model="editedContents[item.key]"
                    type="textarea"
                    autosize
                    class="edit-input"
                  />
                  <div class="edit-actions">
                    <el-button size="small" @click="cancelEditingByKey(item.key)">
                      取消
                    </el-button>
                    <el-button type="primary" size="small" @click="sendMessageByKey(item.key)">
                      发送
                    </el-button>
                  </div>
                </div>
              </template>
            </div>

            <div v-if="!editingMessageKeys.includes(item.key)" class="copy-button-container">
              <el-tooltip content="复制" placement="bottom">
                <el-button
                  class="copy-btn"
                  :icon="copyIconMap[item.key] || 'CopyDocument'"
                  size="small"
                  @click="copyToClipboard(item.content, item.key)"
                />
              </el-tooltip>
              <el-tooltip content="编辑" placement="bottom">
                <el-button class="copy-btn" icon="Edit" size="small" @click="startEditing(item)" />
              </el-tooltip>
            </div>
          </div>
        </template>
      </BubbleList>

      <div class="sender-wrapper">
        <div class="knowledge-selector">
          <el-select
            :model-value="chatStore.knowledgeId"
            :loading="knowledgeOptionsLoading"
            clearable
            filterable
            placeholder="普通聊天（可选择知识库）"
            @update:model-value="selectKnowledge"
          >
            <el-option
              v-for="knowledge in knowledgeOptions"
              :key="String(knowledge.id)"
              :label="knowledge.name"
              :value="String(knowledge.id)"
            />
          </el-select>
          <span>未选择时只使用模型和已授权工具</span>
        </div>
        <ChatSender
          ref="chatSenderRef"
          v-model="inputValue"
          :loading="isLoading"
          @submit="startSSE"
          @cancel="cancelSSE"
        />
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.model-used-label {
  margin-top: 6px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.citation-list {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  margin-top: 8px;
  font-size: 13px;
}

.citation-label {
  color: var(--el-text-color-secondary);
}

.citation-link {
  height: auto;
  padding: 2px 4px;
  white-space: normal;
  text-align: left;
}

.user-bubble.editing {
  background: transparent !important;
  padding: 0;
}

:deep(.editing-bubble.el-bubble) {
  display: flex !important;
  width: 100% !important;
  justify-content: flex-start !important;
}

:deep(.editing-bubble .el-bubble__content) {
  flex: 1 !important;
  max-width: none !important;
  width: 100% !important;
}

.edit-card {
  width: 500px;
  box-sizing: border-box;
  border: 1px solid #dcdfe6;
  border-radius: 16px;
  padding: 12px;
  background: #ffffff;
  transition: all 0.2s ease;
}

.edit-input :deep(.el-textarea__inner) {
  border: none !important;
  box-shadow: none !important;
  background: transparent !important;
  resize: none;
  padding: 0;
  font-size: 14px;
}

.edit-actions {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
  gap: 4px;
}

.copy-button-container {
  position: absolute;
  bottom: -28px;
  right: -10px;
  transform: translateY(10px);
  transition: all 0.3s ease;
  pointer-events: none;
  display: flex;
  justify-content: flex-end;

  .copy-btn {
    width: 24px;
    height: 24px;
    padding: 0;
    font-size: 16px;
    cursor: pointer;
    pointer-events: auto;
    border: none !important;
    color: #91949a;
    :deep(svg) {
      stroke-width: 3 !important;
    }

    &:hover {
      border-radius: 50%;
      transition: background-color 0.2s;
      background-color: #f1efef;
    }
  }
}

.chat-with-id-container {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 100%;
  max-width: 800px;
  height: 100%;

  .chat-warp {
    display: flex;
    flex-direction: column;
    justify-content: space-between;
    width: 100%;
    height: calc(100vh - 60px);

    .thinking-chain-warp {
      margin-bottom: 12px;
    }

    .tool-events-wrapper {
      padding: 12px;
    }

    .tool-events-fade-enter-active,
    .tool-events-fade-leave-active {
      transition: all 0.3s ease;
    }

    .tool-events-fade-enter-from,
    .tool-events-fade-leave-to {
      opacity: 0;
      transform: translateY(-10px);
    }

    .sender-wrapper {
      position: relative;
      width: 100%;
      margin-bottom: 22px;
    }

    .knowledge-selector {
      display: flex;
      align-items: center;
      gap: 10px;
      margin-bottom: 8px;
      color: var(--el-text-color-secondary);
      font-size: 12px;

      .el-select {
        width: 240px;
      }
    }
  }

  :deep() {
    .el-bubble-list {
      padding-top: 24px;
    }
    .el-bubble {
      padding: 0 12px;
      padding-bottom: 24px;
    }
    .el-typewriter {
      overflow: hidden;
      border-radius: 12px;
    }
    .user-content {
      white-space: pre-wrap;
    }
    .markdown-body {
      background-color: transparent;
      width: auto;
      max-width: none;
      overflow: visible;
    }
    .markdown-elxLanguage-header-div {
      top: -25px !important;
    }
    .elx-xmarkdown-container {
      padding: 8px 4px;
      width: 100%;
      overflow: visible;
    }
  }
}
</style>
