<script setup lang="ts">
import type { VbenFormProps } from '@vben/common-ui';

import type { VxeGridProps } from '#/adapter/vxe-table';
import type {
  ModelOption,
  TokenAccount,
  TokenAccountCommand,
  TokenUserOption,
} from '#/api/token/model';

import { computed, reactive, ref } from 'vue';

import { Page } from '@vben/common-ui';

import {
  Button,
  Empty,
  Form,
  FormItem,
  Input,
  InputNumber,
  Modal,
  Progress,
  Select,
  Space,
  Spin,
  Switch,
  Tag,
  Textarea,
} from 'ant-design-vue';

import { useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  effectiveModelList,
  tokenAccountList,
  tokenAppendGrant,
  tokenChangeStatus,
  tokenInitialGrant,
  tokenSetRemaining,
  tokenSetUnlimited,
  tokenSetWarningThreshold,
  tokenUserOptions,
} from '#/api/token';

import { columns, querySchema } from './data';

type OperationMode = 'append' | 'initial' | 'remaining' | 'threshold';

const formatter = new Intl.NumberFormat('zh-CN');
const operationOpen = ref(false);
const operationLoading = ref(false);
const operationMode = ref<OperationMode>('initial');
const currentAccount = ref<TokenAccount>();
const employees = ref<TokenUserOption[]>([]);
const employeeLoading = ref(false);
const effectiveOpen = ref(false);
const effectiveLoading = ref(false);
const effectiveModels = ref<ModelOption[]>([]);
const effectiveUserName = ref('');

const employeeSelectOptions = computed(() =>
  employees.value.map((item) => ({
    label: `${item.nickName || item.userName}（${item.userName}）`,
    value: item.userId,
  })),
);

const command = reactive<
  TokenAccountCommand & { userId?: number | string }
>({
  reason: '',
  tokens: undefined,
  unlimited: false,
  userId: undefined,
  warningThreshold: 80,
});

const modalTitle = computed(() => {
  const titles: Record<OperationMode, string> = {
    append: '追加 Token 额度',
    initial: '首次分配 Token 额度',
    remaining: '设置剩余额度',
    threshold: '修改告警阈值',
  };
  return titles[operationMode.value];
});

const formOptions: VbenFormProps = {
  commonConfig: {
    componentProps: { allowClear: true },
    labelWidth: 80,
  },
  schema: querySchema(),
  wrapperClass: 'grid-cols-1 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5 gap-x-[60px]',
};

const gridOptions: VxeGridProps = {
  columns,
  height: 'auto',
  pagerConfig: {},
  proxyConfig: {
    ajax: {
      query: async ({ page }, formValues = {}) =>
        tokenAccountList({
          pageNum: page.currentPage,
          pageSize: page.pageSize,
          ...formValues,
        }),
    },
  },
  rowConfig: { keyField: 'id' },
  id: 'ai-token-user-quota-index',
};

const [BasicTable, tableApi] = useVbenVxeGrid({ formOptions, gridOptions });

function resetCommand() {
  command.userId = undefined;
  command.tokens = undefined;
  command.unlimited = false;
  command.warningThreshold = 80;
  command.reason = '';
}

// 同时按账号和昵称搜索，合并去重并排除已配置额度的员工。
async function loadEmployees(keyword = '') {
  employeeLoading.value = true;
  try {
    const baseQuery = { pageNum: 1, pageSize: 50 };
    const results = keyword
      ? await Promise.all([
        tokenUserOptions({ ...baseQuery, userName: keyword }),
        tokenUserOptions({ ...baseQuery, nickName: keyword }),
      ])
      : [await tokenUserOptions(baseQuery)];
    const employeeMap = new Map<number | string, TokenUserOption>();
    results
      .flatMap((result) => result.rows)
      .filter((item) => !item.configured)
      .forEach((item) => employeeMap.set(item.userId, item));
    employees.value = [...employeeMap.values()];
  } finally {
    employeeLoading.value = false;
  }
}

async function openInitial() {
  resetCommand();
  currentAccount.value = undefined;
  operationMode.value = 'initial';
  operationOpen.value = true;
  await loadEmployees();
}

function openOperation(mode: Exclude<OperationMode, 'initial'>, row: TokenAccount) {
  resetCommand();
  currentAccount.value = row;
  command.userId = row.userId;
  if (mode === 'remaining') command.tokens = row.remainingTokens;
  if (mode === 'threshold') command.warningThreshold = row.warningThreshold;
  operationMode.value = mode;
  operationOpen.value = true;
}

