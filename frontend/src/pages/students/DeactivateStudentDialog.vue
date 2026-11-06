<template>
  <el-dialog
    :model-value="modelValue"
    title="Deactivate student"
    width="min(560px, 92vw)"
    data-cy="deactivate-dialog"
    :close-on-click-modal="!pending"
    :close-on-press-escape="!pending"
    :show-close="!pending"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <template v-if="student">
      <p>
        Deactivate <strong>{{ student.firstName }} {{ student.lastName }}</strong
        >?
      </p>
      <p>This will:</p>
      <ul data-cy="deactivate-consequences">
        <li>Make the account inactive and deny access, including an existing login.</li>
        <li>
          Remove the student's current team assignment, while keeping course-section enrollment.
        </li>
        <li>
          Stop submission reminders and new peer evaluations of this student, including for earlier
          weeks.
        </li>
        <li>
          Keep previously submitted weekly activity reports and peer evaluations available in
          authorized historical reports.
        </li>
        <li>Require a separate course-admin team assignment after any future reactivation.</li>
      </ul>
      <el-alert
        v-if="notice"
        :title="notice"
        type="warning"
        :closable="false"
        data-cy="deactivate-notice"
      />
      <el-button v-if="needsRefresh && !pending" @click="refreshStatus" data-cy="deactivate-refresh"
        >Refresh status</el-button
      >
    </template>
    <template #footer>
      <el-button
        :disabled="pending"
        @click="emit('update:modelValue', false)"
        data-cy="deactivate-cancel"
        >Cancel</el-button
      >
      <el-button
        type="danger"
        :loading="pending"
        :disabled="pending || needsRefresh || !currentActive"
        @click="confirm"
        data-cy="deactivate-confirm"
      >
        Confirm deactivation
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { isAxiosError } from 'axios'
import { ElMessage } from 'element-plus'
import { deactivateStudent, findStudentById } from '@/apis/student'
import type { Student } from '@/apis/student/types'

const props = defineProps<{ modelValue: boolean; student?: Student }>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  updated: [student: Student]
}>()
const pending = ref(false)
const needsRefresh = ref(false)
const currentActive = ref(false)
const notice = ref('')
const uncertainStudentIds = new Set<number>()
watch(
  () => props.modelValue,
  (visible) => {
    if (visible) {
      currentActive.value = Boolean(props.student?.enabled)
      needsRefresh.value = Boolean(props.student?.id && uncertainStudentIds.has(props.student.id))
      notice.value = ''
      if (needsRefresh.value) void refreshStatus()
    }
  }
)

async function refreshStatus() {
  if (!props.student?.id) return
  pending.value = true
  needsRefresh.value = true
  try {
    const result = await findStudentById(props.student.id)
    if (!result.flag) throw new Error('Status was not refreshed')
    currentActive.value = Boolean(result.data.enabled)
    emit('updated', result.data)
    uncertainStudentIds.delete(props.student.id)
    needsRefresh.value = false
    notice.value = `The request outcome was unknown. Status refreshed: ${result.data.enabled ? 'Active' : 'Inactive'}.`
  } catch {
    notice.value = 'The outcome is unknown. Refresh the student status before trying again.'
  } finally {
    pending.value = false
  }
}

async function confirm() {
  if (pending.value || needsRefresh.value || !currentActive.value || !props.student?.id) return
  pending.value = true
  try {
    const result = await deactivateStudent(props.student.id)
    if (!result.flag) throw new Error('Deactivation was not confirmed')
    emit('updated', result.data)
    ElMessage.success(result.message)
    emit('update:modelValue', false)
  } catch (error) {
    if (
      isAxiosError(error) &&
      error.response &&
      error.response.status >= 400 &&
      error.response.status < 500 &&
      error.response.status !== 408
    ) {
      notice.value = 'Deactivation was refused. The student status has not been changed here.'
    } else {
      uncertainStudentIds.add(props.student.id)
      needsRefresh.value = true
      notice.value = 'The outcome is unknown. Refreshing student status before another action.'
      await refreshStatus()
    }
  } finally {
    pending.value = false
  }
}
</script>
