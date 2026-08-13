'use client'

// 最小化 Markdown 渲染（FR-AI-001）
// 支持：# 标题、**粗体**、`code`、代码块 ```、列表 -、表格 |a|b|
// 不引入外部 markdown 库，避免 CDN 依赖。

function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

function inline(s: string): string {
  let out = escapeHtml(s)
  out = out.replace(/`([^`]+)`/g, '<code class="px-1 rounded bg-blackho text-amber-300">$1</code>')
  out = out.replace(/\*\*([^*]+)\*\*/g, '<strong class="text-white">$1</strong>')
  out = out.replace(/\*([^*]+)\*/g, '<em>$1</em>')
  return out
}

export default function Markdown({ source }: { source: string }) {
  if (!source) return null
  const lines = source.split(/\r?\n/)
  const blocks: string[] = []
  let i = 0
  while (i < lines.length) {
    const line = lines[i]
    // 代码块
    if (/^```/.test(line.trim())) {
      const buf: string[] = []
      i++
      while (i < lines.length && !/^```/.test(lines[i].trim())) {
        buf.push(lines[i]); i++
      }
      i++
      blocks.push(`<pre class="my-2 p-2 bg-blackho rounded text-xs overflow-x-auto text-manatee"><code>${escapeHtml(buf.join('\n'))}</code></pre>`)
      continue
    }
    // 表格
    if (/^\|.*\|$/.test(line.trim())) {
      const tbl: string[] = []
      while (i < lines.length && /^\|.*\|$/.test(lines[i].trim())) {
        tbl.push(lines[i]); i++
      }
      const rows = tbl.filter(r => !/^\|[\s-:|]+\|$/.test(r.trim())).map(r => r.trim().slice(1, -1).split('|').map(c => c.trim()))
      const head = rows[0] || []
      const body = rows.slice(1)
      const th = head.map(c => `<th class="border border-strokedark px-2 py-1 text-amber-300 text-left">${inline(c)}</th>`).join('')
      const tb = body.map(r => `<tr>${r.map(c => `<td class="border border-strokedark px-2 py-1">${inline(c)}</td>`).join('')}</tr>`).join('')
      blocks.push(`<table class="my-2 text-xs border-collapse"><thead><tr>${th}</tr></thead><tbody>${tb}</tbody></table>`)
      continue
    }
    // 标题
    const h = line.match(/^(#{1,6})\s+(.*)$/)
    if (h) {
      const lvl = h[1].length
      const cls = ['text-xl text-amber-300', 'text-lg text-amber-300', 'text-base text-amber-300', 'text-sm text-amber-300', 'text-sm text-manatee', 'text-xs text-waterloo'][lvl - 1]
      blocks.push(`<h${lvl} class="${cls} font-semibold mt-3 mb-1" style="font-family:'Playfair Display','Lora',serif">${inline(h[2])}</h${lvl}>`)
      i++; continue
    }
    // 列表
    if (/^[-*]\s+/.test(line)) {
      const items: string[] = []
      while (i < lines.length && /^[-*]\s+/.test(lines[i])) {
        items.push(lines[i].replace(/^[-*]\s+/, '')); i++
      }
      blocks.push(`<ul class="list-disc pl-5 my-2">${items.map(it => `<li>${inline(it)}</li>`).join('')}</ul>`)
      continue
    }
    // 空行
    if (!line.trim()) { i++; continue }
    // 段落
    blocks.push(`<p class="my-1 leading-relaxed">${inline(line)}</p>`)
    i++
  }
  return <div className="markdown-body text-manatee text-sm" dangerouslySetInnerHTML={{ __html: blocks.join('\n') }} />
}
