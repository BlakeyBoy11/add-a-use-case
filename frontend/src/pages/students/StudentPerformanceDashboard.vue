<template>
  <div class="student-header">
    <el-text class="mx-1" size="large">{{ studentName }}</el-text>
    <template v-if="student">
      <el-tag v-if="student.enabled" type="success" data-cy="student-status">Active</el-tag>
      <el-tag v-else type="danger" data-cy="student-status">Deactivated</el-tag>
      <el-button
        v-if="student.enabled"
        type="danger"
        plain
        size="small"
        data-cy="student-deactivate"
        :loading="deactivating"
        @click="confirmDeactivation"
      >
        Deactivate
      </el-button>
    </template>
  </div>
  <StudentActivities :studentId="studentId" />
  <el-divider />
  <StudentEvaluations :studentId="studentId" />
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import axios from 'axios' // Only for isAxiosError; the call itself goes through apis/student
import { ElMessage, ElMessageBox } from 'element-plus'
import StudentActivities from '../activities/admin/StudentActivities.vue'
import StudentEvaluations from '../evaluations/admin/StudentEvaluations.vue'

import { useRoute } from 'vue-router'
import { deactivateStudent, findStudentById } from '@/apis/student'
import type { FindStudentByIdResponse, Student } from '@/apis/student/types'

const route = useRoute()

const studentId: number = Number(route.params.studentId)
const student = ref<Student>()
const deactivating = ref(false) // A deactivation request is in flight, so the button cannot send a second one
const studentName = computed(() =>
  student.value ? student.value.firstName + ' ' + student.value.lastName : ''
)

async function loadStudent() {
  const result: FindStudentByIdResponse = await findStudentById(studentId)
  student.value = result.data
}

onMounted(loadStudent)

// UC-STU-deactivate-student steps 3-6. The warning names every consequence in the use case's Associated Information.
async function confirmDeactivation() {
  if (!student.value || deactivating.value) return
  const { firstName, lastName } = student.value
  try {
    await ElMessageBox.confirm(
      `Deactivate ${firstName} ${lastName}? They will no longer be able to log in or submit weekly activity reports and peer evaluations, and a session they have open ends on its next request. Their account and submitted work are kept, they stay on their team, and they can be reactivated later.`,
      'Deactivate student',
      {
        confirmButtonText: 'Confirm',
        cancelButtonText: 'Cancel',
        type: 'warning',
        confirmButtonClass: 'deactivate-confirm',
        cancelButtonClass: 'deactivate-cancel'
      }
    )
  } catch {
    return // 5a: cancelled at the warning, so no request is sent
  }

  // The button stays disabled until the page shows the outcome, which removes it
  deactivating.value = true
  try {
    await deactivateStudent(studentId)
    ElMessage.success('Student deactivated.')
    await loadStudent()
  } catch (error) {
    // 6a: the shared request instance has already shown "This student is already deactivated."; re-fetch so the
    // page shows her as deactivated. Any other failure (a 403 has already routed away) is left to that instance.
    if (axios.isAxiosError(error) && error.response?.status === 409) {
      await loadStudent()
    }
  } finally {
    deactivating.value = false
  }
}
</script>

<style scoped>
.student-header {
  display: flex;
  align-items: center;
  gap: 12px;
}
</style>
