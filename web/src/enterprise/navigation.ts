import type { IconName } from './ui';

export type EnterpriseSection = 'dashboard' | 'ask' | 'history' | 'search' | 'documents' | 'wiki' | 'graph' | 'evidence' | 'ingestion' | 'tasks' | 'quality' | 'models' | 'audit' | 'admin';
export type NavigationItem = { id: EnterpriseSection; label: string; icon: IconName; group: 'workspace' | 'knowledge' | 'management'; description: string; adminOnly?: boolean; hidden?: boolean };

// One registry owns page identity, navigation order and client-side role visibility.
// Backend authorization remains authoritative for every request.
export const navigation: NavigationItem[] = [
  { id: 'ask', label: '知识查询', icon: 'chat', group: 'workspace', description: '用自然语言查找资料，答案与原文依据一起呈现。' },
  { id: 'history', label: '查询记录', icon: 'history', group: 'workspace', description: '继续历史查询，整理查询组和参考资料包。' },
  { id: 'dashboard', label: '知识概览', icon: 'dashboard', group: 'workspace', adminOnly: true, description: '查看真实知识资产、处理进度和需要关注的事项。' },
  { id: 'documents', label: '资料库', icon: 'documents', group: 'knowledge', description: '浏览原件、版本及访问范围，按需上传资料。' },
  { id: 'wiki', label: '知识条目', icon: 'wiki', group: 'knowledge', description: '按业务范围浏览已整理的知识与来源。' },
  { id: 'graph', label: '关系图谱', icon: 'graph', group: 'knowledge', description: '从资料与业务实体出发，追溯有原文依据的关系。' },
  { id: 'ingestion', label: '上传与处理', icon: 'upload', group: 'knowledge', description: '查看上传进度、处理结果和失败原因。' },
  { id: 'tasks', label: '知识生成', icon: 'sparkles', group: 'management', adminOnly: true, description: '查看摘要、知识条目和关系的生成进度。' },
  { id: 'quality', label: '质量检查', icon: 'quality', group: 'management', adminOnly: true, description: '核对摘要依据、内容覆盖及待处理的质量问题。' },
  { id: 'search', label: '检索诊断', icon: 'test', group: 'management', adminOnly: true, description: '检查召回结果、证据和排序效果。' },
  { id: 'models', label: '模型服务', icon: 'models', group: 'management', adminOnly: true, description: '查看及管理模型接入与配置版本。' },
  { id: 'audit', label: '操作审计', icon: 'audit', group: 'management', adminOnly: true, description: '追溯关键操作及其实际结果。' },
  { id: 'admin', label: '用户与权限', icon: 'users', group: 'management', adminOnly: true, description: '管理账号、既有访问权限和系统状态。' },
  { id: 'evidence', label: '原文依据', icon: 'evidence', group: 'knowledge', hidden: true, description: '查看引用原文及其来源位置。' },
];

export const adminSections = new Set(navigation.filter(item => item.adminOnly).map(item => item.id));
export const managementSections = new Set(navigation.filter(item => item.group === 'management').map(item => item.id));

export function sectionFromPath(pathname: string): EnterpriseSection {
  const segment = pathname.split('/').filter(Boolean)[1] || 'dashboard';
  return navigation.find(item => item.id === segment)?.id || 'dashboard';
}

export function sectionPath(section: EnterpriseSection) {
  return section === 'dashboard' ? '/enterprise' : `/enterprise/${section}`;
}

export function sectionMeta(section: EnterpriseSection) {
  const item = navigation.find(item => item.id === section)!;
  return { title: item.label, description: item.description };
}
