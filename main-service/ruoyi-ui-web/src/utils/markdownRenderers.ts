import { h } from 'vue';
import EchartsRenderer from '@/components/EchartsRenderer/index.vue';

/**
 * 将 JavaScript 对象字面量转换为有效的 JSON
 */
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
  cleaned = cleaned.replace(/([{,]\s*)([a-zA-Z_$][a-zA-Z0-9_$]*)\s*:/g, '$1"$2":');

  // 处理单引号字符串，转换为双引号
  cleaned = cleaned.replace(/'([^']*)'/g, '"$1"');

  // 移除末尾的逗号
  cleaned = cleaned.replace(/,(\s*[}\]])/g, '$1');

  return cleaned;
}

/**
 * 检查代码是否是 ECharts 配置
 */
function isEchartsConfig(code: string): boolean {
  try {
    const trimmed = code.trim();

    // 检查是否以 { 开头
    if (!trimmed.startsWith('{')) {
      return false;
    }

    // 尝试转换为 JSON 并解析
    const jsonStr = convertJsObjectToJson(code);
    const obj = JSON.parse(jsonStr);

    // 检查是否包含 ECharts 的关键配置字段
    return !!(
      typeof obj === 'object' &&
      obj !== null &&
      (obj.xAxis || obj.yAxis || obj.series || obj.title || obj.legend || obj.gauge || obj.pie || obj.bar)
    );
  }
  catch {
    return false;
  }
}

/**
 * 渲染 ECharts 图表
 */
function renderEcharts(code: string) {
  console.log('[codeXRender] 渲染 echarts，代码长度:', code?.length || 0);
  return h(EchartsRenderer, {
    selfProps: {
      code: code,
      width: '100%',
      height: '600px',  // 增加高度
      theme: 'dark',
    },
    style: {
      width: '100%',
      maxWidth: '100%'
    }
  });
}

/**
 * 渲染普通代码块
 */
function renderCodeBlock(code: string, language: string) {
  console.log('[codeXRender] 渲染代码块，语言:', language, '长度:', code?.length || 0);
  return h('pre', [
    h('code', {
      class: `language-${language}`,
    }, code),
  ]);
}

/**
 * XMarkdown code block renderer
 * API 格式: { [language]: (props: { raw: { content: string } }) => VNode }
 */
export const codeXRender = {
  echarts: (props: { raw: any }) => {
    const code = props.raw?.content || '';
    console.log('[codeXRender.echarts] 收到代码，长度:', code.length);
    return renderEcharts(code);
  },

  json: (props: { raw: any }) => {
    const code = props.raw?.content || '';
    console.log('[codeXRender.json] 收到代码，长度:', code.length);
    if (isEchartsConfig(code)) {
      return renderEcharts(code);
    }
    return renderCodeBlock(code, 'json');
  },

  javascript: (props: { raw: any }) => {
    const code = props.raw?.content || '';
    console.log('[codeXRender.javascript] 收到代码，长度:', code.length);
    if (isEchartsConfig(code)) {
      return renderEcharts(code);
    }
    return renderCodeBlock(code, 'javascript');
  },

  text: (props: { raw: any }) => {
    const code = props.raw?.content || '';
    if (isEchartsConfig(code)) {
      return renderEcharts(code);
    }
    return renderCodeBlock(code, 'text');
  },

  '': (props: { raw: any }) => {
    const code = props.raw?.content || '';
    if (isEchartsConfig(code)) {
      return renderEcharts(code);
    }
    return renderCodeBlock(code, 'plaintext');
  },
};

export default codeXRender;
