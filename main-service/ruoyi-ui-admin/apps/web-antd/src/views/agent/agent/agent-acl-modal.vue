<script setup lang="ts">
import type { Dayjs } from 'dayjs';
import type { AgentAcl, AgentVO } from '#/api/agent/agent/model';
import type { Role } from '#/api/system/role/model';
import type { DeptTree } from '#/api/system/user/model';

import { computed, reactive, ref } from 'vue';

import {
  Alert,
  Button,
  DatePicker,
  Input,
  Modal,
  Select,
  Spin,
  Table,
  TreeSelect,
  message,
} from 'ant-design-vue';
import dayjs from 'dayjs';

import { agentAclList, agentAclSave } from '#/api/agent/agent';
import { roleOptionSelect } from '#/api/system/role';
import { getDeptTree } from '#/api/system/user';
import { DictEnum } from '@vben/constants';
import { getDictOptions } from '#/utils/dict';

interface AclRow {
  effectiveTime?: Dayjs;
  expireTime?: Dayjs;
  permission: 'MANAGE' | 'USE';
  remark?: string;
  status: string;
  subjectId?: number;
  subjectType: 'DEPT' | 'ROLE';
}

const open = ref(false);
const loading = ref(false);
const currentAgent = ref<AgentVO>();
const roleOptions = ref<Role[]>([]);
const deptTree = ref<DeptTree[]>([]);
const rows = reactive<AclRow[]>([]);
const subjectTypeOptions = getDictOptions(DictEnum.AI_AGENT_ACL_SUBJECT_TYPE);
const permissionOptions = getDictOptions(DictEnum.AI_AGENT_ACL_PERMISSION);
const statusOptions = getDictOptions(DictEnum.SYS_NORMAL_DISABLE);

const columns = computed(() => [
  { key: 'sequence', title: '序号', width: 70, customRender: ({ index }: { index: number }) => index + 1 },
  { dataIndex: 'subjectType', key: 'subjectType', title: '授权主体', width: 140 },
  { dataIndex: 'subjectId', key: 'subjectId', title: '角色 / 部门', width: 230 },
  { dataIndex: 'permission', key: 'permission', title: '权限', width: 130 },
  { dataIndex: 'effectiveTime', key: 'effectiveTime', title: '生效时间', width: 210 },
  { dataIndex: 'expireTime', key: 'expireTime', title: '失效时间', width: 210 },
  { dataIndex: 'status', key: 'status', title: '状态', width: 100 },
  { dataIndex: 'remark', key: 'remark', title: '备注', width: 180 },
  { key: 'action', title: '操作', width: 80 },
]);

function addRow(subjectType: 'DEPT' | 'ROLE' = 'ROLE') {
  rows.push({
    permission: 'USE',
    status: '0',
    subjectType,
  });
}

function removeRow(index: number) {
  rows.splice(index, 1);
}

// 服务端时间字符串转换为日期控件值，保存时再转换回接口格式。
function toRow(item: AgentAcl): AclRow {
  return {
    effectiveTime: item.effectiveTime ? dayjs(item.effectiveTime) : undefined,
    expireTime: item.expireTime ? dayjs(item.expireTime) : undefined,
    permission: item.permission || 'USE',
    remark: item.remark,
    status: item.status || '0',
    subjectId: item.subjectId,
    subjectType: item.subjectType,
  };
}

/** 打开时并行读取 ACL 与主体候选，只允许选择当前启用的角色。 */
async function show(agent: AgentVO) {
  open.value = true;
  loading.value = true;
  currentAgent.value = agent;
  try {
    const [aclItems, roles, depts] = await Promise.all([
      agentAclList(agent.id),
      roleOptionSelect(),
      getDeptTree(),
    ]);
    roleOptions.value = (roles as Role[]).filter((role) => role.status === '0');
    deptTree.value = depts;
    rows.splice(0, rows.length, ...aclItems.map(toRow));
  } finally {
    loading.value = false;
  }
}

