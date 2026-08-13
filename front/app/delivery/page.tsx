'use client'

import { useEffect, useMemo, useState } from 'react'
import { BiTask, BiRefresh, BiListUl, BiRocket } from 'react-icons/bi'
import { Card } from '@/components/ui/card'
import { Button } from '@/components/ui/button'

const API = (typeof process !== 'undefined' && process.env?.API_BASE_URL) || 'http://localhost:8888'

interface Resume { id: number; name: string; parse_status: string }
interface DeliveryTarget {
  id: number; status: string; platform: string; jobId: string; companyName: string
  jobName: string; salary: string; location: string; matchScore: number
  scoreBreakdownJson?: string; resumeVersionId?: number; errorMessage?: string
}
interface Opt { code?: string; name: string; value?: string }

const PLATFORMS = [
  { id: 'boss',    label: 'Boss 直聘', color: 'text-amber-300 border-amber-500/40' },
  { id: 'job51',   label: '51job',    color: 'text-blue-300 border-blue-500/40' },
  { id: 'liepin',  label: '猎聘',      color: 'text-emerald-300 border-emerald-500/40' },
  { id: 'zhilian', label: '智联招聘',  color: 'text-rose-300 border-rose-500/40' },
]

// Boss 静态选项（后端 BossController 未提供 /config/options/*，沿用项目内常见配置）
const BOSS_OPTIONS = {
  city: ['北京','上海','广州','深圳','杭州','成都','武汉','南京','苏州','西安','重庆','天津','长沙','青岛','济南'],
  industry: ['互联网/IT','金融','消费/零售','广告/传媒','教育','医疗','制造业','汽车','游戏','电商'],
  experience: ['在校生','应届生','1年以内','1-3年','3-5年','5-10年','10年以上'],
  degree: ['大专','本科','硕士','博士','不限'],
  salary: ['3K以下','3-5K','5-10K','10-20K','20-50K'],
  scale: ['0-20人','20-99人','100-499人','500-999人','1000-9999人','10000人以上'],
  stage: ['未融资','天使轮','A轮','B轮','C轮','D轮及以上','已上市','不需要融资'],
  jobType: ['全职','兼职','实习','不限'],
  deadStatus: ['1月内无登录','3月内无登录','半年内无登录','近半年无动态','本月无动态'],
}

