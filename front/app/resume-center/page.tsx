'use client'

import { useEffect, useState, useRef, useMemo } from 'react'
import { motion } from 'framer-motion'
import { BiUpload, BiTrash, BiRefresh, BiEdit, BiX, BiFile, BiCheckCircle, BiXCircle, BiTime, BiDownload } from 'react-icons/bi'
import { Card } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { RESUME_TEMPLATES } from '@/lib/resume-templates'

const API = (typeof process !== 'undefined' && process.env?.API_BASE_URL) || 'http://localhost:8888'

interface Resume {
  id: number; name: string; original_filename: string; parse_status: string
  is_manually_edited: number; template_count: number; job_specific_count: number
  created_at: string; updated_at: string; parsed_json?: string
  versions?: { id: number; type: string; template_id: string; rendered_pdf_path: string; rendered_html_path: string }[]
}
interface Template { id: string; name: string; description: string; preview?: string }

export default function ResumeCenterPage() {
  const [resumes, setResumes] = useState<Resume[]>([])
  const [templates, setTemplates] = useState<Template[]>([])
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState<{ type: 'info' | 'success' | 'error'; text: string } | null>(null)
  const [previewHtml, setPreviewHtml] = useState<string>('')
  const [previewTpl, setPreviewTpl] = useState<string>('editorial-dark-v1')
  const [editor, setEditor] = useState<{ open: boolean; resume: Resume | null; jsonText: string }>({ open: false, resume: null, jsonText: '' })
  const [versionModal, setVersionModal] = useState<{ open: boolean; resume: Resume | null }>({ open: false, resume: null })
  const fileRef = useRef<HTMLInputElement | null>(null)

  async function loadList() {
    const r = await fetch(`${API}/api/resume/list`).then(r => r.json())
    if (r.success) setResumes(r.data)
  }
  async function loadTemplates() {
    const r = await fetch(`${API}/api/resume/templates`).then(r => r.json())
    if (r.success) setTemplates(r.data)
  }
  useEffect(() => { loadList(); loadTemplates() }, [])

  async function handleUpload() {
    const f = fileRef.current?.files?.[0]
    if (!f) { toast('info', '请选择文件'); return }
    if (f.size > 10 * 1024 * 1024) { toast('error', '文件超过 10MB'); return }
    const ext = f.name.toLowerCase().split('.').pop()
    if (!['pdf', 'docx', 'doc'].includes(ext)) { toast('error', '仅支持 .pdf / .docx / .doc'); return }
    setBusy(true); toast('info', '上传中...')
    const fd = new FormData(); fd.append('file', f)
    try {
      const r = await fetch(`${API}/api/resume/upload`, { method: 'POST', body: fd })
      const d = await r.json()
      if (!d.success) { toast('error', d.message || '上传失败'); return }
      if (d.data.reused) { toast('success', `文件已复用（id=${d.data.id}）`); loadList(); return }
      toast('info', `上传成功，解析中（id=${d.data.id}）`)
      loadList()
      // 轮询
      const id = d.data.id
      let cnt = 0
      const ti = setInterval(async () => {
        cnt++
        const lr = await fetch(`${API}/api/resume/${id}`).then(r => r.json())
        if (lr.success && (lr.data.parse_status === 'PARSED' || lr.data.parse_status === 'PARSE_FAILED' || cnt > 40)) {
          clearInterval(ti)
          loadList()
          if (lr.data.parse_status === 'PARSED') toast('success', '解析完成')
          else if (lr.data.parse_status === 'PARSE_FAILED') toast('error', '解析失败，可点击「重新解析」')
          else if (cnt > 40) toast('info', '解析超时')
        }
      }, 1500)
    } catch (e: any) { toast('error', '上传异常: ' + e.message) }
    finally { setBusy(false) }
  }

  async function handleRetry(id: number) {
    setBusy(true); toast('info', '重新解析中...')
    const r = await fetch(`${API}/api/resume/${id}/retry-parse`, { method: 'POST' }).then(r => r.json())
    if (r.success) {
      toast('success', `已触发解析（状态：${r.data.parse_status}）`)
      loadList()
    } else toast('error', r.message || '重试失败')
    setBusy(false)
  }

  async function handlePreview(id: number) {
    toast('info', '正在渲染预览...')
    try {
      const r = await fetch(`${API}/api/resume/${id}/preview`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ template_id: previewTpl })
      })
      const d = await r.json()
      if (d.success) { setPreviewHtml(d.data.html); toast('success', '预览已更新（合并视图）') }
      else toast('error', d.message)
    } catch (e: any) { toast('error', '预览失败: ' + e.message) }
  }

  function openEditor(r: Resume) {
    let txt = ''
    try { txt = JSON.stringify(JSON.parse(r.parsed_json || '{}'), null, 2) } catch { txt = r.parsed_json || '{}' }
    setEditor({ open: true, resume: r, jsonText: txt })
  }
  async function saveEditor() {
    if (!editor.resume) return
    // 前端预校验必填
    try {
      const obj = JSON.parse(editor.jsonText)
      const missing: string[] = []
      if (!obj.basics?.name) missing.push('姓名')
      if (!obj.basics?.phone) missing.push('手机')
      if (!obj.basics?.email) missing.push('邮箱')
      if (missing.length > 0) { toast('error', `必填字段为空：${missing.join(' / ')}`); return }
    } catch (e: any) { toast('error', 'JSON 解析失败: ' + e.message); return }

    setBusy(true)
    const r = await fetch(`${API}/api/resume/${editor.resume.id}/parsed-json`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ parsed_json: editor.jsonText })
    }).then(r => r.json())
    if (r.success) { toast('success', '已保存（is_manually_edited=1）'); setEditor({ open: false, resume: null, jsonText: '' }); loadList() }
    else toast('error', r.message || '保存失败')
    setBusy(false)
  }

  async function handleDelete(id: number) {
    if (!confirm(`确认删除简历 #${id}? 若已被投递引用将失败`)) return
    const r = await fetch(`${API}/api/resume/${id}`, { method: 'DELETE' }).then(r => r.json())
    if (r.success) { toast('success', '已删除'); loadList() }
    else toast('error', r.message || '删除失败（可能已被引用）')
  }

  async function openVersions(r: Resume) {
    const full = await fetch(`${API}/api/resume/${r.id}`).then(r => r.json())
    if (full.success) setVersionModal({ open: true, resume: full.data })
    else toast('error', '无法加载版本')
  }

  function toast(type: 'info' | 'success' | 'error', text: string) {
    setMsg({ type, text })
    setTimeout(() => setMsg(null), 4000)
  }

  const stats = useMemo(() => ({
    total: resumes.length,
    parsed: resumes.filter(r => r.parse_status === 'PARSED').length,
    failed: resumes.filter(r => r.parse_status === 'PARSE_FAILED').length,
    edited: resumes.filter(r => r.is_manually_edited === 1).length
  }), [resumes])

  return (
    <div className="p-6 space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-white">简历中心</h1>
          <p className="text-xs text-waterloo mt-1">上传简历 → AI 解析 → 选择模板预览 → 生成岗位定制版</p>
        </div>
        <div className="flex gap-2 text-xs">
          <Badge label="总数" v={stats.total} color="amber" />
          <Badge label="已解析" v={stats.parsed} color="emerald" />
          <Badge label="解析失败" v={stats.failed} color="red" />
          <Badge label="已手工编辑" v={stats.edited} color="purple" />
        </div>
      </div>

      {/* 上传区 */}
      <Card className="p-4 bg-blacksection border-strokedark">
        <h2 className="text-sm font-semibold text-white mb-3 uppercase tracking-wide">上传新简历</h2>
        <div className="flex flex-col md:flex-row md:items-center gap-3">
          <input ref={fileRef as any} type="file" accept=".pdf,.docx,.doc" className="md:w-96 text-sm text-manatee" />
          <Button onClick={handleUpload} disabled={busy} className="bg-amber-500 hover:bg-amber-600 text-black">
            <BiUpload className="mr-1" /> {busy ? '上传中...' : '上传简历'}
          </Button>
          <span className="text-xs text-waterloo">支持 PDF/DOCX/DOC · ≤10MB · 同一 SHA 自动去重</span>
        </div>
      </Card>

      {/* 模板选择 */}
      <Card className="p-4 bg-blacksection border-strokedark">
        <h2 className="text-sm font-semibold text-white mb-3 uppercase tracking-wide">选择预览模板</h2>
        <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-3">
          {RESUME_TEMPLATES.map(t => (
            <motion.button key={t.id} whileHover={{ y: -2 }} onClick={() => setPreviewTpl(t.id)}
                          className={`text-left p-2 rounded border ${previewTpl === t.id ? 'border-amber-500 ring-2 ring-amber-500/40' : 'border-strokedark hover:border-amber-500/60'}`}>
              <img src={t.preview} alt={t.name} className="w-full h-32 object-cover rounded mb-2 bg-white" />
              <div className="text-xs font-semibold text-white">{t.name}</div>
              <div className="text-[10px] text-waterloo leading-tight">{t.description}</div>
            </motion.button>
          ))}
        </div>
      </Card>

      {/* 简历列表 */}
      <Card className="p-4 bg-blacksection border-strokedark">
        <h2 className="text-lg font-semibold text-white mb-3">简历列表（{resumes.length}）</h2>
        {resumes.length === 0 ? <p className="text-waterloo">暂无简历，请先上传。</p> : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm text-left text-manatee">
              <thead className="text-xs uppercase text-waterloo border-b border-strokedark">
                <tr>
                  <th className="py-2 pr-3">ID</th>
                  <th className="py-2 pr-3">名称</th>
                  <th className="py-2 pr-3">状态</th>
                  <th className="py-2 pr-3">版本数</th>
                  <th className="py-2 pr-3">编辑标记</th>
                  <th className="py-2 pr-3">更新时间</th>
                  <th className="py-2 pr-3">操作</th>
                </tr>
              </thead>
              <tbody>
                {resumes.map(r => (
                  <tr key={r.id} className="border-b border-strokedark/40">
                    <td className="py-2 pr-3">{r.id}</td>
                    <td className="py-2 pr-3">
                      <div className="text-white">{r.name}</div>
                      <div className="text-xs text-waterloo">{r.original_filename}</div>
                    </td>
                    <td className="py-2 pr-3">
                      <StatusBadge status={r.parse_status} />
                    </td>
                    <td className="py-2 pr-3 text-xs">
                      <div>T:{r.template_count}</div>
                      <div>J:{r.job_specific_count}</div>
                    </td>
                    <td className="py-2 pr-3">
                      {r.is_manually_edited === 1 ? <BiEdit className="text-purple-300" /> : <span className="text-waterloo text-xs">—</span>}
                    </td>
                    <td className="py-2 pr-3 text-xs text-waterloo">{r.updated_at}</td>
                    <td className="py-2 pr-3 space-x-1">
                      <button onClick={() => handlePreview(r.id)} className="px-2 py-1 bg-amber-500/20 text-amber-300 rounded text-xs hover:bg-amber-500/30">预览</button>
                      <button onClick={() => openEditor(r)} disabled={r.parse_status !== 'PARSED'} className="px-2 py-1 bg-purple-500/20 text-purple-300 rounded text-xs hover:bg-purple-500/30 disabled:opacity-30">编辑</button>
                      <button onClick={() => openVersions(r)} className="px-2 py-1 bg-cyan-500/20 text-cyan-300 rounded text-xs hover:bg-cyan-500/30">版本</button>
                      {r.parse_status === 'PARSE_FAILED' && (
                        <button onClick={() => handleRetry(r.id)} className="px-2 py-1 bg-blue-500/20 text-blue-300 rounded text-xs hover:bg-blue-500/30">重试</button>
                      )}
                      <button onClick={() => handleDelete(r.id)} className="px-2 py-1 bg-red-500/20 text-red-300 rounded text-xs hover:bg-red-500/30">删除</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      {/* 预览区 */}
      {previewHtml && (
        <Card className="p-4 bg-blacksection border-strokedark">
          <div className="flex items-center justify-between mb-3">
            <h2 className="text-lg font-semibold text-white">预览（合并视图 · {previewTpl}）</h2>
            <button onClick={() => setPreviewHtml('')} className="text-xs text-waterloo hover:text-red-300">关闭预览</button>
          </div>
          <p className="text-xs text-waterloo mb-2">注：此预览已合并 Profile 数据（"来自个人数据库"段即来自 Profile）。</p>
          <iframe srcDoc={previewHtml} className="w-full h-[800px] bg-white rounded" />
        </Card>
      )}

      {/* Toast */}
      {msg && (
        <motion.div initial={{ y: 20, opacity: 0 }} animate={{ y: 0, opacity: 1 }}
                    className={`fixed bottom-4 right-4 p-3 rounded text-sm shadow-lg ${
                      msg.type === 'success' ? 'bg-emerald-700 text-white' :
                      msg.type === 'error' ? 'bg-red-700 text-white' :
                      'bg-blue-700 text-white'}`}>
          {msg.text}
        </motion.div>
      )}

      {/* 编辑器弹窗 */}
      {editor.open && (
        <Modal title={`手工编辑简历 #${editor.resume?.id} · ${editor.resume?.name}`} onClose={() => setEditor({ open: false, resume: null, jsonText: '' })}>
          <p className="text-xs text-waterloo mb-2">直接编辑 JSON。必填：basics.name / basics.phone / basics.email</p>
          <textarea value={editor.jsonText} onChange={e => setEditor({ ...editor, jsonText: e.target.value })}
                    className="w-full h-[60vh] bg-blackho border border-strokedark text-manatee text-xs p-3 rounded font-mono" />
          <div className="mt-3 flex justify-end gap-2">
            <Button onClick={() => setEditor({ open: false, resume: null, jsonText: '' })} className="bg-strokedark text-white">取消</Button>
            <Button onClick={saveEditor} disabled={busy} className="bg-amber-500 text-black hover:bg-amber-600">保存</Button>
          </div>
        </Modal>
      )}

      {/* 版本详情弹窗 */}
      {versionModal.open && versionModal.resume && (
        <Modal title={`版本列表 · 简历 #${versionModal.resume.id}`} onClose={() => setVersionModal({ open: false, resume: null })}>
          {versionModal.resume.versions && versionModal.resume.versions.length > 0 ? (
            <ul className="space-y-2">
              {versionModal.resume.versions.map(v => (
                <li key={v.id} className="p-3 bg-blackho border border-strokedark rounded flex items-center justify-between">
                  <div>
                    <span className={`px-2 py-0.5 rounded text-xs mr-2 ${v.type === 'MASTER' ? 'bg-blue-700/30 text-blue-300' : v.type === 'TEMPLATE' ? 'bg-purple-700/30 text-purple-300' : 'bg-amber-700/30 text-amber-300'}`}>{v.type}</span>
                    <span className="text-manatee text-sm">{v.template_id || '(默认)'}</span>
                  </div>
                  <div className="flex gap-2 text-xs">
                    {v.rendered_pdf_path && <span className="text-emerald-300">PDF ✓</span>}
                    {v.rendered_html_path && <span className="text-blue-300">HTML ✓</span>}
                  </div>
                </li>
              ))}
            </ul>
          ) : <p className="text-waterloo">暂无版本记录</p>}
        </Modal>
      )}
    </div>
  )
}

function StatusBadge({ status }: { status: string }) {
  const map: Record<string, { cls: string; icon: any }> = {
    PARSED: { cls: 'bg-emerald-700/30 text-emerald-300', icon: <BiCheckCircle /> },
    PARSE_FAILED: { cls: 'bg-red-700/30 text-red-300', icon: <BiXCircle /> },
    PENDING: { cls: 'bg-yellow-700/30 text-yellow-300', icon: <BiTime /> },
    PARSING: { cls: 'bg-blue-700/30 text-blue-300', icon: <BiRefresh className="animate-spin" /> }
  }
  const cfg = map[status] || map.PENDING
  return <span className={`px-2 py-0.5 rounded text-xs inline-flex items-center gap-1 ${cfg.cls}`}>{cfg.icon} {status}</span>
}

function Badge({ label, v, color }: { label: string; v: number; color: 'amber' | 'emerald' | 'red' | 'purple' }) {
  const cls: Record<string, string> = {
    amber: 'bg-amber-700/30 text-amber-300 border-amber-700/40',
    emerald: 'bg-emerald-700/30 text-emerald-300 border-emerald-700/40',
    red: 'bg-red-700/30 text-red-300 border-red-700/40',
    purple: 'bg-purple-700/30 text-purple-300 border-purple-700/40'
  }
  return <span className={`px-2 py-1 rounded border ${cls[color]}`}>{label} <strong>{v}</strong></span>
}

function Modal({ title, onClose, children }: { title: string; onClose: () => void; children: React.ReactNode }) {
  return (
    <div className="fixed inset-0 bg-black/70 z-50 flex items-center justify-center p-4">
      <motion.div initial={{ scale: 0.95, opacity: 0 }} animate={{ scale: 1, opacity: 1 }} className="bg-blacksection border border-strokedark rounded-lg w-full max-w-4xl max-h-[90vh] overflow-hidden flex flex-col">
        <div className="p-4 border-b border-strokedark flex items-center justify-between">
          <h3 className="text-lg font-semibold text-white">{title}</h3>
          <button onClick={onClose} className="text-waterloo hover:text-white"><BiX className="text-xl" /></button>
        </div>
        <div className="p-4 overflow-y-auto">{children}</div>
      </motion.div>
    </div>
  )
}