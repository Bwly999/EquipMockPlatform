/** 通用对话框：确认 / 输入提示（中文界面） */
import { useEffect, useRef, useState, type ReactNode } from 'react'

export function Modal({
  title,
  children,
  footer,
  onClose,
  width = 460,
}: {
  title: string
  children: ReactNode
  footer: ReactNode
  onClose: () => void
  width?: number
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4">
      <div
        className="max-h-full w-full overflow-auto rounded-lg bg-white shadow-2xl"
        style={{ maxWidth: width }}
        role="dialog"
        aria-modal="true"
        aria-label={title}
      >
        <div className="border-b border-slate-200 px-4 py-3 text-sm font-semibold text-slate-800">{title}</div>
        <div className="px-4 py-4 text-sm text-slate-700">{children}</div>
        <div className="flex justify-end gap-2 border-t border-slate-200 bg-slate-50 px-4 py-3">{footer}</div>
      </div>
    </div>
  )
}

export function ConfirmDialog({
  title,
  message,
  confirmText = '确定',
  danger = false,
  extraCheckbox,
  onConfirm,
  onCancel,
}: {
  title: string
  message: ReactNode
  confirmText?: string
  danger?: boolean
  extraCheckbox?: { label: string; checked: boolean; onChange: (v: boolean) => void }
  onConfirm: () => void
  onCancel: () => void
}) {
  return (
    <Modal
      title={title}
      onClose={onCancel}
      footer={
        <>
          <button type="button" className="rounded border border-slate-300 bg-white px-3 py-1.5 text-sm hover:bg-slate-50" onClick={onCancel}>
            取消
          </button>
          <button
            type="button"
            autoFocus
            className={`rounded px-3 py-1.5 text-sm text-white ${danger ? 'bg-red-600 hover:bg-red-700' : 'bg-brand-600 hover:bg-brand-700'}`}
            onClick={onConfirm}
          >
            {confirmText}
          </button>
        </>
      }
    >
      <div className="space-y-3">
        <div>{message}</div>
        {extraCheckbox && (
          <label className="flex items-center gap-2 text-sm text-slate-600">
            <input
              type="checkbox"
              checked={extraCheckbox.checked}
              onChange={(e) => extraCheckbox.onChange(e.target.checked)}
            />
            {extraCheckbox.label}
          </label>
        )}
      </div>
    </Modal>
  )
}

export function PromptDialog({
  title,
  label,
  initialValue = '',
  placeholder = '',
  validate,
  confirmText = '确定',
  onSubmit,
  onCancel,
}: {
  title: string
  label: string
  initialValue?: string
  placeholder?: string
  validate?: (value: string) => string | null
  confirmText?: string
  onSubmit: (value: string) => void
  onCancel: () => void
}) {
  const [value, setValue] = useState(initialValue)
  const [touched, setTouched] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    inputRef.current?.focus()
    inputRef.current?.select()
  }, [])

  const error = touched && validate ? validate(value) : null
  const submit = () => {
    setTouched(true)
    const err = validate?.(value) ?? null
    if (err) return
    onSubmit(value)
  }

  return (
    <Modal
      title={title}
      onClose={onCancel}
      footer={
        <>
          <button type="button" className="rounded border border-slate-300 bg-white px-3 py-1.5 text-sm hover:bg-slate-50" onClick={onCancel}>
            取消
          </button>
          <button
            type="button"
            className="rounded bg-brand-600 px-3 py-1.5 text-sm text-white hover:bg-brand-700 disabled:opacity-50"
            disabled={Boolean(error)}
            onClick={submit}
          >
            {confirmText}
          </button>
        </>
      }
    >
      <label className="block">
        <span className="label">{label}</span>
        <input
          ref={inputRef}
          className={`input ${error ? 'input-invalid' : ''}`}
          value={value}
          placeholder={placeholder}
          onChange={(e) => {
            setValue(e.target.value)
            setTouched(true)
          }}
          onKeyDown={(e) => {
            if (e.key === 'Enter') submit()
          }}
        />
        {error && <span className="mt-1 block text-xs text-red-600">{error}</span>}
      </label>
    </Modal>
  )
}
