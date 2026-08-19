/**
 * EquipMock 工作台主进程入口（ESM）。
 * - 单实例锁：二次启动聚焦已有窗口（06 §1）
 * - contextIsolation: true / nodeIntegration: false，渲染进程零 Node API
 * - 全部 fs 操作在主进程（ipc.ts 注册的契约处理器）
 */
import { app, BrowserWindow, Menu, shell } from 'electron'
import { fileURLToPath, pathToFileURL } from 'node:url'
import * as path from 'node:path'
import { WorkbenchIpc } from './ipc'

const __dirname = path.dirname(fileURLToPath(import.meta.url))

const gotLock = app.requestSingleInstanceLock()
if (!gotLock) {
  app.quit()
} else {
  const ipc = new WorkbenchIpc(() => app.getPath('userData'))

  app.on('second-instance', () => {
    const win = BrowserWindow.getAllWindows()[0]
    if (win) {
      if (win.isMinimized()) win.restore()
      win.focus()
    }
  })

  const createWindow = (): void => {
    const win = new BrowserWindow({
      width: 1360,
      height: 880,
      minWidth: 1080,
      minHeight: 700,
      show: false,
      autoHideMenuBar: false,
      title: 'EquipMock 工作台',
      backgroundColor: '#f5f6f8',
      webPreferences: {
        preload: path.join(__dirname, 'preload.cjs'),
        contextIsolation: true,
        nodeIntegration: false,
        sandbox: true,
        spellcheck: false,
      },
    })

    win.once('ready-to-show', () => win.show())

    // 外链交给系统浏览器，不在应用内开新窗口
    win.webContents.setWindowOpenHandler(({ url }) => {
      void shell.openExternal(url)
      return { action: 'deny' }
    })

    const devServerUrl = process.env.VITE_DEV_SERVER_URL
    if (devServerUrl) {
      void win.loadURL(devServerUrl)
      win.webContents.openDevTools({ mode: 'detach' })
    } else {
      void win.loadFile(path.join(__dirname, '../dist/index.html'))
    }
  }

  app.whenReady().then(() => {
    ipc.register()
    ipc.bootstrap()
    setupMenu()
    createWindow()

    app.on('activate', () => {
      if (BrowserWindow.getAllWindows().length === 0) createWindow()
    })
  })

  app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') app.quit()
  })
}

function setupMenu(): void {
  const isDev = Boolean(process.env.VITE_DEV_SERVER_URL)
  const template: Electron.MenuItemConstructorOptions[] = [
    {
      label: '文件',
      submenu: [
        { label: '重新加载', accelerator: 'CmdOrCtrl+R', click: () => BrowserWindow.getFocusedWindow()?.reload() },
        { type: 'separator' },
        { label: '退出', role: 'quit' },
      ],
    },
    {
      label: '编辑',
      submenu: [
        { label: '撤销', role: 'undo' },
        { label: '重做', role: 'redo' },
        { type: 'separator' },
        { label: '剪切', role: 'cut' },
        { label: '复制', role: 'copy' },
        { label: '粘贴', role: 'paste' },
        { label: '全选', role: 'selectAll' },
      ],
    },
    {
      label: '视图',
      submenu: [
        { label: '放大', role: 'zoomIn' },
        { label: '缩小', role: 'zoomOut' },
        { label: '重置缩放', role: 'resetZoom' },
        ...(isDev ? [{ type: 'separator' as const }, { label: '开发者工具', role: 'toggleDevTools' as const }] : []),
      ],
    },
    {
      label: '帮助',
      submenu: [
        {
          label: '关于 EquipMock 工作台',
          click: () => {
            const win = BrowserWindow.getFocusedWindow()
            if (win) void win.webContents.executeJavaScript(`window.alert('EquipMock 工作台 ${app.getVersion()}\\n配置文件契约 equip-mock/*@1')`)
          },
        },
      ],
    },
  ]
  Menu.setApplicationMenu(Menu.buildFromTemplate(template))
}

// 让 loadFile 的 file:// URL 在 ESM 主进程下保持简单
export const indexUrl = (dir: string): string => pathToFileURL(path.join(dir, 'index.html')).href
