// FR-TPL-002 模板注册表
// 新增模板只需在此追加一项 + 在 components/resume-templates/{templateId}/ 下放预览图与可选静态资源
export type ResumeTemplateMeta = {
  id: string;
  name: string;
  description: string;
  preview: string; // PNG / SVG 相对路径，可后端资源或前端 public
  palette: {
    bg: string;
    fg: string;
    accent: string;
    muted: string;
  };
  fonts: {
    heading: string;
    body: string;
  };
};

export const RESUME_TEMPLATES: ResumeTemplateMeta[] = [
  {
    id: 'editorial-dark-v1',
    name: '编辑深色 v1',
    description: '深底 #0a0a0f + 暖色强调 #f0b90b，衬线标题 + 无衬线正文（设计系统一致）。',
    preview: '/templates/editorial-dark-v1/preview.svg',
    palette: { bg: '#0a0a0f', fg: '#e6e6ea', accent: '#f0b90b', muted: '#64748b' },
    fonts: { heading: "'Playfair Display', 'Lora', serif", body: "'DM Sans', 'PingFang SC', 'Microsoft YaHei', sans-serif" },
  },
  {
    id: 'editorial-light-v2',
    name: '编辑浅色 v2',
    description: '浅底 #fafaf7 + 深墨标题 #111827 + 暗金强调 #b8860b。同系列姊妹版（亮色场景）。',
    preview: '/templates/editorial-light-v2/preview.svg',
    palette: { bg: '#fafaf7', fg: '#111827', accent: '#b8860b', muted: '#6b7280' },
    fonts: { heading: "'Playfair Display', 'Lora', serif", body: "'DM Sans', 'PingFang SC', 'Microsoft YaHei', sans-serif" },
  },
];

export function getTemplate(id: string): ResumeTemplateMeta | undefined {
  return RESUME_TEMPLATES.find((t) => t.id === id);
}
