<script setup lang="ts">
import type { SkillBoundAgent, SkillVO } from '#/api/agent/skill/model';

import { computed, ref } from 'vue';

import { Modal, Spin, Table, Tag } from 'ant-design-vue';

import { skillBoundAgents } from '#/api/agent/skill';

const open = ref(false);
const loading = ref(false);
const currentSkill = ref<SkillVO>();
const rows = ref<SkillBoundAgent[]>([]);

const title = computed(() => {
  const name =
    currentSkill.value?.displayName || currentSkill.value?.skillName || '';
  return `已绑定 Agent - ${name}`;
});

const columns = [
  { key: 'sequence', title: '序号', width: 70, customRender: ({ index }: { index: number }) => index + 1 },
  { dataIndex: 'agentName', key: 'agentName', title: '智能体名称' },
  { dataIndex: 'agentCode', key: 'agentCode', title: 'Agent编码' },
  { dataIndex: 'status', key: 'status', title: '绑定状态', width: 100 },
  { dataIndex: 'configJson', key: 'configJson', title: '覆盖配置' },
];

async function show(skill: SkillVO) {
  open.value = true;
  loading.value = true;
  currentSkill.value = skill;
  rows.value = [];
  try {
    rows.value = await skillBoundAgents(skill.id);
  } finally {
    loading.value = false;
  }
}

defineExpose({ show });
</script>

<template>
  <Modal
    v-model:open="open"
    :footer="null"
    :title="title"
    width="720px"
    centered
  >
    <Spin :spinning="loading">
      <Table
        :columns="columns"
        :data-source="rows"
        :pagination="false"
        row-key="agentId"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'status'">
            <Tag :color="record.status === '0' ? 'success' : 'default'">
              {{ record.status === '0' ? '启用' : '停用' }}
            </Tag>
          </template>
          <template v-else-if="column.key === 'configJson'">
            <span>{{ record.configJson || '-' }}</span>
          </template>
        </template>
      </Table>
    </Spin>
  </Modal>
</template>
