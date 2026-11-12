<script setup lang="ts">
import type { Dayjs } from 'dayjs';
import type { EchartsUIType } from '@vben/plugins/echarts';
import type { VbenFormProps } from '@vben/common-ui';

import type {
  ModelOption,
  TokenStatItem,
  TokenStatistics,
} from '#/api/token/model';

import { computed, nextTick, onMounted, ref } from 'vue';

import { Page } from '@vben/common-ui';
import { EchartsUI, useEcharts } from '@vben/plugins/echarts';

import {
  Button,
  Card,
  Col,
  Empty,
  Row,
  Space,
  Spin,
  Statistic,
  Table,
} from 'ant-design-vue';

import { useVbenForm } from '#/adapter/form';
import { tokenModelOptions, tokenUsageStatistics } from '#/api/token';

import { querySchema } from './data';

const formatter = new Intl.NumberFormat('zh-CN');
const costFormatter = new Intl.NumberFormat('zh-CN', {
  maximumFractionDigits: 6,
  minimumFractionDigits: 2,
});
const loading = ref(false);
const models = ref<ModelOption[]>([]);
const dateRange = ref<[Dayjs, Dayjs]>();
const chartRef = ref<EchartsUIType>();
const { renderEcharts } = useEcharts(chartRef);



const formOptions: VbenFormProps = {
  commonConfig: {
    componentProps: { allowClear: true },
    labelWidth: 80,
  },
  schema: querySchema(),
  wrapperClass: 'grid-cols-1 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5 gap-x-[60px]',
  showCollapseButton: true,
  submitButtonOptions: {
    content: '查询',
  },
  resetButtonOptions: {
    content: '重置',
  },
  handleSubmit: (values) => {
    loadStatistics(values);
  },
  handleReset: () => {
    dateRange.value = undefined;
    loadStatistics({});
  },
};

const [BasicForm, formApi] = useVbenForm(formOptions);

// 日期范围按整天查询；接口结果先转成有限数值，再供卡片与图表共用。
async function loadStatistics(values?: Record<string, any>) {
  loading.value = true;
  try {
    const formValues = values || formApi.getValues();
    const result = await tokenUsageStatistics({
      beginTime: dateRange.value?.[0].format('YYYY-MM-DD 00:00:00'),
      endTime: dateRange.value?.[1].format('YYYY-MM-DD 23:59:59'),
      modelId: formValues?.modelId,
      providerCode: formValues?.providerCode?.trim() || undefined,
      userName: formValues?.userName?.trim() || undefined,
    });
    statistics.value = normalizeStatistics(result);
    // 等图表容器完成本轮渲染后再初始化趋势图。
    await nextTick();
    if (statistics.value.dailyTrend.length > 0) renderTrend();
  } finally {
    loading.value = false;
  }
}

const statistics = ref<TokenStatistics>({
  actualTokens: 0,
  chargedTokens: 0,
  dailyTrend: [],
  estimatedCost: 0,
  failedCalls: 0,
  inputTokens: 0,
  modelRanking: [],
  outputTokens: 0,
  successCalls: 0,
  totalCalls: 0,
  userRanking: [],
});

const hasUsageData = computed(() => statistics.value.totalCalls > 0);
const successRate = computed(() => {
  const total = safeNumber(statistics.value.totalCalls);
  const success = safeNumber(statistics.value.successCalls);
  if (total <= 0) return 0;
  return Number(((success / total) * 100).toFixed(2));
});

const rankingColumns = [
  { key: 'sequence', title: '序号', width: 70, customRender: ({ index }: { index: number }) => index + 1 },
  { dataIndex: 'name', key: 'name', title: '名称' },
  { dataIndex: 'calls', key: 'calls', title: '调用次数', width: 110 },
  {
    dataIndex: 'actualTokens',
    key: 'actualTokens',
    title: '实际Token',
    width: 130,
  },
  {
    dataIndex: 'chargedTokens',
    key: 'chargedTokens',
    title: '扣减Token',
    width: 130,
  },
];

function renderTrend() {
  const rows = statistics.value.dailyTrend || [];
  renderEcharts({
    grid: { bottom: 12, containLabel: true, left: 24, right: 24, top: 48 },
    legend: { data: ['实际 Token', '扣减 Token'], top: 4 },
    series: [
      {
        areaStyle: { opacity: 0.16 },
        data: rows.map((item) => item.actualTokens),
        emphasis: { focus: 'series' },
        name: '实际 Token',
        showSymbol: true,
        smooth: true,
        symbolSize: 7,
        type: 'line',
      },
      {
        areaStyle: { opacity: 0.1 },
        data: rows.map((item) => item.chargedTokens),
        emphasis: { focus: 'series' },
        name: '扣减 Token',
        showSymbol: true,
        smooth: true,
        symbolSize: 7,
        type: 'line',
      },
    ],
    tooltip: {
      trigger: 'axis',
      valueFormatter: (value) => `${formatNumber(safeNumber(value))} Token`,
    },
    xAxis: {
      axisLabel: { hideOverlap: true },
      boundaryGap: false,
      data: rows.map((item) => item.name || item.key),
      type: 'category',
    },
    yAxis: {
      axisLabel: { formatter: (value: number) => formatter.format(value) },
      min: 0,
      splitLine: { lineStyle: { type: 'dashed' } },
      type: 'value',
    },
  });
}



function formatNumber(value?: number) {
  return formatter.format(safeNumber(value));
}

function formatCost(value: unknown) {
  return costFormatter.format(safeNumber(value));
}

