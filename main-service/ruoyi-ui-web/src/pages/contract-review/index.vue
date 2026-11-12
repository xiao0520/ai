<script setup lang="ts">
import type { UploadFile, UploadFiles } from 'element-plus';
import type {
  ContractOperation,
  ContractReviewDetail,
  ContractReviewResult,
  ContractType,
  PartyPosition,
  RiskSeverity,
} from '@/api/contract-review/types';
import { useHookFetch } from 'hook-fetch/vue';
import { useRoute, useRouter } from 'vue-router';
import { createContractReview, getContractReview } from '@/api/contract-review';
import { useAgentStore } from '@/stores/modules/agent';

interface ProgressPayload {
  stage: string;
  progress: number;
  message: string;
}

interface TaskPayload {
  taskId: string;
  status: 'RUNNING' | 'COMPLETED';
}

interface ErrorPayload {
  code: string;
  message: string;
}

const route = useRoute();
const router = useRouter();
const agentStore = useAgentStore();
const selectedFile = ref<File>();
const fileList = ref<UploadFile[]>([]);
const contractType = ref<ContractType>('SERVICE');
const partyPosition = ref<PartyPosition>('PARTY_A');
const operation = ref<ContractOperation>('REVIEW');
const progress = ref(0);
const progressMessage = ref('');
const detail = ref<ContractReviewDetail>();
const detailLoading = ref(false);
const taskId = ref('');
const errorMessage = ref('');

const { stream, loading, cancel } = useHookFetch({
  request: createContractReview,
  onError: () => { errorMessage.value = '合同处理连接失败，请稍后重试'; },
});

const result = computed<ContractReviewResult | undefined>(() => detail.value?.result);
const isExtraction = computed(() => operation.value === 'EXTRACT');
const operationText = computed(() => isExtraction.value ? '信息提取' : '智能审查');

onMounted(async () => {
  await ensureContractAgent();
  const value = String(route.params.taskId || '');
  if (/^[1-9]\d{0,18}$/.test(value)) {
    taskId.value = value;
    await loadDetail(value);
  }
});

onUnmounted(cancel);

async function ensureContractAgent() {
  if (agentStore.currentAgentInfo.agentCode === 'contract-review') {
    return;
  }
  if (agentStore.agentList.length === 0) {
    await agentStore.requestAgentList();
  }
  const agent = agentStore.agentList.find(item => item.agentCode === 'contract-review');
  if (agent) {
    agentStore.setCurrentAgentInfo(agent);
  }
}

function selectFile(file: UploadFile, files: UploadFiles) {
  selectedFile.value = file.raw;
  fileList.value = files.slice(-1);
}

function removeFile() {
  selectedFile.value = undefined;
  fileList.value = [];
}

async function startReview() {
  if (loading.value)
    return;
  const agentId = agentStore.currentAgentInfo.id;
  if (!agentId || agentStore.currentAgentInfo.agentCode !== 'contract-review') {
    ElMessage.warning('当前没有可用的合同智能体，请联系管理员配置');
    return;
  }
  if (!selectedFile.value) {
    ElMessage.warning('请先选择合同文件');
    return;
  }

  detail.value = undefined;
  taskId.value = '';
  errorMessage.value = '';
  progress.value = 5;
  progressMessage.value = `正在创建${operationText.value}任务`;
  try {
    for await (const chunk of stream({
      file: selectedFile.value,
      agentId,
      contractType: contractType.value,
      partyPosition: partyPosition.value,
      operation: operation.value,
    })) {
      if (await handleStreamPayload(chunk.result))
        break;
    }
  }
  catch {
    progress.value = 0;
    errorMessage.value ||= '合同处理连接中断，请刷新任务状态或稍后重试';
  }
}

