'use client'

import { useEffect, useRef, useState } from 'react'
import { motion, AnimatePresence } from 'framer-motion'
import { BiBrain, BiSend, BiPlus, BiCheck, BiX, BiCopy, BiTime, BiUserVoice, BiHistory, BiRefresh } from 'react-icons/bi'
import { Card } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import Markdown from '@/components/Markdown'

const API = (typeof process !== 'undefined' && process.env?.API_BASE_URL) || 'http://localhost:8888'

interface Session { id: number; title: string; updated_at: string }
interface Message { id: number; session_id: number; role: string; content: string; created_at: string }

const ROLE_STYLES: Record<string, { cls: string; label: string }> = {
  user: { cls: 'bg-amber-500/20 text-amber-100 border-amber-500/30', label: '你' },
  assistant: { cls: 'bg-blackho text-manatee border-strokedark', label: 'AI' },
  system_card: { cls: 'bg-emerald-700/20 text-emerald-200 border-emerald-700/40', label: '系统·待确认' },
  tool: { cls: 'bg-blue-700/20 text-blue-200 border-blue-700/40', label: '工具结果' }
}

export default function AiChatPage() {
  const [sessions, setSessions] = useState<Session[]>([])
  const [sessionId, setSessionId] = useState<number | null>(null)
  const [messages, setMessages] = useState<Message[]>([])
  const [templates, setTemplates] = useState<string[]>([])
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const [streamChunk, setStreamChunk] = useState('')
  const [msg, setMsg] = useState<{ type: 'info' | 'success' | 'error'; text: string } | null>(null)
  const bottomRef = useRef<HTMLDivElement | null>(null)
  const esRef = useRef<EventSource | null>(null)

  async function loadSessions() {
    const r = await fetch(`${API}/api/ai-chat/sessions`).then(r => r.json())
    if (r.success) setSessions(r.data)
  }
  async function loadTemplates() {
    const r = await fetch(`${API}/api/ai-chat/templates`).then(r => r.json())
    if (r.success) setTemplates(r.data)
  }
  async function loadMessages(sid: number) {
    const r = await fetch(`${API}/api/ai-chat/sessions/${sid}/messages`).then(r => r.json())
    if (r.success) setMessages(r.data)
  }

  useEffect(() => { loadSessions(); loadTemplates() }, [])
  useEffect(() => { if (sessionId) loadMessages(sessionId) }, [sessionId])
  useEffect(() => { bottomRef.current?.scrollIntoView({ behavior: 'smooth' }) }, [messages, streamChunk])

  // SSE 流式订阅
  useEffect(() => {
    if (!sessionId) return
    const es = new EventSource(`${API}/api/ai-chat/stream?sessionId=${sessionId}`)
    esRef.current = es
    es.addEventListener('chunk', (e: any) => setStreamChunk(prev => prev + e.data))
    es.addEventListener('done', () => {
      setStreamChunk('')
      loadMessages(sessionId)
      loadSessions()
      setBusy(false)
    })
    es.addEventListener('error', () => { es.close() })
    return () => { es.close() }
  }, [sessionId])

  async function newSession() {
    const r = await fetch(`${API}/api/ai-chat/sessions`, { method: 'POST' }).then(r => r.json())
    if (r.success) { setSessions([r.data, ...sessions]); setSessionId(r.data.id); toast('success', '新会话已创建') }
  }
  async function deleteSession(sid: number) {
    if (!confirm('确认删除该会话？')) return
    await fetch(`${API}/api/ai-chat/sessions/${sid}`, { method: 'DELETE' })
    setSessions(sessions.filter(s => s.id !== sid))
    if (sessionId === sid) { setSessionId(null); setMessages([]) }
  }

  async function send(t?: string) {
    const msg = t ?? text
    if (!msg.trim() || !sessionId) return
    setBusy(true); setText('')
    // 立即把用户消息插入视图
    const userMsg: Message = { id: Date.now(), session_id: sessionId, role: 'user', content: msg, created_at: new Date().toISOString() }
    setMessages(prev => [...prev, userMsg])
    try {
      const r = await fetch(`${API}/api/ai-chat/sessions/${sessionId}/send`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text: msg })
      })
      const d = await r.json()
      if (d.success) {
        // SSE 会推送；先拉一次确保 system_card 立即可见
        loadMessages(sessionId)
      } else {
        toast('error', d.message || '发送失败')
        setBusy(false)
      }
    } catch (e: any) { toast('error', e.message); setBusy(false) }
  }

  async function confirmCard(cardId: number, ok: boolean) {
    const r = await fetch(`${API}/api/ai-chat/sessions/${sessionId}/confirm`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ system_card_message_id: cardId, confirm: ok })
    }).then(r => r.json())
    if (r.success) {
      loadMessages(sessionId!)
      loadSessions()
      toast('success', ok ? '已确认并写入 Profile' : '已取消')
    } else toast('error', r.message || '操作失败')
  }

  async function copyText(s: string) {
    try { await navigator.clipboard.writeText(s); toast('success', '已复制') } catch { toast('error', '复制失败') }
  }

  function toast(type: 'info' | 'success' | 'error', text: string) {
    setMsg({ type, text }); setTimeout(() => setMsg(null), 3000)
  }

  const pendingCards = messages.filter(m => m.role === 'system_card')
  const lastAssistant = [...messages].reverse().find(m => m.role === 'assistant')

  return (
    <div className="p-6 grid grid-cols-1 md:grid-cols-12 gap-4 h-[calc(100vh-60px)]">
      {/* 左侧：会话列表 + 模板 */}
      <Card className="p-3 bg-blacksection border-strokedark md:col-span-3 overflow-y-auto">
        <Button onClick={newSession} className="w-full bg-amber-500 text-black hover:bg-amber-600 mb-3">
          <BiPlus /> 新会话
        </Button>
        <h3 className="text-xs uppercase text-waterloo mb-2 flex items-center gap-1">
          <BiHistory /> 会话列表（{sessions.length}）
        </h3>
        <ul className="space-y-1 mb-4">
          {sessions.map(s => (
            <li key={s.id}
                className={`p-2 rounded cursor-pointer flex justify-between items-start gap-2 ${sessionId === s.id ? 'bg-amber-500/20 text-amber-300' : 'hover:bg-blackho text-manatee'}`}>
              <div className="flex-1 min-w-0" onClick={() => setSessionId(s.id)}>
                <div className="text-sm font-medium truncate">{s.title}</div>
                <div className="text-xs text-waterloo flex items-center gap-1"><BiTime /> {s.updated_at}</div>
              </div>
              <button onClick={(e) => { e.stopPropagation(); deleteSession(s.id) }}
                      className="text-xs text-waterloo hover:text-red-400 px-1">×</button>
            </li>
          ))}
        </ul>

        <h3 className="text-xs uppercase text-waterloo mb-2 flex items-center gap-1">
          <BiBrain /> 内置问题模板（{templates.length}）
        </h3>
        <ul className="space-y-2">
          {templates.map((t, i) => (
            <li key={i}>
              <button onClick={() => send(t)} disabled={!sessionId || busy}
                      className="w-full text-left text-xs p-2 rounded bg-blackho text-manatee hover:bg-amber-500/10 hover:text-amber-300 disabled:opacity-50 transition">
                {t}
              </button>
            </li>
          ))}
        </ul>

        <div className="mt-4 p-3 bg-blackho border border-strokedark rounded text-xs text-waterloo">
          <p className="mb-1 font-medium text-manatee">关键词识别</p>
          <p>用户输入 <code className="text-amber-300">确认/是/好/改</code> → 自动执行 AI 提议</p>
          <p>用户输入 <code className="text-amber-300">取消/不要</code> → 自动撤销</p>
        </div>
      </Card>

      {/* 中间：对话流 */}
      <Card className="p-4 bg-blacksection border-strokedark md:col-span-6 flex flex-col">
        <div className="flex items-center justify-between mb-3">
          <h2 className="text-lg font-semibold text-white flex items-center gap-2">
            <BiBrain className="text-purple-300" /> 对话流
            {busy && <span className="text-xs text-amber-300 flex items-center gap-1"><BiRefresh className="animate-spin" /> 流式中</span>}
          </h2>
          {pendingCards.length > 0 && <span className="px-2 py-1 rounded text-xs bg-emerald-700/30 text-emerald-300">{pendingCards.length} 个待确认提议</span>}
        </div>

        <div className="flex-1 overflow-y-auto space-y-3 pr-1">
          {messages.length === 0 && <p className="text-waterloo text-sm">{sessionId ? '发送第一条消息开始对话' : '请先选择或新建一个会话'}</p>}
          {messages.map(m => {
            const style = ROLE_STYLES[m.role] || ROLE_STYLES.tool
            return (
              <motion.div key={m.id} initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }}
                          className={`flex ${m.role === 'user' ? 'justify-end' : 'justify-start'}`}>
                <div className={`max-w-[85%] rounded p-3 text-sm border ${style.cls}`}>
                  <div className="flex justify-between items-center mb-1">
                    <span className="text-xs text-waterloo font-semibold">[{style.label}]</span>
                    <div className="flex items-center gap-2">
                      <span className="text-xs text-waterloo">{m.created_at?.slice(11, 19)}</span>
                      {m.role === 'assistant' && (
                        <button onClick={() => copyText(m.content)} className="text-xs text-waterloo hover:text-amber-300"><BiCopy /></button>
                      )}
                    </div>
                  </div>
                  {m.role === 'system_card' ? <SystemCardContent content={m.content} onConfirm={(ok) => confirmCard(m.id, ok)} /> : null}
                  {m.role === 'assistant' ? <Markdown source={m.content} /> : <div className="whitespace-pre-wrap">{m.content}</div>}
                </div>
              </motion.div>
            )
          })}

          {/* 流式追加区 */}
          {streamChunk && (
            <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} className="flex justify-start">
              <div className="max-w-[85%] rounded p-3 text-sm bg-blackho text-manatee border border-strokedark">
                <div className="flex items-center gap-1 mb-1 text-xs text-waterloo">
                  <BiRefresh className="animate-spin" /> [AI] 流式中
                </div>
                <Markdown source={streamChunk} />
              </div>
            </motion.div>
          )}
          <div ref={bottomRef} />
        </div>

        <div className="mt-3 flex gap-2">
          <input className="flex-1 bg-blackho border border-strokedark text-white p-2 rounded"
                 placeholder="输入消息（识别 '确认/是/改' 自动执行 AI 提议；'取消/不要' 自动撤销）..."
                 value={text} onChange={e => setText(e.target.value)}
                 onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send() } }}
                 disabled={!sessionId || busy} />
          <Button onClick={() => send()} disabled={busy || !sessionId} className="bg-amber-500 text-black hover:bg-amber-600">
            <BiSend /> 发送
          </Button>
        </div>
      </Card>

      {/* 右侧：上下文信息 */}
      <Card className="p-3 bg-blacksection border-strokedark md:col-span-3 overflow-y-auto">
        <h3 className="text-sm font-semibold text-white mb-2 flex items-center gap-2">
          <BiUserVoice /> 上下文
        </h3>
        <div className="space-y-3 text-xs">
          <div className="p-2 bg-blackho rounded">
            <div className="text-waterloo mb-1">最近 AI 回复：</div>
            {lastAssistant ? <div className="text-manatee line-clamp-3">{lastAssistant.content}</div> : <div className="text-waterloo italic">无</div>}
          </div>
          <div className="p-2 bg-blackho rounded">
            <div className="text-waterloo mb-1">待确认提议：</div>
            {pendingCards.length === 0 ? <div className="text-waterloo italic">无</div> :
              pendingCards.map(c => (
                <div key={c.id} className="text-emerald-300 text-xs truncate">card #{c.id}</div>
              ))
            }
          </div>
          <div className="p-2 bg-blackho rounded">
            <div className="text-waterloo mb-1">说明</div>
            <p className="text-manatee leading-relaxed">AI 会自动携带最近 10 条消息 + 当前 Profile 摘要 + 当日投递汇总。</p>
          </div>
        </div>
      </Card>

      <AnimatePresence>
        {msg && (
          <motion.div initial={{ y: 20, opacity: 0 }} animate={{ y: 0, opacity: 1 }} exit={{ opacity: 0 }}
                      className={`fixed bottom-4 right-4 p-3 rounded text-sm shadow-lg ${
                        msg.type === 'success' ? 'bg-emerald-700 text-white' :
                        msg.type === 'error' ? 'bg-red-700 text-white' :
                        'bg-blue-700 text-white'}`}>
            {msg.text}
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  )
}

function SystemCardContent({ content, onConfirm }: { content: string; onConfirm: (ok: boolean) => void }) {
  // 解析 content 取出可读预览
  let toolName = 'unknown'
  let preview = content
  try {
    const obj = JSON.parse(content)
    toolName = obj.tool || obj.field || 'unknown'
    preview = JSON.stringify(obj, null, 2)
  } catch {}
  return (
    <div>
      <p className="text-xs text-waterloo mb-2">工具：<span className="text-amber-300">{toolName}</span></p>
      <pre className="text-xs bg-blackho p-2 rounded text-manatee max-h-32 overflow-y-auto mb-2">{preview}</pre>
      <div className="flex gap-2 mt-2">
        <button onClick={() => onConfirm(true)} className="px-3 py-1 bg-emerald-600 text-white rounded text-xs flex items-center gap-1">
          <BiCheck /> 确认
        </button>
        <button onClick={() => onConfirm(false)} className="px-3 py-1 bg-red-600 text-white rounded text-xs flex items-center gap-1">
          <BiX /> 取消
        </button>
      </div>
    </div>
  )
}