function safeNumber(value: unknown) {
  const result = Number(value ?? 0);
  return Number.isFinite(result) ? result : 0;
}

function normalizeStatItems(items: TokenStatItem[] | null | undefined) {
  return (items || []).map((item) => ({
    ...item,
    actualTokens: safeNumber(item.actualTokens),
    calls: safeNumber(item.calls),
    chargedTokens: safeNumber(item.chargedTokens),
  }));
}

/** 统一兜底缺失、空值和非有限数值，避免统计卡片与图表出现 NaN。 */
function normalizeStatistics(result: TokenStatistics): TokenStatistics {
  return {
    actualTokens: safeNumber(result?.actualTokens),
    chargedTokens: safeNumber(result?.chargedTokens),
    dailyTrend: normalizeStatItems(result?.dailyTrend),
    estimatedCost: safeNumber(result?.estimatedCost),
    failedCalls: safeNumber(result?.failedCalls),
    inputTokens: safeNumber(result?.inputTokens),
    modelRanking: normalizeStatItems(result?.modelRanking),
    outputTokens: safeNumber(result?.outputTokens),
    successCalls: safeNumber(result?.successCalls),
    totalCalls: safeNumber(result?.totalCalls),
    userRanking: normalizeStatItems(result?.userRanking),
  };
}

function isNumberRankingColumn(dataIndex: unknown) {
  return (
    typeof dataIndex === 'string' &&
    ['calls', 'actualTokens', 'chargedTokens'].includes(dataIndex)
  );
}

function getRankingNumber(record: object, dataIndex: unknown) {
  if (typeof dataIndex !== 'string') return 0;
  return Number((record as Record<string, unknown>)[dataIndex] || 0);
}

onMounted(async () => {
  models.value = await tokenModelOptions();
  const modelOptions = models.value.map((model) => ({
    label: `${model.modelName}{model.providerCode || '未配置厂'}）`,
    value: model.modelId,
  }));
  formApi.updateSchema([
    {
      fieldName: 'modelId',
      componentProps: {
        options: modelOptions,
      },
    },
  ]);
  await loadStatistics();
});
</script>

<template>
  <Page :auto-content-height="true">
    <Spin :spinning="loading">
      <Card class="mb-4" :bordered="false">
        <BasicForm />
      </Card>

      <Row :gutter="16">
        <Col :lg="6" :sm="12" :xs="24">
          <Card class="mb-4 h-[142px]" :bordered="false">
            <Statistic title="调用次数" :value="statistics.totalCalls" />
            <div class="mt-2 text-sm text-gray-500">
              成功 {{ formatNumber(statistics.successCalls) }}，失败{{ formatNumber(statistics.failedCalls) }}
            </div>
          </Card>
        </Col>
        <Col :lg="6" :sm="12" :xs="24">
          <Card class="mb-4 h-[142px]" :bordered="false">
            <Statistic title="实际 Token" :value="statistics.actualTokens" group-separator="," />
            <div class="mt-2 text-sm text-gray-500">
              输入 {{ formatNumber(statistics.inputTokens) }}，输出{{ formatNumber(statistics.outputTokens) }}
            </div>
          </Card>
        </Col>
        <Col :lg="6" :sm="12" :xs="24">
          <Card class="mb-4 h-[142px]" :bordered="false">
            <Statistic title="扣减 Token" :value="statistics.chargedTokens" group-separator="," />
            <div class="mt-2 text-sm text-gray-500">所有模型按权重计算后的额度</div>
          </Card>
        </Col>
        <Col :lg="6" :sm="12" :xs="24">
          <Card class="mb-4 h-[142px]" :bordered="false">
            <Statistic
              title="调用成功"
              :precision="2"
              suffix="%"
              :value="successRate"
              :value-style="{ color: hasUsageData ? '#1677ff' : '#8c8c8c' }"
            />
            <div class="mt-2 text-sm text-gray-500">
              预估成本 {{ formatCost(statistics.estimatedCost) }}
            </div>
          </Card>
        </Col>
      </Row>

      <Card class="mb-4" :bordered="false" title="每日 Token 趋势">
        <Empty v-if="statistics.dailyTrend.length === 0" description="暂无用量数据" />
        <EchartsUI v-show="statistics.dailyTrend.length > 0" ref="chartRef" height="360px" />
      </Card>

      <Row :gutter="16">
        <Col :lg="12" :xs="24">
          <Card class="mb-4" :bordered="false" title="模型用量排行">
            <Table
              :columns="rankingColumns"
              :data-source="statistics.modelRanking"
              :pagination="false"
              row-key="key"
              size="middle"
            >
              <template #bodyCell="{ column, record }">
                <template v-if="isNumberRankingColumn(column.dataIndex)">
                  {{ formatNumber(getRankingNumber(record, column.dataIndex)) }}
                </template>
              </template>
            </Table>
          </Card>
        </Col>
        <Col :lg="12" :xs="24">
          <Card class="mb-4" :bordered="false" title="员工用量排行">
            <Table
              :columns="rankingColumns"
              :data-source="statistics.userRanking"
              :pagination="false"
              row-key="key"
              size="middle"
            >
              <template #bodyCell="{ column, record }">
                <template v-if="isNumberRankingColumn(column.dataIndex)">
                  {{ formatNumber(getRankingNumber(record, column.dataIndex)) }}
                </template>
              </template>
            </Table>
          </Card>
        </Col>
      </Row>
    </Spin>
  </Page>
</template>
