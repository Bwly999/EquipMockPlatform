// @vitest-environment jsdom
/** 规则编辑器组件测试（Testing Library）：正则即时校验 / 参数行类型推断 / action 三态 */
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useState } from 'react'
import { ActionEditor } from '../../src/components/ActionEditor'
import { ArgsEditor } from '../../src/components/ArgsEditor'
import { PatternEditor } from '../../src/components/PatternEditor'

afterEach(cleanup)

/** 受控包装：让 onChange 驱动重渲染（模拟真实表单流） */
function ArgsHarness({ initial }: { initial: unknown[] }) {
  const [args, setArgs] = useState(initial)
  return <ArgsEditor args={args} onChange={setArgs} />
}

describe('PatternEditor', () => {
  it('非法正则标红并显示错误信息', () => {
    const onChange = vi.fn()
    render(<PatternEditor patterns={['[unclosed']} onChange={onChange} />)
    const input = screen.getByDisplayValue('[unclosed')
    expect(input.className).toContain('input-invalid')
    expect(screen.getByText(/非法正则/)).toBeTruthy()
  })

  it('合法正则不标红', () => {
    render(<PatternEditor patterns={['\\d+']} onChange={() => {}} />)
    const input = screen.getByDisplayValue('\\d+')
    expect(input.className).not.toContain('input-invalid')
  })

  it('测试匹配输入框：全匹配命中 / 不命中', () => {
    render(<PatternEditor patterns={['\\d+']} onChange={() => {}} />)
    const test = screen.getByPlaceholderText('测试匹配…')
    fireEvent.change(test, { target: { value: '123' } })
    expect(screen.getByText('✓ 全匹配')).toBeTruthy()
    fireEvent.change(test, { target: { value: 'a123' } })
    expect(screen.getByText('✗ 不匹配')).toBeTruthy()
  })

  it('增删正则行', () => {
    const onChange = vi.fn()
    render(<PatternEditor patterns={[]} onChange={onChange} />)
    fireEvent.click(screen.getByText('+ 添加正则'))
    expect(onChange).toHaveBeenCalledWith([''])
  })
})

describe('ArgsEditor', () => {
  it('按值类型渲染控件：数字/字符串/布尔/null', () => {
    const onChange = vi.fn()
    render(<ArgsEditor args={[5, 'CH1', true, null]} onChange={onChange} />)
    expect(screen.getByDisplayValue('5')).toBeTruthy()
    expect(screen.getByDisplayValue('CH1')).toBeTruthy()
    const bool = screen.getByDisplayValue('true') as HTMLSelectElement
    expect(bool.tagName).toBe('SELECT')
    // null 值展示（多个 select 的 option 里也有 "null" 文本）
    expect(screen.getAllByText('null').length).toBeGreaterThanOrEqual(4)
  })

  it('修改数字参数回调', () => {
    const onChange = vi.fn()
    render(<ArgsEditor args={[5]} onChange={onChange} />)
    fireEvent.change(screen.getByDisplayValue('5'), { target: { value: '9' } })
    expect(onChange).toHaveBeenCalledWith([9])
  })

  it('添加参数行默认 null', () => {
    const onChange = vi.fn()
    render(<ArgsEditor args={[]} onChange={onChange} />)
    fireEvent.click(screen.getByText('+ 添加参数'))
    expect(onChange).toHaveBeenCalledWith([null])
  })

  it('$hex 控件：奇数长度 hex 标红（值仍提交，保存期由校验器拦截）', () => {
    render(<ArgsHarness initial={[{ $hex: 'A1B2' }]} />)
    const input = screen.getByDisplayValue('A1B2')
    fireEvent.change(input, { target: { value: 'A1B3C' } })
    expect(screen.getByDisplayValue('A1B3C').className).toContain('input-invalid')
    expect(screen.getByText(/偶数长度/)).toBeTruthy()
  })
})

describe('ActionEditor', () => {
  it('THROW 显示 FQCN 与 message 输入，非法 FQCN 标红', () => {
    const onChange = vi.fn()
    render(<ActionEditor action={{ type: 'THROW', exception: 'bad name' }} onChange={onChange} />)
    expect(screen.getByPlaceholderText('java.io.IOException').className).toContain('input-invalid')
    expect(screen.getByPlaceholderText('device timeout')).toBeTruthy()
  })

  it('切换动作类型生成对应默认值', () => {
    const onChange = vi.fn()
    render(<ActionEditor action={undefined} onChange={onChange} />)
    const select = screen.getByLabelText('动作类型') as HTMLSelectElement
    fireEvent.change(select, { target: { value: 'VOID' } })
    expect(onChange).toHaveBeenCalledWith({ type: 'VOID' })
    fireEvent.change(select, { target: { value: 'VALUE' } })
    expect(onChange).toHaveBeenCalledWith({ type: 'VALUE', value: null })
  })

  it('allowReal：默认动作支持 REAL（删除 defaultAction）', () => {
    const onChange = vi.fn()
    render(<ActionEditor allowReal action={{ type: 'VOID' }} onChange={onChange} />)
    const select = screen.getByLabelText('动作类型') as HTMLSelectElement
    expect([...select.options].some((o) => o.value === 'REAL')).toBe(true)
    fireEvent.change(select, { target: { value: 'REAL' } })
    expect(onChange).toHaveBeenCalledWith(undefined)
  })

  it('VOID 无附加输入', () => {
    render(<ActionEditor action={{ type: 'VOID' }} onChange={() => {}} />)
    expect(screen.getAllByText(/跳过真实调用/).length).toBeGreaterThan(0)
  })
})