export default function DeliveryPage() {
  const [resumes, setResumes] = useState<Resume[]>([])
  const [req, setReq] = useState<any>({
    resume_id: 0, template_id: 'editorial-dark-v1',
    keywords: '数据分析', city_codes: ['武汉'], salary_min_k: 2, salary_max_k: 7,
    platforms: ['boss'], experience: '', degree: '',
    industries: [], scales: [], blacklist_keywords: '', custom_greeting: '',
    max_count: 5, mode: 'STANDARD', match_threshold: 60,
  })
  // 各平台独立配置
  const [pc, setPc] = useState<Record<string, any>>({
    boss: {
      keywords: ['数据分析'], city: '武汉', industry: ['互联网/IT'],
      experience: ['在校生'], degree: ['本科'], salary: ['3-5K'],
      scale: ['100-499人'], stage: ['A轮'], jobType: '全职',
      expectedSalaryMin: 2, expectedSalaryMax: 7,
      sayHi: '您好，我是 2026 应届生，意向数据分析岗位。',
      enableAI: true, sendImgResume: false, filterDeadHr: true,
      deadStatus: ['1月内无登录'], debugger: false, waitTime: 5,
    },
    job51: { keywords: ['数据分析'], jobArea: ['武汉'], salary: ['3-5千/月'] },
    liepin:{ keywords: ['数据分析'], city: '武汉', salary: '5-10' },
    zhilian:{ keywords: ['数据分析'], city: '武汉', salary: '0' },
  })
  const [activeTab, setActiveTab] = useState<string>('boss')
  // 各平台 options（来自后端 /api/{platform}/config/options/...）
  const [opts, setOpts] = useState<Record<string, Opt[]>>({
    liepin_city: [], liepin_salary: [],
    zhilian_city: [], zhilian_salary: [],
    job51_jobArea: [], job51_salary: [],
  })

  const [requestId, setRequestId] = useState<number | null>(null)
  const [targets, setTargets] = useState<DeliveryTarget[]>([])
  const [msg, setMsg] = useState<{ type: 'info' | 'success' | 'error'; text: string } | null>(null)
  const [busy, setBusy] = useState(false)
  const [progress, setProgress] = useState<{ ts: string; text: string }[]>([])

  // ----- 拉基础数据 -----
  async function loadResumes() {
    const r = await fetch(`${API}/api/resume/list`).then(r => r.json())
    if (r?.success) setResumes(r.data || [])
  }
  async function loadOpt(type: string, url: string) {
    try {
      const r = await fetch(`${API}${url}`).then(r => r.json())
      // 不同平台返回结构可能不同：liepin/job51 是 [{id,type,name,code}]，zhilian 是 [{code,name}]
      const arr = Array.isArray(r) ? r : (r?.data || [])
      setOpts(prev => ({ ...prev, [type]: arr.map((o: any) => ({ code: o.code, name: o.name })) }))
    } catch { /* ignore */ }
  }
  useEffect(() => {
    loadResumes()
    loadOpt('liepin_city',  '/api/liepin/config/options/city')
    loadOpt('liepin_salary','/api/liepin/config/options/salary')
    loadOpt('zhilian_city', '/api/zhilian/config/options/city')
    loadOpt('zhilian_salary','/api/zhilian/config/options/salary')
    loadOpt('job51_jobArea','/api/51job/config/options/jobArea')
    loadOpt('job51_salary', '/api/51job/config/options/salary')
  }, [])

  // ----- 校验 -----
  const validation = useMemo(() => {
    const errs: string[] = []
    const ps: string[] = req.platforms || []
    for (const p of ps) {
      const c = pc[p] || {}
      const kw: string[] = Array.isArray(c.keywords) ? c.keywords : []
      if (kw.length === 0) { errs.push(`${p} 缺少 keywords`); continue }
      if (p === 'boss') {
        if (!c.city) errs.push('boss 缺少 city')
        if (!c.jobType) errs.push('boss 缺少 jobType')
        if (!Array.isArray(c.experience) || c.experience.length === 0) errs.push('boss 缺少 experience')
        if (!Array.isArray(c.degree) || c.degree.length === 0) errs.push('boss 缺少 degree')
      } else if (p === 'job51') {
        if (!Array.isArray(c.jobArea) || c.jobArea.length === 0) errs.push('job51 缺少 jobArea')
      } else if (p === 'liepin' || p === 'zhilian') {
        if (!c.city) errs.push(`${p} 缺少 city`)
      }
    }
    return errs
  }, [req.platforms, pc])

  // ----- 工具：list 字段切换 -----
  function toggleList(field: string, platform: string, value: string) {
    setPc((prev: any) => {
      const cur = prev[platform] || {}
      const arr: string[] = Array.isArray(cur[field]) ? [...cur[field]] : []
      const i = arr.indexOf(value)
      if (i >= 0) arr.splice(i, 1); else arr.push(value)
      return { ...prev, [platform]: { ...cur, [field]: arr } }
    })
  }
  function setField(platform: string, field: string, value: any) {
    setPc((prev: any) => ({ ...prev, [platform]: { ...(prev[platform] || {}), [field]: value } }))
  }
  function setGlobalField(field: string, value: any) {
    setReq((prev: any) => ({ ...prev, [field]: value }))
  }
  function togglePlatform(id: string) {
    setReq((prev: any) => {
      const arr: string[] = [...(prev.platforms || [])]
      const i = arr.indexOf(id)
      if (i >= 0) arr.splice(i, 1); else arr.push(id)
      return { ...prev, platforms: arr }
    })
    if (!(req.platforms || []).includes(id) && !pc[id]) {
      // 首次勾选时填默认值
      setPc((prev: any) => ({
        ...prev,
        [id]: prev[id] || (id === 'boss' ? pc.boss :
                  id === 'job51' ? { keywords: pc.boss.keywords, jobArea: ['武汉'], salary: ['3-5千/月'] } :
                  { keywords: pc.boss.keywords, city: '武汉', salary: id === 'zhilian' ? '0' : '5-10' }),
      }))
    }
  }

  // ----- 操作 -----
  async function submit() {
    if (validation.length > 0) {
      setMsg({ type: 'error', text: '校验未通过：' + validation.join('；') })
      return
    }
    setBusy(true); setMsg(null)
    try {
      const payload = {
        ...req,
        keywords: req.keywords.split(/[,，]/).map((s: string) => s.trim()).filter(Boolean),
        blacklist_keywords: (req.blacklist_keywords || '').split(/[,，]/).map((s: string) => s.trim()).filter(Boolean),
        platform_configs: pc,
      }
      const r = await fetch(`${API}/api/delivery/submit`, {
        method: 'POST', headers: { 'Content-Type': 'application/json;charset=utf-8' },
        body: JSON.stringify(payload),
      }).then(r => r.json())
      if (!r?.success) { setMsg({ type: 'error', text: r?.message || '提交失败' }); return }
      setRequestId(r.data.id)
      setMsg({ type: 'success', text: `投递需求已提交 #${r.data.id}` })
    } finally { setBusy(false) }
  }
  async function pullAndMatch() {
    if (!requestId) { setMsg({ type: 'error', text: '请先提交' }); return }
    setBusy(true); setMsg(null)
    try {
      // 真实场景由 worker 拉取；演示用 seed
      const seed = [
        { platform:'boss', job_id:'B-T1', company_name:'小米科技', job_name:'数据分析实习生', salary:'3-5k', location:'武汉', jd_text:'负责用户行为数据分析，SQL/Python 加分。' },
        { platform:'boss', job_id:'B-T2', company_name:'字节跳动', job_name:'BI 数据分析师',  salary:'6-9k', location:'武汉', jd_text:'SQL/Python/Tableau 必备；A/B 测试。' },
        { platform:'boss', job_id:'B-T3', company_name:'某MCN',    job_name:'销售实习生',      salary:'4-6k', location:'武汉', jd_text:'电销陌拜。' },
      ]
      const r = await fetch(`${API}/api/delivery/${requestId}/pull-and-match`, {
        method: 'POST', headers: { 'Content-Type': 'application/json;charset=utf-8' },
        body: JSON.stringify({ jobs: seed }),
      }).then(r => r.json())
      if (r?.success) {
        setTargets(r.data)
        setMsg({ type: 'success', text: `已注入 ${r.data.length} 个岗位` })
      } else {
        setMsg({ type: 'error', text: r?.message || '拉取失败' })
      }
    } finally { setBusy(false) }
  }
  async function generateJobSpecific() {
    if (!requestId) { setMsg({ type: 'error', text: '请先提交' }); return }
    setBusy(true); setMsg(null)
    try {
      const r = await fetch(`${API}/api/delivery/${requestId}/generate-job-specific`, {
        method: 'POST', headers: { 'Content-Type': 'application/json;charset=utf-8' },
        body: JSON.stringify({ limit: 3 }),
      }).then(r => r.json())
      if (r?.success) {
        setMsg({ type: 'success', text: `已生成 ${r.data.length} 个定制版本` })
      } else setMsg({ type: 'error', text: r?.message || '生成失败' })
    } finally { setBusy(false) }
  }
  async function runDelivery() {
    if (!requestId) { setMsg({ type: 'error', text: '请先提交' }); return }
    setBusy(true); setMsg(null)
    try {
      const r = await fetch(`${API}/api/delivery/${requestId}/run`, { method: 'POST' }).then(r => r.json())
      if (r?.success) {
        setMsg({ type: 'success', text: r.message })
        pushProgress(`>>> 已派发：${r.message}`)
      } else setMsg({ type: 'error', text: r?.message || '派发失败' })
    } finally { setBusy(false) }
  }
  function pushProgress(text: string) {
    setProgress(prev => [...prev.slice(-9), { ts: new Date().toLocaleTimeString(), text }])
  }

  // ----- 子组件：Chip 多选 -----
  function Chip({ active, onClick, children }: any) {
    return (
      <button type="button" onClick={onClick}
        className={`px-2 py-1 text-xs rounded border transition ${active
          ? 'bg-amber-500 text-black border-amber-300'
          : 'bg-black/30 text-gray-300 border-white/10 hover:border-white/30'}`}>
        {children}
      </button>
    )
  }
  function Chips({ values, options, field, platform }: { values: string[]; options: string[]; field: string; platform: string }) {
    return (
      <div className="flex flex-wrap gap-1">
        {options.map(o => (
          <Chip key={o} active={values.includes(o)} onClick={() => toggleList(field, platform, o)}>{o}</Chip>
        ))}
      </div>
    )
  }

  return (
    <div className="min-h-screen bg-[#0a0a0f] text-gray-100 p-8 space-y-6">
      <div className="flex items-center gap-3">
        <BiTask className="text-amber-400 text-2xl" />
        <h1 className="text-2xl font-bold tracking-widest uppercase">投递中心 / Delivery Center</h1>
      </div>
      <p className="text-xs text-gray-500">在这里填一次，4 个平台的全部配置都已就位并驱动爬虫/投递执行。</p>

      {/* ====== 公共字段 ====== */}
      <Card className="bg-[#12121a] border-white/10 p-6 space-y-4">
        <h2 className="text-lg font-semibold text-amber-300">公共需求</h2>
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          <div>
            <label className="text-xs text-gray-400">简历</label>
            <select className="w-full bg-black/40 border border-white/10 rounded p-2 text-sm"
              value={req.resume_id} onChange={e => setGlobalField('resume_id', Number(e.target.value))}>
              <option value={0}>— 选择简历 —</option>
              {resumes.map(r => <option key={r.id} value={r.id}>#{r.id} {r.name}</option>)}
            </select>
          </div>
          <div>
            <label className="text-xs text-gray-400">关键词 (逗号分隔)</label>
            <input className="w-full bg-black/40 border border-white/10 rounded p-2 text-sm"
              value={req.keywords} onChange={e => setGlobalField('keywords', e.target.value)} />
          </div>
          <div>
            <label className="text-xs text-gray-400">城市 (逗号)</label>
            <input className="w-full bg-black/40 border border-white/10 rounded p-2 text-sm"
              value={(req.city_codes || []).join(',')} onChange={e => setGlobalField('city_codes', e.target.value.split(/[,，]/).filter(Boolean))} />
          </div>
          <div>
            <label className="text-xs text-gray-400">薪资下限 K</label>
            <input type="number" className="w-full bg-black/40 border border-white/10 rounded p-2 text-sm"
              value={req.salary_min_k} onChange={e => setGlobalField('salary_min_k', Number(e.target.value))} />
          </div>
          <div>
            <label className="text-xs text-gray-400">薪资上限 K</label>
            <input type="number" className="w-full bg-black/40 border border-white/10 rounded p-2 text-sm"
              value={req.salary_max_k} onChange={e => setGlobalField('salary_max_k', Number(e.target.value))} />
          </div>
          <div>
            <label className="text-xs text-gray-400">最多投递数</label>
            <input type="number" className="w-full bg-black/40 border border-white/10 rounded p-2 text-sm"
              value={req.max_count} onChange={e => setGlobalField('max_count', Number(e.target.value))} />
          </div>
          <div>
            <label className="text-xs text-gray-400">匹配阈值</label>
            <input type="number" className="w-full bg-black/40 border border-white/10 rounded p-2 text-sm"
              value={req.match_threshold} onChange={e => setGlobalField('match_threshold', Number(e.target.value))} />
          </div>
          <div>
            <label className="text-xs text-gray-400">黑名单关键词</label>
            <input className="w-full bg-black/40 border border-white/10 rounded p-2 text-sm"
              value={req.blacklist_keywords} onChange={e => setGlobalField('blacklist_keywords', e.target.value)} />
          </div>
        </div>

        {/* 平台勾选 */}
        <div>
          <label className="text-xs text-gray-400">勾选投递平台</label>
          <div className="flex flex-wrap gap-2 mt-1">
            {PLATFORMS.map(p => {
              const on = (req.platforms || []).includes(p.id)
              return (
                <button key={p.id} type="button" onClick={() => togglePlatform(p.id)}
                  className={`px-3 py-1.5 rounded border text-sm transition ${on
                    ? `${p.color} bg-white/10`
                    : 'border-white/10 text-gray-500 hover:border-white/30'}`}>
                  {p.label}
                </button>
              )
            })}
          </div>
        </div>

        {validation.length > 0 && (
          <div className="text-xs text-red-400 bg-red-900/20 border border-red-500/30 rounded p-2">
            ⚠️ {validation.join('；')}
          </div>
        )}
      </Card>

      {/* ====== 平台 tabs + 配置面板 ====== */}
      {(req.platforms || []).length > 0 && (
        <Card className="bg-[#12121a] border-white/10 p-6 space-y-4">
          <div className="flex gap-2 border-b border-white/10 pb-2">
            {(req.platforms || []).map((pid: string) => {
              const meta = PLATFORMS.find(p => p.id === pid)!
              return (
                <button key={pid} type="button" onClick={() => setActiveTab(pid)}
                  className={`px-4 py-1.5 text-sm rounded-t border-b-2 transition ${activeTab === pid
                    ? `${meta.color} border-current bg-white/5`
                    : 'border-transparent text-gray-500 hover:text-gray-300'}`}>
                  {meta.label}
                </button>
              )
            })}
          </div>

          {activeTab === 'boss' && (
            <div className="space-y-4">
              <h3 className="text-sm font-semibold text-amber-300">Boss 配置（19 字段）</h3>
              <div className="grid grid-cols-2 gap-3">
                <div><label className="text-xs text-gray-400">keywords (逗号)</label>
                  <input className="w-full bg-black/40 border border-white/10 rounded p-2 text-sm"
                    value={(pc.boss.keywords || []).join(',')}
                    onChange={e => setField('boss','keywords', e.target.value.split(/[,，]/).filter(Boolean))} />
                </div>
                <div><label className="text-xs text-gray-400">city</label>
                  <select className="w-full bg-black/40 border border-white/10 rounded p-2 text-sm"
                    value={pc.boss.city || ''} onChange={e => setField('boss','city', e.target.value)}>
                    <option value="">— 必填 —</option>
                    {BOSS_OPTIONS.city.map(c => <option key={c} value={c}>{c}</option>)}
                  </select>
                </div>
                <div><label className="text-xs text-gray-400">jobType</label>
                  <select className="w-full bg-black/40 border border-white/10 rounded p-2 text-sm"
                    value={pc.boss.jobType || ''} onChange={e => setField('boss','jobType', e.target.value)}>
                    {BOSS_OPTIONS.jobType.map(c => <option key={c} value={c}>{c}</option>)}
                  </select>
                </div>
                <div><label className="text-xs text-gray-400">industry</label>
                  <Chips values={pc.boss.industry || []} options={BOSS_OPTIONS.industry} field="industry" platform="boss" />
                </div>
                <div><label className="text-xs text-gray-400">experience</label>
                  <Chips values={pc.boss.experience || []} options={BOSS_OPTIONS.experience} field="experience" platform="boss" />
                </div>
                <div><label className="text-xs text-gray-400">degree</label>
                  <Chips values={pc.boss.degree || []} options={BOSS_OPTIONS.degree} field="degree" platform="boss" />
                </div>
                <div><label className="text-xs text-gray-400">salary</label>
                  <Chips values={pc.boss.salary || []} options={BOSS_OPTIONS.salary} field="salary" platform="boss" />
                </div>
                <div><label className="text-xs text-gray-400">scale</label>
                  <Chips values={pc.boss.scale || []} options={BOSS_OPTIONS.scale} field="scale" platform="boss" />
                </div>
                <div><label className="text-xs text-gray-400">stage</label>
                  <Chips values={pc.boss.stage || []} options={BOSS_OPTIONS.stage} field="stage" platform="boss" />
                </div>
                <div><label className="text-xs text-gray-400">expectedSalaryMin</label>
                  <input type="number" className="w-full bg-black/40 border border-white/10 rounded p-2 text-sm"
                    value={pc.boss.expectedSalaryMin || 0}
                    onChange={e => setField('boss','expectedSalaryMin', Number(e.target.value))} />
                </div>
                <div><label className="text-xs text-gray-400">expectedSalaryMax</label>
                  <input type="number" className="w-full bg-black/40 border border-white/10 rounded p-2 text-sm"
                    value={pc.boss.expectedSalaryMax || 0}
                    onChange={e => setField('boss','expectedSalaryMax', Number(e.target.value))} />
                </div>
                <div><label className="text-xs text-gray-400">waitTime</label>
                  <input type="number" className="w-full bg-black/40 border border-white/10 rounded p-2 text-sm"
                    value={pc.boss.waitTime || 5}
                    onChange={e => setField('boss','waitTime', Number(e.target.value))} />
                </div>
                <div className="col-span-2"><label className="text-xs text-gray-400">sayHi (招呼语)</label>
                  <textarea rows={2} className="w-full bg-black/40 border border-white/10 rounded p-2 text-sm"
                    value={pc.boss.sayHi || ''} onChange={e => setField('boss','sayHi', e.target.value)} />
                </div>
              </div>
              <div className="flex flex-wrap items-center gap-6 text-sm">
                <label className="flex items-center gap-2"><input type="checkbox" checked={!!pc.boss.enableAI}
                  onChange={e => setField('boss','enableAI', e.target.checked)} /> enableAI</label>
                <label className="flex items-center gap-2"><input type="checkbox" checked={!!pc.boss.sendImgResume}
                  onChange={e => setField('boss','sendImgResume', e.target.checked)} /> sendImgResume</label>
                <label className="flex items-center gap-2"><input type="checkbox" checked={!!pc.boss.filterDeadHr}
                  onChange={e => setField('boss','filterDeadHr', e.target.checked)} /> filterDeadHr</label>
                <label className="flex items-center gap-2"><input type="checkbox" checked={!!pc.boss.debugger}
                  onChange={e => setField('boss','debugger', e.target.checked)} /> debugger</label>
              </div>
              <div>
                <label className="text-xs text-gray-400">deadStatus</label>
                <Chips values={pc.boss.deadStatus || []} options={BOSS_OPTIONS.deadStatus} field="deadStatus" platform="boss" />
              </div>
            </div>
          )}

          {activeTab === 'job51' && (
            <div className="space-y-4">
              <h3 className="text-sm font-semibold text-blue-300">51job 配置（3 字段）</h3>
              <div className="grid grid-cols-2 gap-3">
                <div><label className="text-xs text-gray-400">keywords (逗号)</label>
                  <input className="w-full bg-black/40 border border-white/10 rounded p-2 text-sm"
                    value={(pc.job51.keywords || []).join(',')}
                    onChange={e => setField('job51','keywords', e.target.value.split(/[,，]/).filter(Boolean))} />
                </div>
                <div><label className="text-xs text-gray-400">jobArea</label>
                  <Chips values={pc.job51.jobArea || []} options={(opts.job51_jobArea || []).map(o => o.name)} field="jobArea" platform="job51" />
                </div>
                <div className="col-span-2"><label className="text-xs text-gray-400">salary</label>
                  <Chips values={pc.job51.salary || []} options={(opts.job51_salary || []).map(o => o.name)} field="salary" platform="job51" />
                </div>
              </div>
            </div>
          )}

          {activeTab === 'liepin' && (
            <div className="space-y-4">
              <h3 className="text-sm font-semibold text-emerald-300">猎聘 配置（3 字段）</h3>
              <div className="grid grid-cols-2 gap-3">
                <div><label className="text-xs text-gray-400">keywords (逗号)</label>
                  <input className="w-full bg-black/40 border border-white/10 rounded p-2 text-sm"
                    value={(pc.liepin.keywords || []).join(',')}
                    onChange={e => setField('liepin','keywords', e.target.value.split(/[,，]/).filter(Boolean))} />
                </div>
                <div><label className="text-xs text-gray-400">city</label>
                  <select className="w-full bg-black/40 border border-white/10 rounded p-2 text-sm"
                    value={pc.liepin.city || ''} onChange={e => setField('liepin','city', e.target.value)}>
                    <option value="">— 必填 —</option>
                    {(opts.liepin_city || []).map(o => <option key={o.code} value={o.name}>{o.name}</option>)}
                  </select>
                </div>
                <div><label className="text-xs text-gray-400">salary (自由输入)</label>
                  <input className="w-full bg-black/40 border border-white/10 rounded p-2 text-sm"
                    value={pc.liepin.salary || ''} onChange={e => setField('liepin','salary', e.target.value)} />
                </div>
              </div>
            </div>
          )}

          {activeTab === 'zhilian' && (
            <div className="space-y-4">
              <h3 className="text-sm font-semibold text-rose-300">智联 配置（3 字段）</h3>
              <div className="grid grid-cols-2 gap-3">
                <div><label className="text-xs text-gray-400">keywords (逗号)</label>
                  <input className="w-full bg-black/40 border border-white/10 rounded p-2 text-sm"
                    value={(pc.zhilian.keywords || []).join(',')}
                    onChange={e => setField('zhilian','keywords', e.target.value.split(/[,，]/).filter(Boolean))} />
                </div>
                <div><label className="text-xs text-gray-400">city (code 选)</label>
                  <select className="w-full bg-black/40 border border-white/10 rounded p-2 text-sm"
                    value={pc.zhilian.city || ''} onChange={e => setField('zhilian','city', e.target.value)}>
                    <option value="">— 必填 —</option>
                    {(opts.zhilian_city || []).map(o => <option key={o.code} value={o.code}>{o.name}({o.code})</option>)}
                  </select>
                </div>
                <div><label className="text-xs text-gray-400">salary (code 选)</label>
                  <select className="w-full bg-black/40 border border-white/10 rounded p-2 text-sm"
                    value={pc.zhilian.salary || ''} onChange={e => setField('zhilian','salary', e.target.value)}>
                    {(opts.zhilian_salary || []).map(o => <option key={o.code} value={o.code}>{o.name}</option>)}
                  </select>
                </div>
              </div>
            </div>
          )}
        </Card>
      )}

      {/* ====== 操作区 ====== */}
      <Card className="bg-[#12121a] border-white/10 p-6">
        <h2 className="text-lg font-semibold text-amber-300">操作</h2>
        <div className="flex flex-wrap gap-3 mt-3">
          <Button disabled={busy || validation.length>0} onClick={submit} className="bg-amber-500 text-black">
            1. 提交投递需求
          </Button>
          <Button disabled={busy || !requestId} onClick={pullAndMatch} variant="outline">
            <BiRefresh /> 2. 拉取并匹配
          </Button>
          <Button disabled={busy || !requestId} onClick={generateJobSpecific} variant="outline">
            3. 生成定制版
          </Button>
          <Button disabled={busy || !requestId} onClick={runDelivery} className="bg-amber-500 text-black">
            <BiRocket /> 4. 启动爬虫投递
          </Button>
        </div>

        {msg && (
          <div className={`mt-4 text-sm rounded p-3 border ${
            msg.type === 'error' ? 'text-red-300 bg-red-900/20 border-red-500/30'
            : msg.type === 'success' ? 'text-emerald-300 bg-emerald-900/20 border-emerald-500/30'
            : 'text-gray-300 bg-white/5 border-white/10'
          }`}>
            {msg.text}
          </div>
        )}

        {progress.length > 0 && (
          <div className="mt-3 text-xs text-gray-500 font-mono space-y-1">
            {progress.map((p, i) => <div key={i}>[{p.ts}] {p.text}</div>)}
          </div>
        )}
      </Card>

      {/* ====== 投递列表 ====== */}
      {targets.length > 0 && (
        <Card className="bg-[#12121a] border-white/10 p-6">
          <h2 className="text-lg font-semibold text-amber-300 flex items-center gap-2"><BiListUl /> 投递列表</h2>
          <table className="w-full text-sm mt-3">
            <thead><tr className="text-left text-gray-400 border-b border-white/10">
              <th className="py-2">#</th><th>公司</th><th>岗位</th><th>平台</th><th>状态</th><th>评分</th>
            </tr></thead>
            <tbody>
              {targets.map(t => (
                <tr key={t.id} className="border-b border-white/5">
                  <td className="py-2">{t.id}</td>
                  <td>{t.companyName}</td>
                  <td>{t.jobName}</td>
                  <td>{t.platform}</td>
                  <td><span className="px-2 py-0.5 rounded text-xs bg-white/5 border border-white/10">{t.status}</span></td>
                  <td>{t.matchScore}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}
    </div>
  )
}