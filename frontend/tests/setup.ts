import { vi } from 'vitest'

vi.stubGlobal(
  'ResizeObserver',
  class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
)

import { config } from '@vue/test-utils'
config.global.stubs = { transition: false, 'transition-group': false }