/** 四种额度操作共用表单，但分别调用首次分配、追加、设余额或设阈值接口。 */
async function submitOperation() {
  const userId = command.userId;
  if (!userId) {
    Modal.warning({ content: '请选择员工', title: '提示' });
    return;
  }
  if (!command.reason?.trim()) {
    Modal.warning({ content: '请填写操作原因', title: '提示' });
    return;
  }
  if (
    operationMode.value !== 'threshold' &&
    command.tokens === undefined
  ) {
    Modal.warning({ content: '请填写 Token 数量', title: '提示' });
    return;
  }
  operationLoading.value = true;
  try {
    const payload: TokenAccountCommand = {
      reason: command.reason.trim(),
      tokens: command.tokens,
      unlimited: command.unlimited,
      warningThreshold: command.warningThreshold,
    };
    if (operationMode.value === 'initial') {
      await tokenInitialGrant(userId, payload);
    } else if (operationMode.value === 'append') {
      await tokenAppendGrant(userId, payload);
    } else if (operationMode.value === 'remaining') {
      await tokenSetRemaining(userId, payload);
    } else {
      await tokenSetWarningThreshold(userId, payload);
    }
    operationOpen.value = false;
    await tableApi.query();
  } finally {
    operationLoading.value = false;
  }
}

function toggleUnlimited(row: TokenAccount) {
  const target = row.unlimited !== '1';
  Modal.confirm({
    content: `确认将“${row.nickName || row.userName}”设置为${target ? '不限额' : '限额'}吗？`,
    title: '不限额设置确认',
    onOk: async () => {
      await tokenSetUnlimited(row.userId, {
        reason: target ? '管理员开启不限额' : '管理员关闭不限额',
        unlimited: target,
      });
      await tableApi.query();
    },
  });
}

function handleUnlimitedClick(event: MouseEvent, row: TokenAccount) {
  event.stopPropagation();
  toggleUnlimited(row);
}

function handleUnlimitedKeydown(event: KeyboardEvent, row: TokenAccount) {
  if (event.key !== 'Enter' && event.key !== ' ') return;
  event.preventDefault();
  event.stopPropagation();
  toggleUnlimited(row);
}

// 冻结/解冻需确认；接口约定 0 为正常、1 为冻结。
function toggleStatus(row: TokenAccount) {
  const frozen = row.status !== '1';
  Modal.confirm({
    content: `确认${frozen ? '冻结' : '解冻'}“${row.nickName || row.userName}”的 Token 账户吗？`,
    title: '账户状态确认',
    onOk: async () => {
      await tokenChangeStatus(row.userId, {
        reason: frozen ? '管理员冻结额度账户' : '管理员解冻额度账户',
        status: frozen ? '1' : '0',
      });
      await tableApi.query();
    },
  });
}

async function showEffectiveModels(row: TokenAccount) {
  effectiveUserName.value = row.nickName || row.userName;
  effectiveModels.value = [];
  effectiveOpen.value = true;
  effectiveLoading.value = true;
  try {
    effectiveModels.value = await effectiveModelList(row.userId);
  } finally {
    effectiveLoading.value = false;
  }
}

function formatTokens(value?: number) {
  return formatter.format(value || 0);
}
</script>