async function handleStreamPayload(value: unknown): Promise<boolean> {
  if (!isObject(value)) {
    return false;
  }
  // 流式接口在创建任务前也可能返回统一业务错误 JSON。
  if (typeof value.code === 'number' && value.code !== 200) {
    errorMessage.value = typeof value.msg === 'string' ? value.msg : '合同审查请求失败';
    return true;
  }
  if (isErrorPayload(value)) {
    progress.value = 0;
    errorMessage.value = value.message;
    return true;
  }
  if (isProgressPayload(value)) {
    progress.value = value.progress;
    progressMessage.value = value.message;
    return false;
  }
  if (isTaskPayload(value)) {
    taskId.value = value.taskId;
    await router.replace({ name: 'contractReview', params: { taskId: value.taskId } });
    if (value.status === 'COMPLETED') {
      progress.value = 100;
      progressMessage.value = '处理完成';
      await loadDetail(value.taskId);
      return true;
    }
  }
  return false;
}

async function loadDetail(id: string) {
  detailLoading.value = true;
  try {
    const value = await getContractReview(id);
    if (!value) {
      throw new Error('EMPTY_RESPONSE');
    }
    detail.value = value;
    operation.value = value.operation || 'REVIEW';
    errorMessage.value = value.errorMessage || '';
    if (value.status === 'RUNNING') {
      progress.value = Math.max(progress.value, 5);
      progressMessage.value = '任务仍在执行中';
    }
  }
  catch {
    errorMessage.value = '处理结果暂时无法读取，请稍后刷新';
  }
  finally {
    detailLoading.value = false;
  }
}