// 每行必须绑定角色或部门；失效时间晚于生效时间后才提交整组授权。
async function submit() {
  if (!currentAgent.value) return;
  if (rows.some((row) => !row.subjectId)) {
    message.error('请为每条授权选择角色或部门');
    return;
  }
  if (
    rows.some(
      (row) =>
        row.effectiveTime &&
        row.expireTime &&
        !row.expireTime.isAfter(row.effectiveTime),
    )
  ) {
    message.error('失效时间必须晚于生效时间');
    return;
  }
  loading.value = true;
  try {
    await agentAclSave(
      currentAgent.value.id,
      rows.map((row) => ({
        effectiveTime: row.effectiveTime?.format('YYYY-MM-DD HH:mm:ss'),
        expireTime: row.expireTime?.format('YYYY-MM-DD HH:mm:ss'),
        permission: row.permission,
        remark: row.remark,
        status: row.status,
        subjectId: Number(row.subjectId),
        subjectType: row.subjectType,
      })),
    );
    open.value = false;
  } finally {
    loading.value = false;
  }
}

defineExpose({ show });
</script>

<template>
  <Modal
    v-model:open="open"
    :confirm-loading="loading"
    :mask-closable="false"
    title="配置 Agent 可用范围"
    width="1260px"
    @ok="submit"
  >
    <Spin :spinning="loading">
      <Alert
        class="mb-4"
        message="此处只决定哪些角色或部门可使用该 Agent。模型权限继续由“角色模型权限”决定，知识库权限继续由知识库 ACL 决定，三者不会互相覆盖。"
        show-icon
        type="info"
      />
      <div class="mb-3 flex gap-2">
        <Button type="primary" @click="addRow('ROLE')">新增角色授权</Button>
        <Button @click="addRow('DEPT')">新增部门授权</Button>
      </div>
      <Table
        :columns="columns"
        :data-source="rows"
        :pagination="false"
        row-key="subjectId"
        size="small"
      >
        <template #bodyCell="{ column, index, record }">
          <template v-if="column.key === 'subjectType'">
            <Select
              v-model:value="record.subjectType"
              :options="subjectTypeOptions"
              @change="record.subjectId = undefined"
            />
          </template>
          <template v-else-if="column.key === 'subjectId'">
            <Select
              v-if="record.subjectType === 'ROLE'"
              v-model:value="record.subjectId"
              :options="roleOptions.map((role) => ({ label: role.roleName, value: role.roleId }))"
              allow-clear
              placeholder="请选择角色"
              show-search
            />
            <TreeSelect
              v-else
              v-model:value="record.subjectId"
              :field-names="{ children: 'children', label: 'label', value: 'id' }"
              :tree-data="deptTree"
              allow-clear
              placeholder="请选择部门"
              show-search
              tree-default-expand-all
              tree-node-filter-prop="label"
            />
          </template>
          <template v-else-if="column.key === 'permission'">
            <Select
              v-model:value="record.permission"
              :options="permissionOptions"
            />
          </template>
          <template v-else-if="column.key === 'effectiveTime'">
            <DatePicker
              v-model:value="record.effectiveTime"
              class="w-full"
              format="YYYY-MM-DD HH:mm:ss"
              placeholder="立即生效"
              show-time
            />
          </template>
          <template v-else-if="column.key === 'expireTime'">
            <DatePicker
              v-model:value="record.expireTime"
              class="w-full"
              format="YYYY-MM-DD HH:mm:ss"
              placeholder="永久有效"
              show-time
            />
          </template>
          <template v-else-if="column.key === 'status'">
            <Select
              v-model:value="record.status"
              :options="statusOptions"
            />
          </template>
          <template v-else-if="column.key === 'remark'">
            <Input v-model:value="record.remark" :maxlength="500" placeholder="备注" />
          </template>
          <template v-else-if="column.key === 'action'">
            <Button danger size="small" type="link" @click="removeRow(index)">删除</Button>
          </template>
        </template>
      </Table>
      <div v-if="!rows.length" class="py-8 text-center text-gray-400">
        暂未配置可用范围。启用后的 Agent 仅超级管理员可见。
      </div>
    </Spin>
  </Modal>
</template>
