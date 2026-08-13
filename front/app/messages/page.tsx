'use client'

import { useEffect, useMemo, useState } from 'react'
import { motion, AnimatePresence } from 'framer-motion'
import { BiEnvelope, BiEnvelopeOpen, BiCheck, BiBlock, BiRefresh, BiX, BiFilter, BiSortAlt2 } from 'react-icons/bi'
import { Card } from '@/components/ui/card'
import { Button } from '@/components/ui/button'

const API = (typeof process !== 'undefined' && process.env?.API_BASE_URL) || 'http://localhost:8888'

interface Message {
  id: number; platform: string; platform_message_id: string
  direction: string; sender_role: string; sender_name: string
  content_text: string; job_id: string; delivery_target_id: number
  is_read: number; received_at: string
}

const PLATFORM_LABEL: Record<string, string> = { boss: 'Boss', liepin: '猎聘', '51job': '51Job', zhilian: '智联' }
const PLATFORM_COLOR: Record<string, string> = {
  boss: 'bg-amber-500/20 text-amber-300 border-amber-500/30',
  liepin: 'bg-purple-500/20 text-purple-300 border-purple-500/30',
  '51job': 'bg-blue-500/20 text-blue-300 border-blue-500/30',
  zhilian: 'bg-cyan-500/20 text-cyan-300 border-cyan-500/30'
}

