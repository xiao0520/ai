<script setup lang="ts">
import * as echarts from 'echarts';
import { nextTick, onMounted, onUnmounted, ref, watch } from 'vue';

// 通过 selfProps 获取所有自定义属性
const props = defineProps({
  selfProps: {
    type: Object,
    default: () => ({}),
  },
});

const refEle = ref<HTMLElement>();
let myChart: echarts.ECharts | null = null;
let retryCount = 0;
const maxRetries = 3;

// 使用计算属性保持响应性
const code = () => props.selfProps?.code ?? '';
const width = () => props.selfProps?.width ?? '100%';
const height = () => props.selfProps?.height ?? '400px';
const theme = () => props.selfProps?.theme ?? 'dark';
const title = () => props.selfProps?.title ?? '';

// 提取函数字符串并替换为占位符
function extractFunctions(jsCode: string): { code: string; functions: Map<string, string> } {
  const functions = new Map<string, string>();
  let counter = 0;

  // 匹配 function(...)  {...} 或 (params) => {...} 的模式
  // eslint-disable-next-line regexp/no-super-linear-backtracking
  const functionPattern = /:\s*(function\s*\([^)]*\)\s*\{(?:[^{}]|\{[^{}]*\})*\}|(?:[^=,{}\n]|\{[^{}]*\})*=>\s*\{(?:[^{}]|\{[^{}]*\})*\}|(?:[^=,{}\n]|\{[^{}]*\})*=>\s*[^,}\n]*(?=[,}\]]|$))/g;

  let code = jsCode;
  // 先处理标准的 function(params) { ... } 格式
  for (const match of jsCode.matchAll(functionPattern)) {
    const placeholder = `__FUNCTION_${counter}__`;
    const functionStr = match[1];
    functions.set(placeholder, functionStr);
    code = code.replace(functionStr, `"${placeholder}"`);
    counter++;
  }

  return { code, functions };
}

// 恢复函数
function reconstructFunctions(obj: any, functions: Map<string, string>): any {
  if (obj === null || obj === undefined) {
    return obj;
  }

  if (typeof obj === 'string') {
    const functionStr = functions.get(obj);
    if (functionStr) {
      try {
        // 提取函数体和参数
        const match = functionStr.match(/function\s*\(([^)]*)\)\s*\{([\s\S]*)\}/)
          || functionStr.match(/\(([^)]*)\)\s*=>\s*\{([\s\S]*)\}/);
        if (match) {
          const [, params, body] = match;
          // eslint-disable-next-line no-new-func
          return new Function(params, body);
        }
      }
      catch (e) {
        console.warn('函数解析失败:', functionStr, e);
      }
    }
    return obj;
  }

  if (Array.isArray(obj)) {
    return obj.map(item => reconstructFunctions(item, functions));
  }

  if (typeof obj === 'object') {
    const result: any = {};
    for (const key in obj) {
      result[key] = reconstructFunctions(obj[key], functions);
    }
    return result;
  }

  return obj;
}

// 将 JavaScript 对象字面量转换为有效的 JSON
function convertJsObjectToJson(jsCode: string): string {
  let cleaned = jsCode.trim();

  // 如果已经是标准 JSON，直接返回
  try {
    JSON.parse(cleaned);
    return cleaned;
  }
  catch {
    // 继续处理
  }

  // 为无引号的键添加引号：key: value -> "key": value
  cleaned = cleaned.replace(/([{,]\s*)([a-z_$][\w$]*)\s*:/gi, '$1"$2":');

  // 处理单引号字符串，转换为双引号
  cleaned = cleaned.replace(/'([^']*)'/g, '"$1"');

  // 移除末尾的逗号
  cleaned = cleaned.replace(/,(\s*[}\]])/g, '$1');

  return cleaned;
}

// 解析ECharts配置
function parseEChartsOption(data: string | object) {
  try {
    // 如果已经是对象，直接返回
    if (typeof data === 'object' && data !== null) {
      return data;
    }

    // 如果是字符串，进行解析
    if (typeof data === 'string') {
      let cleanedStr = data.trim();

      // 处理 markdown 代码块格式：```echarts ... ``` 或 ```json ... ```

      const codeBlockMatch = cleanedStr.match(/^```(?:[^`]|`(?!``))*```\s*$/);
      if (codeBlockMatch) {
        cleanedStr = codeBlockMatch[1].trim();
      }

      // 提取函数
      const { code: codeWithoutFunctions, functions } = extractFunctions(cleanedStr);

      // 尝试直接解析
      try {
        const parsed = JSON.parse(codeWithoutFunctions);
        return reconstructFunctions(parsed, functions);
      }
      catch {
        // 尝试转换 JavaScript 对象字面量为 JSON
        try {
          const jsonStr = convertJsObjectToJson(codeWithoutFunctions);
          const parsed = JSON.parse(jsonStr);
          return reconstructFunctions(parsed, functions);
        }
        catch {
          // 最后再尝试其他修复方式
          const fixedStr = codeWithoutFunctions
            .replace(/'/g, '"') // 单引号转双引号
            .replace(/(\w+)\s*:/g, '"$1":') // 无引号的键添加引号
            .replace(/,\s*\}/g, '}') // 移除末尾逗号
            .replace(/,\s*\]/g, ']'); // 移除数组末尾逗号

          const parsed = JSON.parse(fixedStr);
          return reconstructFunctions(parsed, functions);
        }
      }
    }

    return null;
  }
  catch (error) {
    console.error('ECharts配置解析失败:', error, data);
    return null;
  }
}

