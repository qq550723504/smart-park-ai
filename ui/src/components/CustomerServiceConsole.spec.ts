import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { ElInput } from 'element-plus'
import CustomerServiceConsole from './CustomerServiceConsole.vue'

describe('CustomerServiceConsole', () => {
  it('keeps the customer question accessibly named after value entry', async () => {
    const wrapper = mount(CustomerServiceConsole, {
      props: { role: 'VIEWER' },
      global: {
        stubs: {
          'el-input': ElInput,
          'el-button': true,
          'el-tag': true,
          'el-empty': true,
        },
      },
    })

    const questionInput = wrapper.get('.chat-composer input')
    await questionInput.setValue('A1 洗手间漏水，需要报修')

    expect(questionInput.attributes('aria-label')).toBe('园区服务问题')
    expect((questionInput.element as HTMLInputElement).value).toBe('A1 洗手间漏水，需要报修')
    wrapper.unmount()
  })
})
