export type ModuleType = 'iframe' | 'embedded' | 'mfe' | 'link';

export type EntryCategory =
  | 'applications'
  | 'settings'
  | 'features'
  | 'admin-settings'
  | 'user-settings';

// ── Module Content ────────────────────────────────────────────────────

export interface PortalModuleContent {
  moduleKey: string;
  contentKey: string;
  category: EntryCategory;
  name: string;
  description?: string;
  type: ModuleType;
  url?: string;
  loadPath?: string;
  entryUrl?: string;
  element?: string;
  sandbox?: string[];
  allow?: string;
  parentContentKey: string | null;
  groupKey: string | null;
  sortOrder: number;
  icon?: string;
  color?: string;
  roles?: string[];
  active?: boolean;
  multi?: boolean;
}

export function moduleContentId(ep: PortalModuleContent): string {
  return `${ep.moduleKey}:${ep.contentKey}`;
}

export function parseModuleContentId(id: string): { moduleKey: string; contentKey: string } {
  const idx = id.indexOf(':');
  return idx >= 0
    ? { moduleKey: id.substring(0, idx), contentKey: id.substring(idx + 1) }
    : { moduleKey: id, contentKey: 'main' };
}

// ── Navigation Group (sections) ───────────────────────────────────────

export interface NavigationGroup {
  groupKey: string;
  category: EntryCategory;
  name: string;
  parentKey: string | null;
  sortOrder: number;
  icon?: string;
  roles?: string[];
}

// ── Services & User ───────────────────────────────────────────────────

export interface PortalService {
  key: string;
  name: string;
  url: string | null;
  color: string;
  hint?: string;
}

export interface PortalUser {
  sub: string;
  name: string;
  email?: string;
  roles: string[];
}

export interface PortalConfig {
  user: PortalUser;
  /** Portal-owned basic preferences (scope 'general' of user_settings); absent keys mean "no DB preference". */
  preferences?: UserPreferences;
  moduleContents: PortalModuleContent[];
  navigationGroups: NavigationGroup[];
  services: PortalService[];
}

export interface UserPreferences {
  theme?: string;
  language?: string;
}

// ── Tab / Workspace ───────────────────────────────────────────────────

export interface Tab {
  id: number;
  content: PortalModuleContent;
  instance: number;
}

export interface TabGroup {
  id: string;
  tabs: Tab[];
  activeId: number | null;
}

export type SplitDir = 'row' | 'col';

export interface LeafNode {
  kind: 'leaf';
  groupId: string;
}

export interface SplitNode {
  kind: 'split';
  id: string;
  dir: SplitDir;
  ratio: number;
  a: LayoutNode;
  b: LayoutNode;
}

export type LayoutNode = LeafNode | SplitNode;

export interface WorkspaceMeta {
  id: string;
  name: string;
  description: string;
  color: string;
  status: string;
  savedAt: number;
}

export interface SavedGroup {
  tabs: string[];
  activeIdx: number | null;
}

export interface WorkspaceSnapshot {
  id?: string;
  name: string;
  description?: string;
  color?: string;
  status?: string;
  savedAt: number;
  layout: LayoutNode | null;
  groups: Record<string, SavedGroup>;
  focusedGroupId: string | null;
  hideSingleTabToolbar: boolean;
  locked: boolean;
}

// ── Form values (Module Registry) ─────────────────────────────────────

export interface ModuleFormValue {
  key: string;
  name: string;
  active?: boolean;
}

export interface ModuleContentFormValue {
  moduleKey: string;
  contentKey: string;
  category: EntryCategory;
  name: string;
  description?: string;
  type: ModuleType;
  url?: string;
  sandbox?: string;
  allow?: string;
  loadPath?: string;
  entryUrl?: string;
  element?: string;
  parentContentKey?: string;
  active?: boolean;
  color?: string;
  multi?: boolean;
}

export interface ModulePayload {
  key: string;
  name: string;
  active?: boolean;
  baseUrl?: string | null;
  health?: string | null;
}

// ── Install Wizard ─────────────────────────────────────────────────────

export interface ManifestContentEntry {
  key: string;
  name: string;
  description?: string;
  type: ModuleType;
  url?: string;
  path?: string;
  loadPath?: string;
  entryUrl?: string;
  element?: string;
  sandbox?: string[];
  allow?: string;
  requiredRoles?: string[];
  multi?: boolean;
  sortOrder?: number;
}

export interface ManifestRole {
  key: string;
  name: string;
  description?: string;
}

export interface PortalModuleManifest {
  manifestVersion: number;
  key: string;
  name: string;
  baseUrl: string;
  health?: string;
  content: {
    applications: ManifestContentEntry[];
    features: ManifestContentEntry[];
    adminSettings: ManifestContentEntry[];
    userSettings: ManifestContentEntry[];
  };
  security: { roles: ManifestRole[] };
  capabilities?: { scope: string }[];
  events?: { published?: { subject: string; schemaVersion?: number; description?: string }[]; consumed?: { subject: string; schemaVersion?: number; description?: string }[] };
}

export interface EntryDiff {
  action: 'create' | 'update' | 'unchanged';
  key: string;
  category: string;
  name: string;
}

export interface RoleDiff {
  action: 'create' | 'update' | 'unchanged';
  key: string;
  name: string;
}

export interface InstallDiff {
  moduleAction: 'create' | 'update' | 'unchanged';
  moduleContents: EntryDiff[];
  roles: RoleDiff[];
  digest: string;
}

export interface VersionOutput {
  id: number;
  version: string;
  digest: string;
  installedAt: string;
  installedBy: string;
  status: string;
}