// 渲染图表
function renderChart() {
  if (!refEle.value) {
    console.log('[EchartsRenderer] refEle 不存在');
    return;
  }

  // 检查 DOM 是否有实际尺寸
  if (refEle.value.clientWidth === 0 || refEle.value.clientHeight === 0) {
    if (retryCount < maxRetries) {
      retryCount++;
      console.warn(`[EchartsRenderer] 容器尺寸为 0 (${refEle.value.clientWidth} x ${refEle.value.clientHeight})，重试 ${retryCount}/${maxRetries}`);
      setTimeout(() => {
        renderChart();
      }, 100);
    }
    else {
      console.error('[EchartsRenderer] 容器无法获取有效尺寸，放弃');
      if (refEle.value) {
        refEle.value.innerHTML = '<div style="color: #999; padding: 20px;">容器尺寸无效 (0x0)</div>';
      }
    }
    return;
  }

  // 重置重试计数
  retryCount = 0;

  const configData = code();
  console.log('[EchartsRenderer] 配置数据:', configData?.substring?.(0, 100) || 'empty');

  if (!configData) {
    console.warn('[EchartsRenderer] 配置数据为空');
    return;
  }

  const option = parseEChartsOption(configData);
  console.log('[EchartsRenderer] 解析结果:', option ? '成功' : '失败');

  if (!option) {
    refEle.value.innerHTML = '<div style="color: red; padding: 20px;">配置解析失败</div>';
    return;
  }

  try {
    console.log('[EchartsRenderer] 初始化 ECharts...');
    if (!myChart) {
      myChart = echarts.init(refEle.value, theme());
      console.log('[EchartsRenderer] ECharts 初始化完成');
    }
    myChart.setOption(option);
    console.log('[EchartsRenderer] 配置设置成功');
  }
  catch (error) {
    console.error('[EchartsRenderer] 图表渲染失败:', error);
    refEle.value.innerHTML = `<div style="color: red; padding: 20px;">渲染失败: ${error}</div>`;
  }
}

// 窗口resize处理
function handleResize() {
  myChart?.resize();
}

// 监听容器大小变化
const resizeObserver = new ResizeObserver(() => {
  myChart?.resize();
});

// 生命周期
onMounted(() => {
  // 重置重试计数
  retryCount = 0;

  // 使用 nextTick 确保 DOM 已经完全渲染
  nextTick(() => {
    // 再加一个小延迟，确保样式已应用且容器有实际尺寸
    setTimeout(() => {
      renderChart();
      if (refEle.value) {
        resizeObserver.observe(refEle.value);
      }
      window.addEventListener('resize', handleResize);
    }, 50);
  });
});

// 监听props变化
watch(
  () => props.selfProps,
  () => {
    // 延迟执行，确保 DOM 已经准备好
    nextTick(() => {
      setTimeout(() => {
        renderChart();
      }, 50);
    });
  },
  { deep: true },
);

// 清理资源
onUnmounted(() => {
  resizeObserver.disconnect();
  window.removeEventListener('resize', handleResize);
  myChart?.dispose();
  myChart = null;
});
</script>

<template>
  <div class="echarts-container">
    <!-- 副标题（可选） -->
    <div v-if="title()" class="echarts-title">
      {{ title() }}
    </div>
    <!-- 图表容器 -->
    <div
      ref="refEle"
      :style="{
        width: width(),
        height: height(),
        minWidth: '800px',
        minHeight: '300px',
        borderRadius: '8px',
        overflow: 'hidden',
        backgroundColor: theme() === 'light' ? '#fff' : '#1a1a1a',
        boxSizing: 'border-box',
      }"
    />
  </div>
</template>

<style scoped lang="scss">
.echarts-container {
  display: block;
  width: 100%;
  overflow: visible;
  box-sizing: border-box;
  transform: scale(0.62);
  transform-origin: top left;
  margin-bottom: -228px;

  .echarts-title {
    font-size: 14px;
    color: rgba(0, 0, 0, 0.6);
    font-weight: 500;
    margin-bottom: 8px;
  }
}
</style>
