import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'
import { nextTick } from 'vue'
import DeactivateStudentDialog from '@/pages/students/DeactivateStudentDialog.vue'
import StudentPerformanceDashboard from '@/pages/students/StudentPerformanceDashboard.vue'
import Students from '@/pages/students/Students.vue'
import EditStudentForm from '@/pages/students/EditStudentForm.vue'

const api = vi.hoisted(() => ({
  deactivateStudent: vi.fn(),
  findStudentById: vi.fn(),
  searchStudents: vi.fn(),
  updateStudent: vi.fn()
}))
vi.mock('@/apis/student', () => api)
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { studentId: '4' } }),
  useRouter: () => ({ push: vi.fn() })
}))
vi.mock('@/stores/settings', () => ({ useSettingsStore: () => ({ defaultSectionId: 2 }) }))
vi.mock('@/pages/activities/admin/StudentActivities.vue', () => ({
  default: { template: '<div>Activities</div>' }
}))
vi.mock('@/pages/evaluations/admin/StudentEvaluations.vue', () => ({
  default: { template: '<div>Evaluations</div>' }
}))

const active = {
  id: 4,
  username: 'j.smith@abc.edu',
  firstName: 'John',
  lastName: 'Smith',
  email: 'j.smith@abc.edu',
  enabled: true,
  teamId: 1,
  teamName: 'Team1'
}
const inactive = { ...active, enabled: false, teamId: undefined, teamName: undefined }
const result = (data: unknown, message = 'Student deactivated successfully') => ({
  flag: true,
  code: 200,
  message,
  data
})
let wrapper: ReturnType<typeof mount>
const element = (key: string) => document.querySelector(`[data-cy="${key}"]`) as HTMLElement
const confirm = () => element('deactivate-confirm') as HTMLButtonElement
async function click(key: string) {
  element(key).click()
  await nextTick()
  await flushPromises()
}
async function open() {
  wrapper = mount(DeactivateStudentDialog, {
    props: {
      modelValue: false,
      student: active,
      'onUpdate:modelValue': (value: boolean) => wrapper.setProps({ modelValue: value })
    },
    global: { plugins: [ElementPlus] },
    attachTo: document.body
  })
  await wrapper.setProps({ modelValue: true })
  await flushPromises()
}
beforeEach(() => {
  vi.clearAllMocks()
  api.findStudentById.mockResolvedValue(result(active))
  api.deactivateStudent.mockResolvedValue(result(inactive))
  api.searchStudents.mockResolvedValue(
    result({ content: [active], number: 0, size: 50, totalElements: 1 })
  )
  vi.spyOn(ElMessage, 'success').mockImplementation(() => ({ close() {} }))
})
afterEach(() => {
  wrapper?.unmount()
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})

