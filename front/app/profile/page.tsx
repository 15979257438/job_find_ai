'use client'

import { useEffect, useState } from 'react'
import { motion, AnimatePresence } from 'framer-motion'
import { BiPlus, BiTrash, BiEdit, BiX, BiCheckCircle, BiUserCircle } from 'react-icons/bi'
import { Card } from '@/components/ui/card'
import { Button } from '@/components/ui/button'

const API = (typeof process !== 'undefined' && process.env?.API_BASE_URL) || 'http://localhost:8888'

interface Profile {
  id: number; name?: string; phone?: string; email?: string; city?: string
  expected_city_json?: string; expected_salary_min_k?: number; expected_salary_max_k?: number
  summary?: string; skills_json?: string; certificates_json?: string; languages_json?: string
  education_json?: string; experiences_json?: string; projects_json?: string
  conflict_log?: string; last_merged_resume_id?: number; last_merged_at?: string
}
interface Extension {
  id: number; competitions_json?: string; awards_json?: string; open_source_json?: string
  side_projects_json?: string; papers_json?: string; public_speaking_json?: string; other_facts_json?: string
}
interface Resume { id: number; name: string }

type ArrayField = 'competitions' | 'awards' | 'open_source' | 'side_projects' | 'papers' | 'public_speaking' | 'other_facts'

const EXT_LABELS: Record<ArrayField, string> = {
  competitions: '竞赛',
  awards: '获奖',
  open_source: '开源项目',
  side_projects: '副业项目',
  papers: '论文',
  public_speaking: '公开演讲',
  other_facts: '其他事实'
}

// 每个段的字段 schema（用于表单渲染 + 校验）
const EXT_SCHEMA: Record<Exclude<ArrayField, 'other_facts'>, { key: string; label: string; type: 'text' | 'number'; required?: boolean }[]> = {
  competitions: [
    { key: 'name', label: '名称', type: 'text', required: true },
    { key: 'level', label: '级别', type: 'text', required: true },
    { key: 'year', label: '年份', type: 'number' },
    { key: 'role', label: '角色', type: 'text' }
  ],
  awards: [
    { key: 'name', label: '奖项', type: 'text', required: true },
    { key: 'level', label: '级别', type: 'text' },
    { key: 'year', label: '年份', type: 'number' }
  ],
  open_source: [
    { key: 'name', label: '项目名', type: 'text', required: true },
    { key: 'role', label: '角色', type: 'text' },
    { key: 'stars', label: 'Stars', type: 'number' },
    { key: 'desc', label: '简介', type: 'text' }
  ],
  side_projects: [
    { key: 'name', label: '项目名', type: 'text', required: true },
    { key: 'role', label: '角色', type: 'text' },
    { key: 'desc', label: '简介', type: 'text' }
  ],
  papers: [
    { key: 'title', label: '论文标题', type: 'text', required: true },
    { key: 'venue', label: '发表场所', type: 'text' },
    { key: 'year', label: '年份', type: 'number' }
  ],
  public_speaking: [
    { key: 'title', label: '演讲主题', type: 'text', required: true },
    { key: 'venue', label: '场所', type: 'text' },
    { key: 'year', label: '年份', type: 'number' }
  ]
}

function jsonKeyFor(field: ArrayField): string {
  return field === 'other_facts' ? 'other_facts_json' : `${field}_json`
}

// 格式化一个扩展项用于展示：name · level · year · role
function fmtItem(item: any, field: ArrayField): string {
  if (typeof item === 'string') return item
  if (field === 'other_facts') return String(item)
  const order = ['name', 'title', 'level', 'venue', 'year', 'role', 'stars', 'desc', 'description']
  const parts: string[] = []
  for (const k of order) {
    const v = item[k]
    if (v !== undefined && v !== null && String(v).trim() !== '') parts.push(String(v))
  }
  for (const k of Object.keys(item)) {
    if (order.includes(k)) continue
    const v = item[k]
    if (v !== undefined && v !== null && String(v).trim() !== '') parts.push(`${k}=${v}`)
  }
  return parts.join(' · ')
}

