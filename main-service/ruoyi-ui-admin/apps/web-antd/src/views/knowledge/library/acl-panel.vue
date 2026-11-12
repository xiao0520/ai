<script setup lang="ts">
import type { KnowledgeAcl } from '#/api/knowledge/library/model';

import { onMounted, reactive, ref } from 'vue';

import { DictEnum } from '@vben/constants';

import {
  Form,
  FormItem,
  Modal,
  Popconfirm,
  Select,
  Switch,
  Table,
  Tag,
} from 'ant-design-vue';

import {
  knowledgeAclAdd,
  knowledgeAclList,
  knowledgeAclRemove,
} from '#/api/knowledge/library';
import { deptList } from '#/api/system/dept';
import { roleList } from '#/api/system/role';
import { getDictOptions } from '#/utils/dict';

const props = defineProps<{ knowledgeId: number | string }>();
const loading = ref(false);
const open = ref(false);
const rows = ref<KnowledgeAcl[]>([]);
const roleOptions = ref<Array<{ label: string; value: string }>>([]);
const deptOptions = ref<Array<{ label: string; value: string }>>([]);
const subjectTypeOptions = getDictOptions(
  DictEnum.AI_KNOWLEDGE_ACL_SUBJECT_TYPE,
);
const permissionOptions = getDictOptions(DictEnum.AI_KNOWLEDGE_ACL_PERMISSION);
const form = reactive({
  subjectType: 'ROLE',
  subjectId: '',
  permission: 'READ',
  includeChildren: '0',
  status: '0',
});

const columns = [
  {
    key: 'sequence',
    title: '序号',
    width: 70,
    customRender: ({ index }: { index: number }) => index + 1,
  },
  {
    dataIndex: 'subjectType',
    key: 'subjectType',
    title: '授权主体类型',
    width: 200,
  },
  { dataIndex: 'subjectId', key: 'subjectId', title: '主体 ID / 角色标识' },
  { dataIndex: 'permission', key: 'permission', title: '权限', width: 110 },
  {
    dataIndex: 'includeChildren',
    key: 'includeChildren',
    title: '包含下级部门',
    width: 200,
  },
  {
    dataIndex: 'effectiveTime',
    key: 'effectiveTime',
    title: '生效时间',
    width: 240,
  },
  {
    dataIndex: 'expireTime',
    key: 'expireTime',
    title: '失效时间',
    width: 240,
  },
  { key: 'action', title: '操作', width: 100 },
];

async function load() {
  loading.value = true;
  try {
    rows.value = await knowledgeAclList(props.knowledgeId);
  } finally {
    loading.value = false;
  }
}

// 角色授权提交 roleKey，部门授权提交部门 ID；切换主体时不能复用旧 ID。
async function loadRoleOptions() {
  if (roleOptions.value.length > 0) return;
  const result = await roleList({ pageNum: 1, pageSize: 1000, status: '0' });
  roleOptions.value = result.rows
    .filter((role) => role.roleKey)
    .map((role) => ({
      label: `${role.roleName}（${role.roleKey}）`,
      value: role.roleKey,
    }));
}

async function loadDeptOptions() {
  if (deptOptions.value.length > 0) return;
  const depts = await deptList({ status: '0' });
  deptOptions.value = depts.map((dept) => ({
    label: `${dept.deptName}（${dept.deptId}）`,
    value: String(dept.deptId),
  }));
}

function reset() {
  form.subjectType = 'ROLE';
  form.subjectId = '';
  form.permission = 'READ';
  form.includeChildren = '0';
  form.status = '0';
}

async function loadSubjectOptions(subjectType: string) {
  if (subjectType === 'ROLE') {
    await loadRoleOptions();
    return;
  }
  await loadDeptOptions();
}

async function openAdd() {
  reset();
  await loadSubjectOptions(form.subjectType);
  open.value = true;
}

// 先清除旧主体，再加载新类型候选，避免角色标识被当成部门 ID 保存。
async function handleSubjectTypeChange(subjectType: string) {
  form.subjectId = '';
  await loadSubjectOptions(subjectType);
}

async function submit() {
  if (!form.subjectId.trim()) return;
  await knowledgeAclAdd({ knowledgeId: props.knowledgeId, ...form });
  open.value = false;
  await load();
}

async function remove(row: any) {
  await knowledgeAclRemove(row.id);
  await load();
}

onMounted(load);
</script>

<template>
  <div class="space-y-3">
    <div class="flex justify-end">
      <a-button type="primary" v-access:code="['knowledge:acl:edit']" @click="openAdd">新增授权</a-button>
    </div>
    <Table :columns="columns" :data-source="rows" :loading="loading" :pagination="false" :row-key="(row: KnowledgeAcl) => row.id">
      <template #bodyCell="{ column, record }">
        <Tag v-if="column.key === 'subjectType'" color="blue">{{ record.subjectType === 'ROLE' ? '角色' : '部门' }}</Tag>
        <Tag v-else-if="column.key === 'permission'" :color="record.permission === 'MANAGE' ? 'purple' : record.permission === 'WRITE' ? 'orange' : 'green'">
          {{ record.permission === 'MANAGE' ? '管理' : record.permission === 'WRITE' ? '读写' : '只读' }}
        </Tag>
        <span v-else-if="column.key === 'includeChildren'">{{ record.includeChildren === '1' ? '是' : '否' }}</span>
        <Popconfirm v-else-if="column.key === 'action'" title="确认移除此授权？" @confirm="remove(record)">
          <ghost-button danger v-access:code="['knowledge:acl:edit']">移除</ghost-button>
        </Popconfirm>
      </template>
    </Table>
  </div>

  <Modal v-model:open="open" title="新增知识库授权" @ok="submit">
    <Form layout="vertical">
      <FormItem label="授权主体类型" required>
        <Select v-model:value="form.subjectType" :options="subjectTypeOptions" @change="handleSubjectTypeChange" />
      </FormItem>
      <FormItem v-if="form.subjectType === 'ROLE'" label="角色" required>
        <Select
          v-model:value="form.subjectId"
          :options="roleOptions"
          option-filter-prop="label"
          placeholder="请选择当前系统角色"
          show-search
        />
      </FormItem>
      <FormItem v-else label="部门" required>
        <Select
          v-model:value="form.subjectId"
          :options="deptOptions"
          option-filter-prop="label"
          placeholder="请选择当前系统部门"
          show-search
        />
      </FormItem>
      <FormItem label="授权权限" required>
        <Select v-model:value="form.permission" :options="permissionOptions" />
      </FormItem>
      <FormItem v-if="form.subjectType === 'DEPT'" label="包含下级部门">
        <Switch v-model:checked="form.includeChildren" checked-value="1" unchecked-value="0" />
      </FormItem>
    </Form>
  </Modal>
</template>