describe('UC-STU-deactivate-student shared dialog', () => {
  it('names the student and every consequence (T01)', async () => {
    await open()
    const text = element('deactivate-dialog').textContent!
    for (const phrase of [
      'John Smith',
      'existing login',
      'team assignment',
      'enrollment',
      'reminders',
      'earlier weeks',
      'weekly activity reports',
      'historical reports',
      'separate course-admin team assignment'
    ])
      expect(text).toContain(phrase)
  })
  it('sends one POST, disables Confirm while pending, and emits the committed DTO (T01)', async () => {
    let resolve!: (value: unknown) => void
    api.deactivateStudent.mockReturnValue(
      new Promise((r) => {
        resolve = r
      })
    )
    await open()
    await click('deactivate-confirm')
    expect(confirm().disabled).toBe(true)
    confirm().click()
    await flushPromises()
    expect(api.deactivateStudent).toHaveBeenCalledTimes(1)
    resolve(result(inactive))
    await flushPromises()
    expect(wrapper.emitted('updated')).toEqual([[inactive]])
    expect(ElMessage.success).toHaveBeenCalledWith('Student deactivated successfully')
    expect(wrapper.emitted('update:modelValue')).toEqual([[false]])
  })
  for (const method of ['cancel', 'close', 'escape', 'backdrop']) {
    it(`sends no POST on ${method} dismissal (T06)`, async () => {
      await open()
      if (method === 'cancel') await click('deactivate-cancel')
      if (method === 'close')
        (document.querySelector('.el-dialog__headerbtn') as HTMLElement).click()
      if (method === 'escape')
        document.dispatchEvent(
          new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', bubbles: true })
        )
      if (method === 'backdrop') {
        const overlay = document.querySelector('.el-overlay-dialog')!
        for (const event of ['mousedown', 'mouseup', 'click'])
          overlay.dispatchEvent(new MouseEvent(event, { bubbles: true }))
      }
      await flushPromises()
      expect(api.deactivateStudent).not.toHaveBeenCalled()
      await vi.waitFor(() => expect(wrapper.emitted('update:modelValue')).toEqual([[false]]))
    })
  }
  it('uses the exact unchanged outcome message and refreshes the DTO (T04)', async () => {
    api.deactivateStudent.mockResolvedValue(result(inactive, 'Student is already deactivated'))
    await open()
    await click('deactivate-confirm')
    expect(ElMessage.success).toHaveBeenCalledWith('Student is already deactivated')
    expect(wrapper.emitted('updated')).toEqual([[inactive]])
  })
  for (const error of [
    new Error('network error'),
    { isAxiosError: true, response: { status: 504 } }
  ]) {
    it('keeps an unknown outcome disabled until status is refreshed (T16)', async () => {
      let resolve!: (value: unknown) => void
      api.deactivateStudent.mockRejectedValue(error)
      api.findStudentById.mockReturnValue(
        new Promise((r) => {
          resolve = r
        })
      )
      await open()
      await click('deactivate-confirm')
      expect(confirm().disabled).toBe(true)
      expect(element('deactivate-notice').textContent).toContain('unknown')
      expect(ElMessage.success).not.toHaveBeenCalled()
      resolve(result(active))
      await flushPromises()
      expect(confirm().disabled).toBe(false)
      expect(element('deactivate-notice').textContent).toContain('Status refreshed: Active')
    })
  }
  it('cannot bypass a failed refresh by closing and reopening; retry refresh can reveal inactive (T16)', async () => {
    api.deactivateStudent.mockRejectedValue(new Error('timeout'))
    api.findStudentById.mockRejectedValue(new Error('offline'))
    await open()
    await click('deactivate-confirm')
    expect(confirm().disabled).toBe(true)
    await click('deactivate-cancel')
    await wrapper.setProps({ modelValue: true })
    await flushPromises()
    expect(api.findStudentById).toHaveBeenCalledTimes(2)
    expect(confirm().disabled).toBe(true)
    api.findStudentById.mockResolvedValue(result(inactive))
    await click('deactivate-refresh')
    expect(wrapper.emitted('updated')).toEqual([[inactive]])
    expect(confirm().disabled).toBe(true)
    expect(api.deactivateStudent).toHaveBeenCalledTimes(1)
    expect(ElMessage.success).not.toHaveBeenCalled()
  })
  it('does not show success or invent status after a 403 (T16)', async () => {
    api.deactivateStudent.mockRejectedValue({ isAxiosError: true, response: { status: 403 } })
    await open()
    await click('deactivate-confirm')
    expect(wrapper.emitted('updated')).toBeUndefined()
    expect(ElMessage.success).not.toHaveBeenCalled()
    expect(element('deactivate-notice').textContent).toContain('refused')
  })
})

describe('Student screens (T12)', () => {
  it('puts labeled status beside the name and disables an inactive action in the list', async () => {
    api.searchStudents.mockResolvedValue(
      result({ content: [inactive], number: 0, size: 50, totalElements: 1 })
    )
    wrapper = mount(Students, { global: { plugins: [ElementPlus] }, attachTo: document.body })
    await flushPromises()
    const badge = element('student-status')
    expect(badge.textContent).toContain('Inactive')
    expect(badge.classList.contains('el-tag--danger')).toBe(true)
    expect(badge.parentElement?.textContent).toContain('John')
    expect((element('student-deactivate') as HTMLButtonElement).disabled).toBe(true)
  })
  it('refreshes selected details and team after the dedicated action', async () => {
    wrapper = mount(StudentPerformanceDashboard, {
      global: { plugins: [ElementPlus] },
      attachTo: document.body
    })
    await flushPromises()
    expect(element('student-status').textContent).toContain('Active')
    expect(element('student-status').classList.contains('el-tag--success')).toBe(true)
    await click('student-deactivate')
    await click('deactivate-confirm')
    expect(element('student-status').textContent).toContain('Inactive')
    expect(element('student-team').textContent).toContain('None')
    expect((element('student-deactivate') as HTMLButtonElement).disabled).toBe(true)
  })
  it('renders profile status as read-only', async () => {
    wrapper = mount(EditStudentForm, {
      props: { studentId: 4 },
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()
    expect(wrapper.find('[data-cy="profile-status"]').text()).toBe('Active')
    expect(wrapper.find('.el-switch').exists()).toBe(false)
  })
})