function resetReview() {
  detail.value = undefined;
  taskId.value = '';
  errorMessage.value = '';
  progress.value = 0;
  progressMessage.value = '';
  removeFile();
  void router.replace({ name: 'contractReview' });
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function isProgressPayload(value: Record<string, unknown>): value is Record<string, unknown> & ProgressPayload {
  return typeof value.stage === 'string'
    && typeof value.progress === 'number'
    && typeof value.message === 'string';
}

function isTaskPayload(value: Record<string, unknown>): value is Record<string, unknown> & TaskPayload {
  return typeof value.taskId === 'string'
    && (value.status === 'RUNNING' || value.status === 'COMPLETED');
}

function isErrorPayload(value: Record<string, unknown>): value is Record<string, unknown> & ErrorPayload {
  return typeof value.code === 'string' && typeof value.message === 'string';
}

function severityType(severity: RiskSeverity) {
  return severity === 'HIGH' ? 'danger' : severity === 'MEDIUM' ? 'warning' : 'info';
}

function severityText(severity: RiskSeverity) {
  return { HIGH: '高风险', MEDIUM: '中风险', LOW: '低风险' }[severity];
}

function categoryText(category: string) {
  const labels: Record<string, string> = {
    PAYMENT: '付款',
    DELIVERY_ACCEPTANCE: '交付验收',
    BREACH_LIABILITY: '违约责任',
    TERMINATION: '解除终止',
    CONFIDENTIALITY: '保密',
    DISPUTE_RESOLUTION: '争议解决',
    MISSING_CLAUSE: '条款缺失',
    CONFLICT: '条款冲突',
  };
  return labels[category] || category;
}

function locationText(location: { pageNumber?: number; clauseNumber?: string; blockIndex: number }) {
  if (location.pageNumber) {
    return `第 ${location.pageNumber} 页${location.clauseNumber ? ` · ${location.clauseNumber}` : ''}`;
  }
  return location.clauseNumber || `文档块 ${location.blockIndex + 1}`;
}
</script>

<template>
  <div v-loading="detailLoading" class="contract-page">
    <div class="page-header">
      <div>
        <h2>合同智能体 · {{ operationText }}</h2>
        <p>{{ isExtraction ? '提取合同主体、金额、期限和付款条件，不执行风险审查。' : '提取关键信息并结合已授权知识库识别风险。' }}</p>
      </div>
      <el-button v-if="detail && !loading" @click="resetReview">
        处理新合同
      </el-button>
    </div>

    <el-card v-if="!detail || detail.status !== 'COMPLETED'" shadow="never" class="form-card">
      <el-form label-position="top" :disabled="loading">
        <el-form-item label="处理方式">
          <el-radio-group v-model="operation" :disabled="loading || detail?.status === 'RUNNING'" @change="removeFile">
            <el-radio-button value="REVIEW">
              智能审查
            </el-radio-button>
            <el-radio-button value="EXTRACT">
              信息提取
            </el-radio-button>
          </el-radio-group>
        </el-form-item>
        <div v-if="!isExtraction" class="form-grid">
          <el-form-item label="合同类型">
            <el-select v-model="contractType">
              <el-option label="采购合同" value="PURCHASE" />
              <el-option label="销售合同" value="SALES" />
              <el-option label="服务合同" value="SERVICE" />
              <el-option label="其他合同" value="OTHER" />
            </el-select>
          </el-form-item>
          <el-form-item label="我方立场">
            <el-radio-group v-model="partyPosition">
              <el-radio-button value="PARTY_A">
                甲方
              </el-radio-button>
              <el-radio-button value="PARTY_B">
                乙方
              </el-radio-button>
              <el-radio-button value="NEUTRAL">
                中立
              </el-radio-button>
            </el-radio-group>
          </el-form-item>
        </div>
        <el-form-item label="合同文件">
          <el-upload
            v-model:file-list="fileList"
            drag
            :auto-upload="false"
            :limit="1"
            :accept="isExtraction ? '.pdf,.docx,.png,.jpg,.jpeg' : '.pdf,.docx'"
            :on-change="selectFile"
            :on-remove="removeFile"
          >
            <el-icon class="upload-icon">
              <UploadFilled />
            </el-icon>
            <div>拖入文件，或点击选择</div>
            <template #tip>
              {{ isExtraction ? '支持 PDF、DOCX、PNG、JPEG；扫描件使用视觉模型并扣费，一次处理一份合同（最多 20 张扫描页/图片）' : '支持文本型 PDF、DOCX，一次审查一份合同' }}
            </template>
          </el-upload>
        </el-form-item>
        <el-button type="primary" :loading="loading" @click="startReview">
          开始{{ operationText }}
        </el-button>
      </el-form>
      <el-button v-if="taskId && !loading" class="refresh-button" @click="loadDetail(taskId)">
        刷新任务状态
      </el-button>
      <div v-if="loading || progress > 0" class="progress-box">
        <el-progress :percentage="progress" :status="progress === 100 ? 'success' : undefined" />
        <span>{{ progressMessage }}</span>
      </div>
      <el-alert
        v-if="errorMessage"
        type="error"
        :title="errorMessage"
        :closable="false"
        show-icon
      />
    </el-card>

    <template v-if="result">
      <div v-if="!isExtraction" class="summary-grid">
        <el-card shadow="never">
          <strong class="danger">{{ result.summary.high }}</strong><span>高风险</span>
        </el-card>
        <el-card shadow="never">
          <strong class="warning">{{ result.summary.medium }}</strong><span>中风险</span>
        </el-card>
        <el-card shadow="never">
          <strong>{{ result.summary.low }}</strong><span>低风险</span>
        </el-card>
      </div>

      <el-card shadow="never" class="result-card">
        <template #header>
          <strong>合同关键信息</strong>
        </template>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="合同名称">
            {{ result.information.title || '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="合同金额">
            {{ [result.information.amount, result.information.currency].filter(Boolean).join(' ') || '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="甲方">
            {{ result.information.partyA || '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="乙方">
            {{ result.information.partyB || '-' }}
          </el-descriptions-item>
          <el-descriptions-item v-if="result.information.otherParties.length" label="其他主体" :span="2">
            {{ result.information.otherParties.join('、') }}
          </el-descriptions-item>
          <el-descriptions-item label="生效日期">
            {{ result.information.effectiveDate || '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="合同期限">
            {{ result.information.term || [result.information.startDate, result.information.endDate].filter(Boolean).join(' 至 ') || '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="付款约定" :span="2">
            {{ result.information.paymentTerms || '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="交付与验收" :span="2">
            {{ result.information.deliveryAndAcceptance || '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="违约责任" :span="2">
            {{ result.information.breachLiability || '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="解除终止" :span="2">
            {{ result.information.terminationTerms || '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="争议解决" :span="2">
            {{ result.information.disputeResolution || '-' }}
          </el-descriptions-item>
        </el-descriptions>
      </el-card>

      <el-card v-if="!isExtraction" shadow="never" class="result-card">
        <template #header>
          <strong>风险项（{{ result.findings.length }}）</strong>
        </template>
        <el-empty v-if="result.findings.length === 0" description="未发现可定位的风险项" />
        <div v-for="finding in result.findings" :key="finding.findingId" class="finding-card">
          <div class="finding-title">
            <el-tag :type="severityType(finding.severity)">
              {{ severityText(finding.severity) }}
            </el-tag>
            <el-tag type="info" effect="plain">
              {{ categoryText(finding.category) }}
            </el-tag>
            <strong>{{ finding.title }}</strong>
            <span>{{ locationText(finding.location) }}</span>
          </div>
          <p>{{ finding.description }}</p>
          <blockquote>{{ finding.quote }}</blockquote>
          <div class="finding-section">
            <b>修改建议：</b>{{ finding.suggestion }}
          </div>
          <div class="finding-section">
            <b>审查依据：</b>{{ finding.basis }}
          </div>
          <el-collapse v-if="finding.evidences.length">
            <el-collapse-item :title="`知识依据（${finding.evidences.length}）`">
              <div v-for="evidence in finding.evidences" :key="evidence.evidenceId" class="evidence">
                <strong>{{ evidence.sourceTitle }}</strong>
                <p>{{ evidence.quote }}</p>
              </div>
            </el-collapse-item>
          </el-collapse>
        </div>
      </el-card>

      <el-alert
        v-if="result.warnings.length"
        type="warning"
        title="处理提示"
        :description="result.warnings.join('；')"
        :closable="false"
        show-icon
      />
    </template>
  </div>
</template>

<style scoped lang="scss">
.contract-page {
  box-sizing: border-box;
  width: 100%;
  height: 100%;
  padding: 20px 28px 40px;
  overflow-y: auto;
}
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 18px;
  h2 {
    margin: 0 0 6px;
  }
  p {
    margin: 0;
    color: var(--el-text-color-secondary);
  }
}
.form-card,
.result-card {
  margin-bottom: 16px;
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px;
}
.upload-icon {
  margin-bottom: 8px;
  font-size: 40px;
  color: var(--el-color-primary);
}
.progress-box {
  display: grid;
  gap: 6px;
  margin-top: 18px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.summary-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  margin-bottom: 16px;
  :deep(.el-card__body) {
    display: flex;
    gap: 8px;
    align-items: baseline;
  }
  strong {
    font-size: 28px;
  }
  .danger {
    color: var(--el-color-danger);
  }
  .warning {
    color: var(--el-color-warning);
  }
}
.finding-card {
  padding: 16px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
  &:first-child {
    padding-top: 0;
  }
  &:last-child {
    border-bottom: 0;
  }
  p {
    line-height: 1.7;
    overflow-wrap: anywhere;
  }
  blockquote {
    padding: 10px 14px;
    margin: 12px 0;
    color: var(--el-text-color-secondary);
    overflow-wrap: anywhere;
    white-space: pre-wrap;
    background: var(--el-fill-color-light);
    border-left: 3px solid var(--el-color-primary);
  }
}
.finding-title {
  display: flex;
  gap: 10px;
  align-items: center;
  span:last-child {
    margin-left: auto;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }
}
.finding-section {
  margin: 8px 0;
  line-height: 1.7;
  overflow-wrap: anywhere;
}
.evidence {
  padding: 8px 0;
}

@media (width <= 760px) {
  .contract-page {
    padding: 16px;
  }
  .form-grid,
  .summary-grid {
    grid-template-columns: 1fr;
  }
  .finding-title {
    flex-wrap: wrap;
  }
  .finding-title span:last-child {
    width: 100%;
    margin-left: 0;
  }
}
</style>
