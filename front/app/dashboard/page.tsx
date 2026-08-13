'use client'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { motion } from 'framer-motion'
import { BiUserCircle, BiTask, BiBrain, BiEnvelope, BiFile, BiUpload, BiBarChart, BiTrendingUp, BiCheckCircle, BiXCircle } from 'react-icons/bi'
import { Card } from '@/components/ui/card'

const API = (typeof process !== 'undefined' && process.env?.API_BASE_URL) || 'http://localhost:8888'

interface Resume { id: number; name: string; parse_status: string; is_manually_edited: number }
interface DeliveryTarget { id: number; status: string; match_score: number }
interface Profile { id: number; name: string; phone: string; email: string; conflict_log?: string }
interface ChangeLog { id: number; source: string; created_at: string }
interface Message { id: number; is_read: number; direction: string }

export default function DashboardPage() {
  const [resumes, setResumes] = useState<Resume[]>([])
  const [profile, setProfile] = useState<Profile | null>(null)
  const [profileConflictCount, setProfileConflictCount] = useState(0)
  const [targets, setTargets] = useState<DeliveryTarget[]>([])
  const [logs, setLogs] = useState<ChangeLog[]>([])
  const [msgs, setMsgs] = useState<Message[]>([])
  const [aiHealth, setAiHealth] = useState<'unknown' | 'up' | 'down'>('unknown')

  useEffect(() => {
    (async () => {
      try {
        const r1 = await fetch(`${API}/api/resume/list`).then(r => r.json())
        if (r1.success) setResumes(r1.data)

        const r2 = await fetch(`${API}/api/profile`).then(r => r.json())
        if (r2.success && r2.data?.profile) {
          setProfile(r2.data.profile)
          try {
            const log = JSON.parse(r2.data.profile.conflict_log || '[]')
            setProfileConflictCount(log.length)
          } catch { setProfileConflictCount(0) }
        }

        const r3 = await fetch(`${API}/api/profile/change-log`).then(r => r.json())
        if (r3.success) setLogs(r3.data)

        const r4 = await fetch(`${API}/api/messages`).then(r => r.json())
        if (r4.success) setMsgs(r4.data)

        // 统计最近一次投递的目标
        const recent = await fetch(`${API}/api/resume/list`).then(r => r.json())
        if (recent.success && recent.data.length > 0) {
          // 取最新的简历关联的 request（通过 sqlite 无法，这里简化展示）
        }

        // AI 健康检查
        const h = await fetch(`${API}/api/ai/health`).then(r => r.json()).catch(() => null)
        setAiHealth(h?.success ? 'up' : 'down')
      } catch (e) {
        console.error('Dashboard load error', e)
      }
    })()
  }, [])

  const parsedResumes = resumes.filter(r => r.parse_status === 'PARSED').length
  const failedResumes = resumes.filter(r => r.parse_status === 'PARSE_FAILED').length
  const unreadMessages = msgs.filter(m => !m.is_read && m.direction === 'IN').length

  return (
    <div className="p-6 space-y-6">
      <motion.div initial={{ opacity: 0, y: -10 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.4 }}>
        <h1 className="text-3xl font-bold text-white" style={{ fontFamily: "'Playfair Display', serif" }}>
          总览
        </h1>
        <p className="text-waterloo text-sm mt-1">求职系统全局状态 · 实时同步</p>
      </motion.div>

      {/* 第一行：核心 KPI */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <KPI icon={<BiFile className="text-2xl text-amber-300" />} label="简历总数" value={resumes.length}
              hint={`${parsedResumes} 已解析${failedResumes ? ' · ' + failedResumes + ' 失败' : ''}`}
              href="/resume-center" />
        <KPI icon={<BiUserCircle className="text-2xl text-emerald-300" />} label="个人数据库"
              value={profile?.name ? '✓ 已完善' : '✗ 待补全'}
              hint={profileConflictCount > 0 ? `冲突 ${profileConflictCount} 条` : '字段完整'}
              href="/profile" />
        <KPI icon={<BiTask className="text-2xl text-blue-300" />} label="今日投递目标" value={targets.length || '-'}
              hint="进入投递中心查看" href="/delivery" />
        <KPI icon={<BiEnvelope className="text-2xl text-rose-300" />} label="未读消息" value={unreadMessages}
              hint={`共 ${msgs.length} 条`} href="/messages" />
      </div>

      {/* 第二行：快捷入口 */}
      <div>
        <h2 className="text-lg font-semibold text-white mb-3">快捷入口</h2>
        <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
          <QuickAction href="/resume-center" icon={<BiUpload />} label="上传简历" />
          <QuickAction href="/profile" icon={<BiUserCircle />} label="补全 Profile" />
          <QuickAction href="/delivery" icon={<BiTask />} label="新建投递" />
          <QuickAction href="/ai-chat" icon={<BiBrain />} label="AI 对话" />
        </div>
      </div>

      {/* 第三行：状态详情 */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {/* Profile 状态卡 */}
        <Card className="p-4 bg-blacksection border-strokedark">
          <div className="flex items-center justify-between mb-3">
            <h3 className="text-lg font-semibold text-white flex items-center gap-2">
              <BiUserCircle className="text-emerald-300" /> Profile 状态
            </h3>
            <Link href="/profile" className="text-xs text-amber-300 hover:underline">去维护 →</Link>
          </div>
          {profile ? (
            <div className="space-y-2 text-sm">
              <Row k="姓名" v={profile.name || <em className="text-waterloo">未填</em>} />
              <Row k="手机" v={profile.phone || <em className="text-red-300">必填</em>} />
              <Row k="邮箱" v={profile.email || <em className="text-red-300">必填</em>} />
              <Row k="冲突" v={`${profileConflictCount} 条`} warn={profileConflictCount > 0} />
            </div>
          ) : <p className="text-waterloo text-sm">加载中...</p>}
        </Card>

        {/* AI 健康状态卡 */}
        <Card className="p-4 bg-blacksection border-strokedark">
          <div className="flex items-center justify-between mb-3">
            <h3 className="text-lg font-semibold text-white flex items-center gap-2">
              <BiBrain className="text-purple-300" /> AI 系统状态
            </h3>
            <Link href="/ai-config" className="text-xs text-amber-300 hover:underline">去配置 →</Link>
          </div>
          <div className="space-y-2 text-sm">
            <Row k="健康检查" v={
              <span className={`px-2 py-0.5 rounded text-xs ${aiHealth === 'up' ? 'bg-emerald-700/30 text-emerald-300' : aiHealth === 'down' ? 'bg-red-700/30 text-red-300' : 'bg-gray-700/30 text-gray-300'}`}>
                {aiHealth === 'up' ? '✓ 已连接' : aiHealth === 'down' ? '✗ 未连接' : '检查中'}
              </span>
            } />
            <Row k="写回日志" v={`${logs.length} 条（近 7 天）`} />
            <Row k="最近一次" v={logs[0]?.created_at || '无'} />
          </div>
        </Card>

        {/* 简历状态卡 */}
        <Card className="p-4 bg-blacksection border-strokedark">
          <div className="flex items-center justify-between mb-3">
            <h3 className="text-lg font-semibold text-white flex items-center gap-2">
              <BiFile className="text-amber-300" /> 简历列表
            </h3>
            <Link href="/resume-center" className="text-xs text-amber-300 hover:underline">查看全部 →</Link>
          </div>
          {resumes.length === 0 ? <p className="text-waterloo text-sm">尚未上传简历</p> : (
            <ul className="space-y-2">
              {resumes.slice(0, 4).map(r => (
                <li key={r.id} className="flex items-center justify-between text-sm">
                  <span className="text-manatee truncate">{r.name}</span>
                  <span className={`px-2 py-0.5 rounded text-xs ${r.parse_status === 'PARSED' ? 'bg-emerald-700/30 text-emerald-300' : r.parse_status === 'PARSE_FAILED' ? 'bg-red-700/30 text-red-300' : 'bg-yellow-700/30 text-yellow-300'}`}>
                    {r.parse_status}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </Card>

        {/* 变更时间线 */}
        <Card className="p-4 bg-blacksection border-strokedark">
          <div className="flex items-center justify-between mb-3">
            <h3 className="text-lg font-semibold text-white flex items-center gap-2">
              <BiBarChart className="text-cyan-300" /> 最近变更
            </h3>
            <Link href="/profile" className="text-xs text-amber-300 hover:underline">查看全部 →</Link>
          </div>
          {logs.length === 0 ? <p className="text-waterloo text-sm">暂无变更记录</p> : (
            <ul className="space-y-2 max-h-48 overflow-y-auto">
              {logs.slice(0, 6).map(l => (
                <li key={l.id} className="text-sm border-l-2 border-amber-500/40 pl-3">
                  <span className={`px-1.5 py-0.5 rounded text-xs mr-2 ${l.source === 'AI_CHAT' ? 'bg-purple-700/30 text-purple-300' : l.source === 'RESUME_PARSE' ? 'bg-blue-700/30 text-blue-300' : 'bg-gray-700/30 text-gray-300'}`}>
                    {l.source}
                  </span>
                  <span className="text-waterloo text-xs">{l.created_at}</span>
                </li>
              ))}
            </ul>
          )}
        </Card>
      </div>

      {/* 第四行：提示 */}
      {failedResumes > 0 && (
        <Card className="p-4 bg-red-900/20 border-red-700/40">
          <div className="flex items-start gap-3">
            <BiXCircle className="text-2xl text-red-400 flex-shrink-0 mt-0.5" />
            <div className="flex-1">
              <p className="text-red-200 font-medium">有 {failedResumes} 份简历解析失败</p>
              <p className="text-red-300/70 text-sm mt-1">前往「简历中心」点击「重新解析」按钮。</p>
            </div>
          </div>
        </Card>
      )}

      {profileConflictCount > 0 && (
        <Card className="p-4 bg-amber-900/20 border-amber-700/40">
          <div className="flex items-start gap-3">
            <BiTrendingUp className="text-2xl text-amber-400 flex-shrink-0 mt-0.5" />
            <div className="flex-1">
              <p className="text-amber-200 font-medium">Profile 与简历存在 {profileConflictCount} 处冲突</p>
              <p className="text-amber-300/70 text-sm mt-1">前往「个人数据库」查看并选择保留哪一方。</p>
            </div>
          </div>
        </Card>
      )}

      {!profile?.name && (
        <Card className="p-4 bg-emerald-900/20 border-emerald-700/40">
          <div className="flex items-start gap-3">
            <BiCheckCircle className="text-2xl text-emerald-400 flex-shrink-0 mt-0.5" />
            <div className="flex-1">
              <p className="text-emerald-200 font-medium">首次使用建议：先补全个人数据库</p>
              <p className="text-emerald-300/70 text-sm mt-1">姓名、手机、邮箱是后续所有功能的基础。</p>
              <Link href="/profile" className="inline-block mt-2 px-3 py-1.5 bg-emerald-600 text-white rounded text-xs">立即补全</Link>
            </div>
          </div>
        </Card>
      )}
    </div>
  )
}

function KPI({ icon, label, value, hint, href }: { icon: React.ReactNode; label: string; value: any; hint?: string; href: string }) {
  return (
    <Link href={href}>
      <motion.div whileHover={{ scale: 1.02 }} whileTap={{ scale: 0.98 }}>
        <Card className="p-4 bg-blacksection border-strokedark hover:border-amber-500/40 transition">
          <div className="flex items-center gap-3">
            {icon}
            <span className="text-waterloo text-xs">{label}</span>
          </div>
          <div className="text-2xl font-bold text-white mt-2">{value}</div>
          {hint && <div className="text-xs text-waterloo mt-1">{hint}</div>}
        </Card>
      </motion.div>
    </Link>
  )
}

function QuickAction({ href, icon, label }: { href: string; icon: React.ReactNode; label: string }) {
  return (
    <Link href={href}>
      <motion.div whileHover={{ y: -2 }} className="flex items-center gap-2 p-3 bg-blacksection border border-strokedark rounded hover:border-amber-500/40 transition cursor-pointer">
        <span className="text-amber-300 text-xl">{icon}</span>
        <span className="text-white text-sm">{label}</span>
      </motion.div>
    </Link>
  )
}

function Row({ k, v, warn }: { k: string; v: any; warn?: boolean }) {
  return (
    <div className="flex justify-between items-center">
      <span className="text-waterloo">{k}</span>
      <span className={warn ? 'text-amber-300' : 'text-manatee'}>{v}</span>
    </div>
  )
}