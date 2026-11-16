import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import ProductPageHeader from './ProductPageHeader.vue'

describe('ProductPageHeader', () => {
  it('renders a compact page introduction with a named status', () => {
    const wrapper = mount(ProductPageHeader, {
      props: {
        eyebrow: 'VIDEO WORKSPACE',
        title: '视频工作台',
        description: '围绕当前视频内容，开展可核验的分析与问答。',
        status: '所有服务正常',
      },
    })

    expect(wrapper.get('header').classes()).toContain('product-page-header')
    expect(wrapper.get('h1').text()).toBe('视频工作台')
    expect(wrapper.get('[data-test="page-eyebrow"]').text()).toBe('VIDEO WORKSPACE')
    expect(wrapper.get('[data-test="page-status"]').text()).toBe('所有服务正常')
  })

  it('exposes a dedicated action area without changing the heading hierarchy', () => {
    const wrapper = mount(ProductPageHeader, {
      props: {
        eyebrow: 'MEDIA LIBRARY',
        title: '媒体资料库',
        description: '管理已经上传的视频。',
      },
      slots: {
        actions: '<button type="button">上传视频</button>',
      },
    })

    expect(wrapper.findAll('h1')).toHaveLength(1)
    expect(wrapper.get('[data-test="page-actions"] button').text()).toBe('上传视频')
    expect(wrapper.find('[data-test="page-status"]').exists()).toBe(false)
  })
})