export default function MessagesPage() {
  const [messages, setMessages] = useState<Message[]>([])
  const [platform, setPlatform] = useState('')
  const [status, setStatus] = useState('')
  const [sortBy, setSortBy] = useState('time')
  const [groupBy, setGroupBy] = useState<'none' | 'platform' | 'company'>('platform')
  const [crawlPlatform, setCrawlPlatform] = useState('boss')
  const [detail, setDetail] = useState<Message | null>(null)
  const [conversation, setConversation] = useState<Message[]>([])
  const [blacklist, setBlacklist] = useState<{ id: number; type: string; value: string }[]>([])
  const [msg, setMsg] = useState<{ type: 'info' | 'success' | 'error'; text: string } | null>(null)

  async function load() {
    const params = new URLSearchParams()
    if (platform) params.append('platform', platform)
    if (status) params.append('status', status)
    if (sortBy) params.append('sortBy', sortBy)
    const r = await fetch(`${API}/api/messages?${params}`).then(r => r.json())
    if (r.success) setMessages(r.data)
  }
  async function loadBlacklist() {
    const r = await fetch(`${API}/api/boss/config/blacklist`).then(r => r.json())
    // 该端点直接返回数组
    if (Array.isArray(r)) setBlacklist(r)
    else if (r.success) setBlacklist(r.data || [])
  }

  useEffect(() => { load(); loadBlacklist() }, [platform, status, sortBy])

  async function markRead(id: number) {
    await fetch(`${API}/api/messages/${id}/read`, { method: 'POST' })
    load()
  }
  async function markAllRead() {
    for (const m of messages.filter(x => !x.is_read)) {
      await fetch(`${API}/api/messages/${m.id}/read`, { method: 'POST' })
    }
    load()
    toast('success', '全部已标为已读')
  }

  async function openConversation(m: Message) {
    setDetail(m)
    if (m.delivery_target_id) {
      const r = await fetch(`${API}/api/messages/conversation/${m.delivery_target_id}`).then(r => r.json())
      if (r.success) setConversation(r.data)
      else setConversation([m])
    } else {
      setConversation([m])
    }
  }

  async function triggerCrawl() {
    toast('info', '抓取中...')
    const fake = Array.from({ length: 2 }).map((_, i) => ({
      platform_message_id: `mock-${crawlPlatform}-${Date.now()}-${i}`,
      conversation_id: `c-${Date.now()}`,
      direction: 'IN', sender_role: 'HR', sender_name: `HR${i + 1}`,
      content_text: `你好，看你简历很合适，方便聊聊吗？ (${i + 1})`,
      job_id: `mock-job-${i}`
    }))
    const r = await fetch(`${API}/api/messages/crawl/${crawlPlatform}`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ messages: fake })
    }).then(r => r.json())
    toast(r.success ? 'success' : 'error', r.success ? `已插入 ${r.data.inserted} 条` : (r.message || '失败'))
    load()
  }

  async function addBlacklist(type: string, value: string) {
    const r = await fetch(`${API}/api/boss/config/blacklist`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ type, value, remark: `from message ${detail?.id}` })
    }).then(r => r.json())
    if (r.success === undefined || r.success) { toast('success', '已加入黑名单'); loadBlacklist() }
    else toast('error', r.message || '失败')
  }
  async function removeBlacklist(id: number) {
    const r = await fetch(`${API}/api/boss/config/blacklist/${id}`, { method: 'DELETE' }).then(r => r.json())
    if (r.success === undefined || r.success) { toast('success', '已移除'); loadBlacklist() }
    else toast('error', r.message || '失败')
  }

  function toast(type: 'info' | 'success' | 'error', text: string) {
    setMsg({ type, text }); setTimeout(() => setMsg(null), 3000)
  }

  const stats = useMemo(() => ({
    total: messages.length,
    unread: messages.filter(m => !m.is_read && m.direction === 'IN').length,
    hr: messages.filter(m => m.sender_role === 'HR').length,
    platforms: new Set(messages.map(m => m.platform)).size
  }), [messages])

  // 分组
  const grouped = useMemo(() => {
    if (groupBy === 'none') return [{ key: '全部', items: messages }]
    const keyOf = (m: Message) => groupBy === 'platform' ? m.platform : `${m.platform}:${m.job_id}`
    const map = new Map<string, Message[]>()
    for (const m of messages) {
      const k = keyOf(m)
      if (!map.has(k)) map.set(k, [])
      map.get(k)!.push(m)
    }
    return Array.from(map.entries()).map(([key, items]) => ({ key, items }))
  }, [messages, groupBy])

  return (
    <div className="p-6 space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-white flex items-center gap-2">
            <BiEnvelope className="text-rose-300" /> 消息中心
          </h1>
          <p className="text-xs text-waterloo mt-1">HR 回复统一聚合 · 抓取日志 · 黑名单</p>
        </div>
        <div className="flex gap-2 text-xs">
          <span className="px-2 py-1 rounded border bg-rose-700/30 text-rose-300 border-rose-700/40">未读 {stats.unread}</span>
          <span className="px-2 py-1 rounded border bg-blue-700/30 text-blue-300 border-blue-700/40">HR {stats.hr}</span>
          <span className="px-2 py-1 rounded border bg-amber-700/30 text-amber-300 border-amber-700/40">平台 {stats.platforms}</span>
        </div>
      </div>

      {/* 筛选 */}
      <Card className="p-3 bg-blacksection border-strokedark">
        <div className="flex flex-wrap gap-3 items-end">
          <div>
            <label className="text-xs text-waterloo flex items-center gap-1"><BiFilter /> 平台</label>
            <select className="bg-blackho border border-strokedark text-white px-3 py-2 rounded block mt-1 text-sm"
                    value={platform} onChange={e => setPlatform(e.target.value)}>
              <option value="">全部</option>
              <option value="boss">Boss</option>
              <option value="liepin">猎聘</option>
              <option value="51job">51Job</option>
              <option value="zhilian">智联</option>
            </select>
          </div>
          <div>
            <label className="text-xs text-waterloo flex items-center gap-1"><BiFilter /> 状态</label>
            <select className="bg-blackho border border-strokedark text-white px-3 py-2 rounded block mt-1 text-sm"
                    value={status} onChange={e => setStatus(e.target.value)}>
              <option value="">全部</option>
              <option value="UNREAD">未读</option>
              <option value="READ">已读</option>
              <option value="SYSTEM">系统</option>
            </select>
          </div>
          <div>
            <label className="text-xs text-waterloo flex items-center gap-1"><BiSortAlt2 /> 排序</label>
            <select className="bg-blackho border border-strokedark text-white px-3 py-2 rounded block mt-1 text-sm"
                    value={sortBy} onChange={e => setSortBy(e.target.value)}>
              <option value="time">时间倒序</option>
              <option value="score">匹配度倒序</option>
            </select>
          </div>
          <div>
            <label className="text-xs text-waterloo">分组</label>
            <select className="bg-blackho border border-strokedark text-white px-3 py-2 rounded block mt-1 text-sm"
                    value={groupBy} onChange={e => setGroupBy(e.target.value as any)}>
              <option value="platform">按平台</option>
              <option value="company">按岗位</option>
              <option value="none">不分组</option>
            </select>
          </div>
          <Button onClick={markAllRead} disabled={stats.unread === 0} className="bg-strokedark text-white">
            <BiCheck /> 全部已读
          </Button>
          <div className="ml-auto flex items-end gap-2">
            <select className="bg-blackho border border-strokedark text-white px-3 py-2 rounded text-sm"
                    value={crawlPlatform} onChange={e => setCrawlPlatform(e.target.value)}>
              <option value="boss">boss</option>
              <option value="liepin">liepin</option>
              <option value="51job">51job</option>
              <option value="zhilian">zhilian</option>
            </select>
            <Button onClick={triggerCrawl} className="bg-amber-500 text-black hover:bg-amber-600">
              <BiRefresh /> 抓取模拟
            </Button>
          </div>
        </div>
      </Card>

      {/* 黑名单 */}
      <Card className="p-4 bg-blacksection border-strokedark">
        <div className="flex items-center justify-between mb-3">
          <h2 className="text-sm font-semibold text-white flex items-center gap-2"><BiBlock /> 黑名单（{blacklist.length}）</h2>
          <span className="text-xs text-waterloo">命中后自动跳过该 HR / 公司 / 关键词</span>
        </div>
        {blacklist.length === 0 ? <p className="text-xs text-waterloo">暂无黑名单</p> : (
          <ul className="flex flex-wrap gap-2">
            {blacklist.map(b => (
              <li key={b.id} className="px-2 py-1 bg-red-900/30 border border-red-700/40 rounded text-xs text-red-200 flex items-center gap-2">
                <span className="text-waterloo">[{b.type}]</span>
                <span>{b.value}</span>
                <button onClick={() => removeBlacklist(b.id)} className="text-red-300 hover:text-white">×</button>
              </li>
            ))}
          </ul>
        )}
      </Card>

      {/* 消息列表 */}
      <Card className="p-4 bg-blacksection border-strokedark">
        <h2 className="text-lg font-semibold text-white mb-3">消息（{messages.length}）</h2>
        {messages.length === 0 ? <p className="text-waterloo text-sm">暂无消息。</p> : (
          <div className="space-y-4">
            {grouped.map(g => (
              <div key={g.key}>
                {groupBy !== 'none' && (
                  <h3 className="text-xs text-waterloo uppercase mb-2 border-b border-strokedark pb-1">
                    {g.key} · {g.items.length}
                  </h3>
                )}
                <ul className="divide-y divide-strokedark/40">
                  {g.items.map(m => (
                    <motion.li key={m.id} whileHover={{ x: 2 }}
                               className={`py-3 flex items-start gap-3 cursor-pointer ${!m.is_read && m.direction === 'IN' ? 'bg-amber-500/5' : ''}`}
                               onClick={() => openConversation(m)}>
                      <span className={`px-2 py-0.5 rounded text-xs border ${PLATFORM_COLOR[m.platform] || ''}`}>
                        {PLATFORM_LABEL[m.platform] || m.platform}
                      </span>
                      <div className="flex-1 min-w-0">
                        <div className="text-sm flex items-center gap-2">
                          <span className={!m.is_read && m.direction === 'IN' ? 'text-white font-medium' : 'text-manatee'}>{m.sender_name}</span>
                          <span className="text-xs text-waterloo">[{m.direction}/{m.sender_role}]</span>
                          {!m.is_read && m.direction === 'IN' && <BiEnvelope className="text-amber-300" />}
                          {m.is_read && <BiEnvelopeOpen className="text-waterloo text-xs" />}
                        </div>
                        <div className="text-sm text-manatee mt-1 line-clamp-2">{m.content_text}</div>
                        <div className="text-xs text-waterloo mt-1">{m.received_at} · job={m.job_id || '-'}</div>
                      </div>
                      {!m.is_read && (
                        <button onClick={(e) => { e.stopPropagation(); markRead(m.id) }}
                                className="px-2 py-1 bg-emerald-700/30 text-emerald-300 rounded text-xs">标已读</button>
                      )}
                    </motion.li>
                  ))}
                </ul>
              </div>
            ))}
          </div>
        )}
      </Card>

      {/* 会话详情弹窗 */}
      <AnimatePresence>
        {detail && (
          <div className="fixed inset-0 bg-black/70 z-50 flex items-center justify-center p-4">
            <motion.div initial={{ scale: 0.95, opacity: 0 }} animate={{ scale: 1, opacity: 1 }} exit={{ opacity: 0 }}
                        className="bg-blacksection border border-strokedark rounded-lg w-full max-w-2xl max-h-[90vh] overflow-hidden flex flex-col">
              <div className="p-4 border-b border-strokedark flex items-center justify-between">
                <div>
                  <h3 className="text-lg font-semibold text-white">{detail.sender_name} · {PLATFORM_LABEL[detail.platform]}</h3>
                  <p className="text-xs text-waterloo">job={detail.job_id} · {detail.received_at}</p>
                </div>
                <button onClick={() => setDetail(null)} className="text-waterloo hover:text-white"><BiX className="text-xl" /></button>
              </div>
              <div className="p-4 overflow-y-auto flex-1 space-y-3">
                {conversation.map(c => (
                  <div key={c.id} className={`p-2 rounded border ${c.sender_role === 'HR' ? 'bg-amber-500/10 border-amber-500/30' : 'bg-blue-500/10 border-blue-500/30'}`}>
                    <div className="text-xs text-waterloo mb-1">{c.sender_role} · {c.received_at}</div>
                    <div className="text-sm text-manatee">{c.content_text}</div>
                  </div>
                ))}
                {conversation.length === 0 && <p className="text-waterloo text-sm">暂无更多对话</p>}
              </div>
              <div className="p-3 border-t border-strokedark flex gap-2 flex-wrap">
                <Button onClick={() => addBlacklist('HR', detail.sender_name)} className="bg-red-700 text-white text-xs">
                  <BiBlock /> 拉黑 HR
                </Button>
                <Button onClick={() => addBlacklist('KEYWORD', detail.content_text.slice(0, 10))} className="bg-red-700 text-white text-xs">
                  <BiBlock /> 拉黑关键词
                </Button>
                <Button onClick={() => markRead(detail.id)} className="bg-emerald-700 text-white text-xs">
                  <BiCheck /> 标已读
                </Button>
              </div>
            </motion.div>
          </div>
        )}
      </AnimatePresence>

      {msg && (
        <motion.div initial={{ y: 20, opacity: 0 }} animate={{ y: 0, opacity: 1 }}
                    className={`fixed bottom-4 right-4 p-3 rounded text-sm shadow-lg ${
                      msg.type === 'success' ? 'bg-emerald-700 text-white' :
                      msg.type === 'error' ? 'bg-red-700 text-white' :
                      'bg-blue-700 text-white'}`}>
          {msg.text}
        </motion.div>
      )}
    </div>
  )
}