export default function ProfilePage() {
  const [profile, setProfile] = useState<Profile | null>(null)
  const [extension, setExtension] = useState<Extension | null>(null)
  const [diff, setDiff] = useState<any>(null)
  const [logs, setLogs] = useState<any[]>([])
  const [resumes, setResumes] = useState<Resume[]>([])
  const [resumeId, setResumeId] = useState<number | null>(null)
  const [msg, setMsg] = useState<{ type: 'info' | 'success' | 'error'; text: string } | null>(null)
  const [busy, setBusy] = useState(false)
  const [extEditor, setExtEditor] = useState<{
    open: boolean; field: ArrayField | null; index: number | null; draft: any
  }>({ open: false, field: null, index: null, draft: {} })
  const [activeTab, setActiveTab] = useState<'basics' | 'extensions' | 'merge' | 'logs'>('basics')

  async function load() {
    try {
      const r = await fetch(`${API}/api/profile`).then(r => r.json())
      if (r.success) { setProfile(r.data.profile); setExtension(r.data.extension) }
      const df = await fetch(`${API}/api/profile/diff${resumeId ? '?resumeId=' + resumeId : ''}`).then(r => r.json())
      if (df.success) setDiff(df.data)
      const l = await fetch(`${API}/api/profile/change-log`).then(r => r.json())
      if (l.success) setLogs(l.data)
      const rl = await fetch(`${API}/api/resume/list`).then(r => r.json())
      if (rl.success) setResumes(rl.data)
    } catch (e: any) { toast('error', '加载失败: ' + e.message) }
  }
  useEffect(() => { load() }, [resumeId])

  function toast(type: 'info' | 'success' | 'error', text: string) {
    setMsg({ type, text }); setTimeout(() => setMsg(null), 4000)
  }

  async function saveBasics() {
    if (!profile) return
    if (!profile.name || !profile.phone || !profile.email) { toast('error', '姓名/手机/邮箱 为必填'); return }
    setBusy(true)
    const r = await fetch(`${API}/api/profile`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: profile.name, phone: profile.phone, email: profile.email, city: profile.city,
        expected_city: safeParse(profile.expected_city_json, []),
        expected_salary_min_k: profile.expected_salary_min_k,
        expected_salary_max_k: profile.expected_salary_max_k,
        summary: profile.summary,
        skills: safeParse(profile.skills_json, []),
        certificates: safeParse(profile.certificates_json, []),
        languages: safeParse(profile.languages_json, []),
        education: safeParse(profile.education_json, []),
        experiences: safeParse(profile.experiences_json, []),
        projects: safeParse(profile.projects_json, [])
      })
    }).then(r => r.json())
    if (r.success) { toast('success', '已保存'); load() } else toast('error', r.message || '保存失败')
    setBusy(false)
  }

  async function resolveConflict(field: string, profileVal: string) {
    const r = await fetch(`${API}/api/profile/resolve-conflict`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ field, value: profileVal })
    }).then(r => r.json())
    if (r.success) { toast('success', '已采用 Profile 值并清冲突'); load() }
    else toast('error', r.message || '操作失败')
  }

  function openExtensionEditor(field: ArrayField, index: number | null) {
    const jsonKey = jsonKeyFor(field)
    const items = safeParse((extension as any)?.[jsonKey] || '[]', [])
    const draft = index === null
      ? (field === 'other_facts' ? '' : {})
      : items[index]
    setExtEditor({ open: true, field, index, draft })
  }

  function closeEditor() {
    setExtEditor({ open: false, field: null, index: null, draft: {} })
  }

  // 内部：把当前编辑器的 draft 写回对应段的数组
  async function writeExtensionItems(field: ArrayField, items: any[]) {
    const jsonKey = jsonKeyFor(field)
    setBusy(true)
    try {
      const r = await fetch(`${API}/api/profile/extension`, {
        method: 'PUT', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ [jsonKey]: JSON.stringify(items) })
      }).then(r => r.json())
      if (r.success) {
        toast('success', `已更新 ${EXT_LABELS[field]}`)
        await load()
      } else toast('error', r.message || '保存失败')
    } finally { setBusy(false) }
  }

  async function saveExtension() {
    if (!extEditor.field) return
    const field = extEditor.field
    const jsonKey = jsonKeyFor(field)
    const items = safeParse((extension as any)?.[jsonKey] || '[]', [])
    let draft = extEditor.draft

    // 校验：other_facts 接受任意非空字符串；其他段必须是对象且必填字段非空
    if (field === 'other_facts') {
      const txt = String(draft || '').trim()
      if (!txt) { toast('error', '请输入内容'); return }
      draft = txt
    } else {
      const schema = EXT_SCHEMA[field]
      for (const f of schema) {
        if (f.required && (draft[f.key] === undefined || draft[f.key] === null || String(draft[f.key]).trim() === '')) {
          toast('error', `${f.label} 不能为空`)
          return
        }
      }
      // 清理空字段
      Object.keys(draft).forEach(k => {
        if (draft[k] === '' || draft[k] === undefined || draft[k] === null) delete draft[k]
      })
    }

    const next = [...items]
    if (extEditor.index === null) next.push(draft)
    else next[extEditor.index] = draft
    closeEditor()
    await writeExtensionItems(field, next)
  }

  async function deleteExtensionItem(field: ArrayField, index: number) {
    if (!confirm('删除该项？')) return
    const jsonKey = jsonKeyFor(field)
    const items = safeParse((extension as any)?.[jsonKey] || '[]', [])
    items.splice(index, 1)
    await writeExtensionItems(field, items)
  }

  async function clearExtension(field: ArrayField) {
    if (!confirm(`清空整个「${EXT_LABELS[field]}」？`)) return
    await writeExtensionItems(field, [])
  }

  const conflictCount = (() => { try { return JSON.parse(profile?.conflict_log || '[]').length } catch { return 0 } })()

  return (
    <div className="p-6 space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-white flex items-center gap-2">
            <BiUserCircle className="text-emerald-300" /> 个人数据库（Profile）
          </h1>
          <p className="text-xs text-waterloo mt-1">长期用户档案 · 与简历合并后用于匹配与生成</p>
        </div>
        <div className="flex gap-2 text-xs">
          <span className={`px-2 py-1 rounded border ${conflictCount > 0 ? 'bg-amber-700/30 text-amber-300 border-amber-700/40' : 'bg-emerald-700/30 text-emerald-300 border-emerald-700/40'}`}>
            冲突 {conflictCount}
          </span>
          <span className="px-2 py-1 rounded border bg-blue-700/30 text-blue-300 border-blue-700/40">
            上次合并 {diff?.last_merged_at || '从未'}
          </span>
        </div>
      </div>

      {/* 简历选择 */}
      <Card className="p-3 bg-blacksection border-strokedark">
        <div className="flex items-center gap-3">
          <label className="text-sm text-waterloo">对照简历：</label>
          <select className="bg-blackho border border-strokedark text-white p-2 rounded"
                  value={resumeId ?? ''} onChange={e => setResumeId(e.target.value ? Number(e.target.value) : null)}>
            <option value="">（不对比）</option>
            {resumes.map(r => <option key={r.id} value={r.id}>{r.name} (#{r.id})</option>)}
          </select>
          <span className="text-xs text-waterloo">选择后，下方"合并"页将显示差异</span>
        </div>
      </Card>

      {/* Tabs */}
      <div className="flex gap-1 border-b border-strokedark">
        {(['basics', 'extensions', 'merge', 'logs'] as const).map(t => (
          <button key={t} onClick={() => setActiveTab(t)}
                  className={`px-4 py-2 text-sm ${activeTab === t ? 'text-amber-300 border-b-2 border-amber-300' : 'text-waterloo hover:text-white'}`}>
            {t === 'basics' ? '基础信息' : t === 'extensions' ? '扩展段（来自对话/简历）' : t === 'merge' ? '合并视图' : '变更日志'}
          </button>
        ))}
      </div>

      {activeTab === 'basics' && profile && (
        <Card className="p-4 bg-blacksection border-strokedark">
          <h2 className="text-lg font-semibold text-white mb-3">基础信息（必填：姓名/手机/邮箱）</h2>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
            <Field label="姓名 *" v={profile.name || ''} onChange={v => setProfile({ ...profile, name: v })} />
            <Field label="手机 *" v={profile.phone || ''} onChange={v => setProfile({ ...profile, phone: v })} />
            <Field label="邮箱 *" v={profile.email || ''} onChange={v => setProfile({ ...profile, email: v })} />
            <Field label="所在城市" v={profile.city || ''} onChange={v => setProfile({ ...profile, city: v })} />
            <Field label="期望城市 JSON" v={profile.expected_city_json || '[]'} onChange={v => setProfile({ ...profile, expected_city_json: v })} hint='如 ["杭州","上海"]' />
            <Field label="期望薪资下限 K" v={String(profile.expected_salary_min_k || '')} onChange={v => setProfile({ ...profile, expected_salary_min_k: Number(v) || undefined })} />
            <Field label="期望薪资上限 K" v={String(profile.expected_salary_max_k || '')} onChange={v => setProfile({ ...profile, expected_salary_max_k: Number(v) || undefined })} />
          </div>
          <div className="mt-3">
            <label className="text-sm text-waterloo">个人简介</label>
            <textarea className="w-full mt-1 bg-blackho border border-strokedark text-white p-2 rounded"
                      value={profile.summary || ''} onChange={e => setProfile({ ...profile, summary: e.target.value })} rows={3} />
          </div>
          <div className="mt-3 grid grid-cols-1 md:grid-cols-3 gap-3">
            <Field label="技能 JSON" v={profile.skills_json || '[]'} onChange={v => setProfile({ ...profile, skills_json: v })} hint='如 ["Java","Kafka"]' />
            <Field label="证书 JSON" v={profile.certificates_json || '[]'} onChange={v => setProfile({ ...profile, certificates_json: v })} />
            <Field label="语言 JSON" v={profile.languages_json || '[]'} onChange={v => setProfile({ ...profile, languages_json: v })} />
          </div>
          <div className="mt-3 grid grid-cols-1 md:grid-cols-3 gap-3">
            <Field label="教育经历 JSON" v={profile.education_json || '[]'} onChange={v => setProfile({ ...profile, education_json: v })} />
            <Field label="工作经历 JSON" v={profile.experiences_json || '[]'} onChange={v => setProfile({ ...profile, experiences_json: v })} />
            <Field label="项目经历 JSON" v={profile.projects_json || '[]'} onChange={v => setProfile({ ...profile, projects_json: v })} />
          </div>
          <Button onClick={saveBasics} disabled={busy} className="mt-4 bg-amber-500 hover:bg-amber-600 text-black">
            保存基础信息
          </Button>
        </Card>
      )}

      {activeTab === 'extensions' && (
        <Card className="p-4 bg-blacksection border-strokedark">
          <div className="flex items-center justify-between mb-2">
            <div>
              <h2 className="text-lg font-semibold text-white">扩展段（7 项 · 来自对话 / 简历 / 手工维护）</h2>
              <p className="text-xs text-waterloo mt-1">这些段仅取自 Profile，不与简历冲突。当存在时，模板渲染与 AI 回答必须包含。</p>
            </div>
          </div>
          <div className="space-y-3 mt-4">
            {(Object.keys(EXT_LABELS) as ArrayField[]).map(field => {
              const jsonKey = jsonKeyFor(field)
              const items = safeParse((extension as any)?.[jsonKey] || '[]', [])
              return (
                <div key={field} className="border border-strokedark rounded">
                  <div className="px-3 py-2 bg-blackho/60 flex items-center justify-between border-b border-strokedark">
                    <div className="flex items-center gap-3">
                      <span className="text-white font-medium">{EXT_LABELS[field]}</span>
                      <span className="text-xs text-waterloo">{items.length} 条</span>
                    </div>
                    <div className="flex items-center gap-2">
                      <button onClick={() => openExtensionEditor(field, null)}
                              className="text-xs px-2 py-1 bg-amber-500 text-black rounded hover:bg-amber-600">
                        <BiPlus className="inline" /> 新增
                      </button>
                      {items.length > 0 && (
                        <button onClick={() => clearExtension(field)}
                                className="text-xs px-2 py-1 bg-red-700/40 text-red-200 rounded hover:bg-red-700/60">
                          清空
                        </button>
                      )}
                    </div>
                  </div>
                  {items.length === 0 ? (
                    <div className="p-3 text-xs text-waterloo italic">暂无 — 点击右上角「新增」开始维护</div>
                  ) : (
                    <table className="w-full text-sm">
                      <thead className="text-xs uppercase text-waterloo bg-blackho/40">
                        <tr>
                          {field === 'other_facts' ? (
                            <th className="text-left px-3 py-2">内容</th>
                          ) : (
                            (EXT_SCHEMA as any)[field].map((f: any) => (
                              <th key={f.key} className="text-left px-3 py-2">{f.label}</th>
                            ))
                          )}
                          <th className="text-right px-3 py-2 w-24">操作</th>
                        </tr>
                      </thead>
                      <tbody>
                        {items.map((it: any, i: number) => (
                          <tr key={i} className="border-t border-strokedark/40 hover:bg-blackho/30">
                            {field === 'other_facts' ? (
                              <td className="px-3 py-2 text-manatee">{String(it)}</td>
                            ) : (
                              (EXT_SCHEMA as any)[field].map((f: any) => (
                                <td key={f.key} className="px-3 py-2 text-manatee">{it[f.key] || <span className="text-waterloo">—</span>}</td>
                              ))
                            )}
                            <td className="px-3 py-2 text-right">
                              <button onClick={() => openExtensionEditor(field, i)}
                                      className="text-xs text-amber-300 hover:underline mr-2"><BiEdit /></button>
                              <button onClick={() => deleteExtensionItem(field, i)}
                                      className="text-xs text-red-300 hover:underline"><BiTrash /></button>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  )}
                </div>
              )
            })}
          </div>
        </Card>
      )}

      {activeTab === 'merge' && (
        <>
          {diff && diff.differences && diff.differences.length > 0 ? (
            <Card className="p-4 bg-blacksection border-strokedark">
              <h2 className="text-lg font-semibold text-white mb-3 flex items-center gap-2">
                <BiAlertTriangle className="text-amber-300" /> Resume ↔ Profile 差异（{diff.differences.length}）
              </h2>
              <ul className="space-y-2">
                {diff.differences.map((d: any, i: number) => (
                  <li key={i} className="border-b border-strokedark/40 py-2 text-sm">
                    <div className="flex justify-between items-center mb-1">
                      <span className="text-amber-300 font-medium">{d.field}</span>
                      <span className={`text-xs px-2 py-0.5 rounded ${
                        d.status === 'conflict' ? 'bg-red-700/30 text-red-300' :
                        d.status === 'profile_only' ? 'bg-emerald-700/30 text-emerald-300' :
                        'bg-blue-700/30 text-blue-300'}`}>
                        {d.status === 'conflict' ? '冲突' : d.status === 'profile_only' ? '仅 Profile' : '仅 Resume'}
                      </span>
                    </div>
                    <div className="text-manatee grid grid-cols-2 gap-2">
                      <div><span className="text-waterloo text-xs">Resume：</span>{d.resume || <em className="text-waterloo">空</em>}</div>
                      <div><span className="text-waterloo text-xs">Profile：</span>{d.profile || <em className="text-waterloo">空</em>}</div>
                    </div>
                    {d.status === 'conflict' && (
                      <button onClick={() => resolveConflict(d.field.replace('basics.', ''), d.profile)}
                              className="mt-2 px-2 py-1 text-xs bg-amber-500 text-black rounded">
                        采用 Profile 值
                      </button>
                    )}
                  </li>
                ))}
              </ul>
            </Card>
          ) : (
            <Card className="p-4 bg-blacksection border-strokedark">
              <p className="text-waterloo text-sm">选择一份简历后此处将显示 Resume ↔ Profile 差异。</p>
            </Card>
          )}

          <Card className="p-4 bg-blacksection border-strokedark">
            <h2 className="text-lg font-semibold text-white mb-3">合并视图预览（与简历合并后）</h2>
            <pre className="bg-blackho text-manatee text-xs p-3 rounded overflow-x-auto max-h-[500px]">
              {JSON.stringify(diff?.merged_view || (profile && extension ? { ...profile, extension } : {}), null, 2)}
            </pre>
          </Card>
        </>
      )}

      {activeTab === 'logs' && (
        <Card className="p-4 bg-blacksection border-strokedark">
          <h2 className="text-lg font-semibold text-white mb-3 flex items-center gap-2">
            <BiHistory /> 变更日志（{logs.length}）
          </h2>
          <div className="space-y-2 max-h-[500px] overflow-y-auto">
            {logs.length === 0 && <p className="text-waterloo">暂无变更</p>}
            {logs.map((l: any) => (
              <div key={l.id} className="border-b border-strokedark/40 py-2 text-sm">
                <div className="flex items-center gap-2 mb-1">
                  <span className={`px-1.5 py-0.5 rounded text-xs ${l.source === 'AI_CHAT' ? 'bg-purple-700/30 text-purple-300' : l.source === 'RESUME_PARSE' ? 'bg-blue-700/30 text-blue-300' : 'bg-gray-700/30 text-gray-300'}`}>{l.source}</span>
                  <span className="text-waterloo text-xs">{l.created_at}</span>
                  {l.session_id && <Link className="text-xs text-amber-300 hover:underline" href={`/ai-chat`}>会话 #{l.session_id}</Link>}
                </div>
                {l.change_json && <pre className="text-xs text-manatee mt-1 whitespace-pre-wrap bg-blackho p-2 rounded">{l.change_json}</pre>}
              </div>
            ))}
          </div>
        </Card>
      )}

      {msg && (
        <motion.div initial={{ y: 20, opacity: 0 }} animate={{ y: 0, opacity: 1 }}
                    className={`fixed bottom-4 right-4 p-3 rounded text-sm shadow-lg ${
                      msg.type === 'success' ? 'bg-emerald-700 text-white' :
                      msg.type === 'error' ? 'bg-red-700 text-white' :
                      'bg-blue-700 text-white'}`}>
          {msg.text}
        </motion.div>
      )}

      <AnimatePresence>
      {extEditor.open && extEditor.field && (
        <div className="fixed inset-0 bg-black/70 z-50 flex items-center justify-center p-4">
          <motion.div initial={{ scale: 0.95, opacity: 0 }} animate={{ scale: 1, opacity: 1 }} exit={{ opacity: 0 }}
                      className="bg-blacksection border border-strokedark rounded-lg w-full max-w-lg overflow-hidden flex flex-col">
            <div className="p-4 border-b border-strokedark flex items-center justify-between">
              <h3 className="text-lg font-semibold text-white">
                {extEditor.index === null ? '新增' : '编辑'} · {EXT_LABELS[extEditor.field]}
              </h3>
              <button onClick={closeEditor} className="text-waterloo hover:text-white"><BiX className="text-xl" /></button>
            </div>
            <div className="p-4 space-y-3">
              {extEditor.field === 'other_facts' ? (
                <div>
                  <label className="text-sm text-waterloo">内容</label>
                  <textarea value={String(extEditor.draft || '')}
                            onChange={e => setExtEditor({ ...extEditor, draft: e.target.value })}
                            rows={4}
                            placeholder="例如：连续两年获校级一等奖学金"
                            className="w-full mt-1 bg-blackho border border-strokedark text-white p-2 rounded text-sm" />
                </div>
              ) : (
                EXT_SCHEMA[extEditor.field].map(f => (
                  <div key={f.key}>
                    <label className="text-sm text-waterloo">
                      {f.label}{f.required && <span className="text-red-400 ml-1">*</span>}
                    </label>
                    <input
                      type={f.type}
                      value={extEditor.draft?.[f.key] ?? ''}
                      onChange={e => setExtEditor({
                        ...extEditor,
                        draft: { ...(extEditor.draft || {}), [f.key]: f.type === 'number' ? (e.target.value === '' ? '' : Number(e.target.value)) : e.target.value }
                      })}
                      className="w-full mt-1 bg-blackho border border-strokedark text-white p-2 rounded text-sm"
                    />
                  </div>
                ))
              )}
            </div>
            <div className="p-3 border-t border-strokedark flex justify-end gap-2">
              <Button onClick={closeEditor} className="bg-strokedark text-white">取消</Button>
              <Button onClick={saveExtension} disabled={busy} className="bg-amber-500 text-black hover:bg-amber-600">
                <BiCheckCircle /> 保存
              </Button>
            </div>
          </motion.div>
        </div>
      )}
      </AnimatePresence>
    </div>
  )
}

function Field({ label, v, onChange, hint }: { label: string; v: string; onChange: (s: string) => void; hint?: string }) {
  return (
    <div>
      <label className="text-sm text-waterloo">{label}</label>
      <input className="w-full mt-1 bg-blackho border border-strokedark text-white p-2 rounded text-sm"
             value={v} onChange={e => onChange(e.target.value)} />
      {hint && <p className="text-xs text-waterloo mt-1">{hint}</p>}
    </div>
  )
}

function safeParse(s: string | undefined, fallback: any) {
  try { return JSON.parse(s || '[]') } catch { return fallback }
}