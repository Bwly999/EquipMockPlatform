/**
 * Monaco 整文件 JSON 模式（06 §5.3 / §9）：
 * - 静态 import + worker `?worker&inline` 内联（禁止 CDN/运行期下载）
 * - 把 subgroup schema 注册进 json worker 做补全与错误标记
 * - Ctrl+S 走统一保存流
 */
import { useEffect, useRef } from 'react'
import * as monaco from 'monaco-editor/esm/vs/editor/editor.api'
import 'monaco-editor/esm/vs/editor/editor.all.js'
import 'monaco-editor/esm/vs/language/json/monaco.contribution'
import EditorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker&inline'
import JsonWorker from 'monaco-editor/esm/vs/language/json/json.worker?worker&inline'
import subgroupSchema from '../schemas/subgroup.schema.json' with { type: 'json' }
import { SUBGROUP_SCHEMA_ID } from '../lib/types'

// eslint-disable-next-line @typescript-eslint/no-explicit-any
(self as any).MonacoEnvironment = {
  getWorker(_workerId: string, label: string): Worker {
    if (label === 'json') return new JsonWorker()
    return new EditorWorker()
  },
}

// 注册 subgroup schema（仅 json 语言服务 + 基础主题）
monaco.languages.json.jsonDefaults.setDiagnosticsOptions({
  validate: true,
  allowComments: false,
  enableSchemaRequest: false,
  schemas: [{ uri: SUBGROUP_SCHEMA_ID, fileMatch: ['*'], schema: subgroupSchema as object }],
})

export interface MonacoApi {
  reveal: (line: number, column?: number) => void
}

export function MonacoPane({
  value,
  onChange,
  onSave,
  onReady,
}: {
  value: string
  onChange: (next: string) => void
  onSave: () => void
  onReady?: (api: MonacoApi) => void
}) {
  const hostRef = useRef<HTMLDivElement>(null)
  const editorRef = useRef<monaco.editor.IStandaloneCodeEditor | null>(null)
  const onChangeRef = useRef(onChange)
  onChangeRef.current = onChange

  useEffect(() => {
    if (!hostRef.current) return
    const editor = monaco.editor.create(hostRef.current, {
      value,
      language: 'json',
      theme: 'vs',
      automaticLayout: true,
      minimap: { enabled: false },
      fontSize: 13,
      tabSize: 2,
      scrollBeyondLastLine: false,
      renderWhitespace: 'none',
      quickSuggestions: true,
      suggest: { showWords: false },
    })
    editorRef.current = editor

    editor.onDidChangeModelContent(() => onChangeRef.current(editor.getValue()))
    editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, () => onSave())

    onReady?.({
      reveal: (line, column = 1) => {
        editor.revealLineInCenter(line)
        editor.setPosition({ lineNumber: line, column })
        editor.focus()
      },
    })

    return () => {
      editor.dispose()
      editorRef.current = null
    }
    // 仅挂载时创建；外部 value 变化走下方同步 effect
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    const editor = editorRef.current
    if (!editor) return
    if (editor.getValue() !== value && document.activeElement?.tagName !== 'TEXTAREA') {
      // 用 executeEdits 保留 undo 栈
      editor.executeEdits('external', [
        { range: editor.getModel()!.getFullModelRange(), text: value, forceMoveMarkers: true },
      ])
    }
  }, [value])

  return <div ref={hostRef} className="monaco-host h-full w-full" />
}
