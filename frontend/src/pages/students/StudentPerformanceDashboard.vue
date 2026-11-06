<template>
  <div v-if="student" data-cy="student-details">
    <el-text size="large">{{ student.firstName }} {{ student.lastName }}</el-text>
    <el-tag :type="student.enabled ? 'success' : 'danger'" data-cy="student-status">{{
      student.enabled ? 'Active' : 'Inactive'
    }}</el-tag>
    <el-button
      type="danger"
      plain
      :disabled="!student.enabled"
      @click="deactivationVisible = true"
      data-cy="student-deactivate"
      >Deactivate</el-button
    >
    <p data-cy="student-team">Team: {{ student.teamName || 'None' }}</p>
  </div>
  <DeactivateStudentDialog
    v-model="deactivationVisible"
    :student="student"
    @updated="studentUpdated"
  />
  <StudentActivities :studentId="studentId" />
  <el-divider />
  <StudentEvaluations :studentId="studentId" />
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import StudentActivities from '../activities/admin/StudentActivities.vue'
import StudentEvaluations from '../evaluations/admin/StudentEvaluations.vue'
import DeactivateStudentDialog from './DeactivateStudentDialog.vue'
import { useRoute } from 'vue-router'
import { findStudentById } from '@/apis/student'
import type { Student } from '@/apis/student/types'

const route = useRoute()
const studentId = Number(route.params.studentId)
const student = ref<Student>()
const deactivationVisible = ref(false)
function studentUpdated(updated: Student) {
  student.value = updated
}
onMounted(async () => {
  student.value = (await findStudentById(studentId)).data
})
</script>
