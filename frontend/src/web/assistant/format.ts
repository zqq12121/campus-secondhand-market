/**
 * 助手回复的轻量 markdown：只支持 **加粗**、`- ` / `1. ` 列表和换行。
 * 先整体转义 HTML 再加标签，模型输出里即使混入 HTML 也只会显示为文本。
 */
function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;')
}

function inline(s: string): string {
  return s.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
}

export function renderLite(text: string): string {
  const out: string[] = []
  let list: 'ul' | 'ol' | null = null
  const close = () => { if (list) { out.push(`</${list}>`); list = null } }
  for (const raw of escapeHtml(text).split('\n')) {
    const line = raw.trimEnd()
    const ul = /^\s*[-*] (.*)$/.exec(line)
    const ol = /^\s*\d+[.、] (.*)$/.exec(line)
    if (ul || ol) {
      const kind = ul ? 'ul' : 'ol'
      if (list !== kind) { close(); out.push(`<${kind}>`); list = kind }
      out.push(`<li>${inline((ul ?? ol)![1])}</li>`)
      continue
    }
    close()
    if (line.trim()) out.push(`<p>${inline(line)}</p>`)
  }
  close()
  return out.join('')
}