<template>
  <Page :auto-content-height="true">
    <BasicTable table-title="用户 Token 额度">
      <template #toolbar-tools>
        <Button
          type="primary"
          v-access:code="['token:user:grant']"
          @click="openInitial"
        >
          首次分配
        </Button>
      </template>
      <template #tokenNumber="{ row, column }">
        {{ formatTokens(row[column.field]) }}
      </template>
      <template #remaining="{ row }">
        <span :class="row.remainingTokens <= 0 ? 'text-red-500' : 'font-medium'">
          {{ row.unlimited === '1' ? '不限额' : formatTokens(row.remainingTokens) }}
        </span>
      </template>
      <template #usageRate="{ row }">
        <Progress
          :percent="Math.min(Number(row.usageRate || 0), 100)"
          :status="row.usageRate >= row.warningThreshold ? 'exception' : 'normal'"
          size="small"
        />
      </template>
      <template #unlimited="{ row }">
        <span
          v-access:code="['token:user:unlimited']"
          aria-label="切换不限额"
          class="quota-mode-trigger"
          role="button"
          tabindex="0"
          @click="handleUnlimitedClick($event, row)"
          @keydown="handleUnlimitedKeydown($event, row)"
        >
          <Switch :checked="row.unlimited === '1'" :tabindex="-1" />
        </span>
      </template>
      <template #status="{ row }">
        <Tag :color="row.status === '0' ? 'success' : row.status === '1' ? 'error' : 'default'">
          {{ row.status === '0' ? '正常' : row.status === '1' ? '冻结' : '关闭' }}
        </Tag>
      </template>
      <template #action="{ row }">
        <Space size="small">
          <ghost-button
            v-access:code="['token:user:grant']"
            @click.stop="openOperation('append', row)"
          >追加</ghost-button>
          <ghost-button
            v-access:code="['token:user:adjust']"
            @click.stop="openOperation('remaining', row)"
          >调余额</ghost-button>
          <ghost-button
            v-access:code="['token:user:adjust']"
            @click.stop="openOperation('threshold', row)"
          >阈值</ghost-button>
          <ghost-button
            v-access:code="['token:role-model:list']"
            @click.stop="showEffectiveModels(row)"
          >模型</ghost-button>
          <ghost-button
            v-access:code="['token:user:freeze']"
            @click.stop="toggleStatus(row)"
          >{{ row.status === '1' ? '解冻' : '冻结' }}</ghost-button>
        </Space>
      </template>
    </BasicTable>

    <Modal
      v-model:open="operationOpen"
      :confirm-loading="operationLoading"
      :title="modalTitle"
      width="560px"
      @ok="submitOperation"
    >
      <Form class="pt-3" :label-col="{ span: 6 }" :wrapper-col="{ span: 17 }">
        <FormItem v-if="operationMode === 'initial'" label="选择员工" required>
          <Select
            v-model:value="command.userId"
            :filter-option="false"
            :loading="employeeLoading"
            :options="employeeSelectOptions"
            show-search
            placeholder="搜索并选择未配置额度的员工"
            @search="loadEmployees"
          />
        </FormItem>
        <FormItem v-else label="员工">
          <Input :value="currentAccount?.nickName || currentAccount?.userName" disabled />
        </FormItem>
        <FormItem v-if="operationMode !== 'threshold'" label="Token 数量" required>
          <InputNumber
            v-model:value="command.tokens"
            :min="operationMode === 'remaining' || command.unlimited ? 0 : 1"
            :precision="0"
            class="w-full"
            placeholder="请输入 Token 数量"
          />
        </FormItem>
        <FormItem v-if="operationMode === 'threshold'" label="告警阈值" required>
          <InputNumber
            v-model:value="command.warningThreshold"
            :max="100"
            :min="1"
            :precision="0"
            addon-after="%"
            class="w-full"
          />
        </FormItem>
        <template v-if="operationMode === 'initial'">
          <FormItem label="不限额">
            <Switch v-model:checked="command.unlimited" />
          </FormItem>
          <FormItem label="告警阈值">
            <InputNumber
              v-model:value="command.warningThreshold"
              :max="100"
              :min="1"
              :precision="0"
              addon-after="%"
              class="w-full"
            />
          </FormItem>
        </template>
        <FormItem label="操作原因" required>
          <Textarea
            v-model:value="command.reason"
            :maxlength="500"
            :rows="3"
            placeholder="请输入操作原因，操作将写入额度流水"
            show-count
          />
        </FormItem>
      </Form>
    </Modal>

    <Modal
      v-model:open="effectiveOpen"
      :footer="null"
      :title="`${effectiveUserName}的有效模型`"
      width="620px"
    >
      <Spin :spinning="effectiveLoading">
        <Empty v-if="!effectiveLoading && effectiveModels.length === 0" description="当前角色没有授权可用模型" />
        <div v-else class="flex flex-wrap gap-2 py-4">
          <Tag v-for="model in effectiveModels" :key="model.modelId" color="blue">
            {{ model.modelName }} · {{ model.providerCode }}
          </Tag>
        </div>
      </Spin>
    </Modal>
  </Page>
</template>

<style scoped>
.quota-mode-trigger {
  display: inline-flex;
  width: 100%;
  min-height: 32px;
  cursor: pointer;
  align-items: center;
  justify-content: center;
}

.quota-mode-trigger :deep(.ant-switch) {
  pointer-events: none;
}
</style>
