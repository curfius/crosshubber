import { Component, inject, signal, computed } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { SlicePipe, UpperCasePipe, JsonPipe } from '@angular/common';
import { EMBEDDED_LOAD_PATHS } from '../../workarea/embedded-modules';
import type { ModuleType, EntryCategory, EntryPointFormValue, PortalModuleManifest, VersionOutput, ManifestContentEntry, ModulePayload } from '../../../core/models';
import { I18nService } from '../../../core/i18n/i18n.service';
import { RegistryService, type ModuleOutput, type EntryPointOutput } from './module-registry.store';
import { DsTree, DsTreeNode } from '../../../shared/components/ds-tree/ds-tree.component';
import { Switch } from '../../../shared/components/switch/switch.component';
import { ConfirmDialog } from '../../../shared/components/confirm-dialog/confirm-dialog.component';
import { DsWizardHorizontal, type WizardStep } from '../../../shared/components/ds-wizard-horizontal/ds-wizard-horizontal.component';

const COLORS = ['#f59e0b', '#3b82f6', '#10b981', '#a855f7', '#ef4444', '#06b6d4', '#f97316', '#ec4899', '#6366f1', '#14b8a6'];

const CONTENT_GROUPS = [
  { key: 'applications', label: 'Applications' },
  { key: 'features', label: 'Features' },
  { key: 'adminSettings', label: 'Admin Settings' },
  { key: 'userSettings', label: 'User Settings' },
] as const;

@Component({
  selector: 'app-module-registry',
  imports: [FormsModule, SlicePipe, UpperCasePipe, JsonPipe, Switch, ConfirmDialog, DsTree, DsWizardHorizontal],
  templateUrl: './module-registry.component.html',
  styleUrl: './module-registry.component.css',
})
export class ModuleRegistry {
  private readonly registry = inject(RegistryService);
  protected readonly i18n = inject(I18nService);

  // ── State ──────────────────────────────────────────────────────────
  protected readonly modules = signal<ModuleOutput[]>([]);
  protected readonly selectedModule = signal<ModuleOutput | null>(null);
  protected readonly entryPoints = signal<EntryPointOutput[]>([]);
  protected readonly allEntryPoints = signal<EntryPointOutput[]>([]);
  protected readonly versions = signal<VersionOutput[]>([]);
  protected readonly activeManifest = signal<PortalModuleManifest | null>(null);
  protected readonly error = signal('');
  protected readonly busy = signal(false);
  protected readonly pendingRollback = signal<VersionOutput | null>(null);
  protected readonly comparingVersion = signal<VersionOutput | null>(null);
  protected readonly compareManifest = signal<PortalModuleManifest | null>(null);
  protected readonly compareDiff = signal<PreviewSection[]>([]);
  // Compare dropdown state
  protected readonly compareLeftId = signal<number | null>(null);
  protected readonly compareRightId = signal<number | null>(null);
  protected readonly compareLeftManifest = signal<PortalModuleManifest | null>(null);
  protected readonly compareRightManifest = signal<PortalModuleManifest | null>(null);

  // Draft choice modal (when opening module with pending drafts)
  protected readonly showDraftChoice = signal(false);
  protected readonly pendingDrafts = signal<VersionOutput[]>([]);
  protected readonly liveVersionForChoice = signal<VersionOutput | null>(null);

  // Versions section state
  protected readonly showVersions = signal(false);

  // Manifest Info state — default expanded so Module/Security/etc. are visible without extra click (spec: all blocks visible in edit, non-empty in read)
  protected readonly showManifestInfo = signal(true);
  protected readonly showJsonModal = signal(false);
  protected readonly manifestInfoSections = computed<PreviewSection[]>(() => {
    const m = this.workingManifest() ?? this.activeManifest();
    return m ? buildDiffSections(null, m) : [];
  });

  protected readonly genericSections = computed<PreviewSection[]>(() =>
    this.manifestInfoSections().filter((s) => s.title !== 'Security Roles')
  );

  protected readonly visibleGenericSections = computed<PreviewSection[]>(() => {
    const all = this.genericSections();
    if (this.isEditing()) return all;
    return all.filter((s) => {
      if (s.fields) return s.fields.some((f) => f.newValue != null && f.newValue !== '' && !(Array.isArray(f.newValue) && f.newValue.length === 0));
      if (s.items) return s.items.length > 0;
      return false;
    });
  });

  protected hasVisibleData(sectionTitle: string): boolean {
    const m = this.workingManifest() ?? this.activeManifest();
    if (!m) return false;
    if (sectionTitle === 'Security Roles') return (m.security?.roles?.length ?? 0) > 0;
    if (sectionTitle === 'Capabilities') return (m.capabilities?.length ?? 0) > 0;
    if (sectionTitle === 'Events') return ((m.events?.published?.length ?? 0) > 0 || (m.events?.consumed?.length ?? 0) > 0);
    return false;
  }

  // Draft / editing state
  protected readonly isEditing = signal(false);
  protected readonly workingManifest = signal<PortalModuleManifest | null>(null);
  protected readonly draftVersion = signal<VersionOutput | null>(null);
  protected readonly validationErrors = signal<string[]>([]);
  protected readonly showActivationProposal = signal(false);
  protected readonly activationResult = signal<{ moduleKey: string; version: string } | null>(null);

  protected readonly moduleNodes = computed<DsTreeNode[]>(() =>
    this.modules().map((m) => ({
      id: m.key,
      label: m.name,
      meta: m.builtin ? this.i18n.t('registry.tree.builtin') : undefined,
      dotColor: this.statusDot(this.moduleStatus(m)),
      data: m,
    })),
  );

  protected readonly selectedModuleId = computed(() => this.selectedModule()?.key ?? null);

  private statusDot(status: 'disabled' | 'empty' | 'active'): string {
    switch (status) {
      case 'active': return 'var(--portal-status-success)';
      case 'empty': return 'var(--portal-status-warning)';
      default: return 'var(--portal-text-disabled)';
    }
  }

  protected onModuleSelected(node: DsTreeNode): void {
    const mod = node.data as ModuleOutput;
    if (mod) this.selectModule(mod);
  }

  protected onModuleAction(node: DsTreeNode): void {
    const mod = node.data as ModuleOutput;
    if (mod && !mod.builtin) this.requestRemoveModule(mod);
  }

  // ── Module properties (inline block) — now versioned via workingManifest
  protected readonly showModuleProperties = signal(false);
  protected readonly propName = signal('');
  protected readonly propBaseUrl = signal('');
  protected readonly propHealth = signal('');
  protected readonly propSaving = signal(false);

  // ── Entry point form (now edits workingManifest when isEditing, legacy fallback otherwise)
  protected readonly showEpForm = signal(false);
  protected readonly editingEpId = signal<number | null>(null);
  protected readonly editingContentKey = signal<string | null>(null);
  protected readonly editingContentCategory = signal<EntryCategory | null>(null);
  protected readonly epForm = signal<EntryPointFormValue>(this.emptyEpForm());

  protected readonly loadPaths = [...EMBEDDED_LOAD_PATHS];
  protected readonly colors = COLORS;

  protected readonly pendingDeleteModule = signal<ModuleOutput | null>(null);
  protected readonly pendingDeleteEp = signal<EntryPointOutput | null>(null);
  protected readonly showColorPicker = signal(false);

  async ngOnInit(): Promise<void> {
    await Promise.all([this.reloadModules(), this.reloadAllEntryPoints()]);
    const first = this.modules()[0];
    if (first) this.selectModule(first);
  }

  // ── Module CRUD ────────────────────────────────────────────────────

  private async reloadModules(): Promise<void> {
    try {
      this.modules.set(await this.registry.listModules());
    } catch {
      this.error.set(this.i18n.t('registry.error.loadModules'));
    }
  }

  private async reloadAllEntryPoints(): Promise<void> {
    try {
      this.allEntryPoints.set(await this.registry.listAllEntryPoints());
    } catch {
      // non-fatal
    }
  }

  protected moduleStatus(mod: ModuleOutput): 'disabled' | 'empty' | 'active' {
    if (!mod.active) return 'disabled';
    const hasActiveEp = this.allEntryPoints().some((ep) => ep.moduleKey === mod.key && ep.active);
    return hasActiveEp ? 'active' : 'empty';
  }

  private async reloadEntryPoints(): Promise<void> {
    const mod = this.selectedModule();
    if (!mod) { this.entryPoints.set([]); return; }
    try {
      this.entryPoints.set(await this.registry.listEntryPoints(mod.key));
    } catch {
      this.error.set(this.i18n.t('registry.error.loadEntryPoints'));
    }
  }

  private async reloadVersions(): Promise<void> {
    const mod = this.selectedModule();
    if (!mod) { this.versions.set([]); return; }
    try {
      this.versions.set(await this.registry.listVersions(mod.key));
    } catch {
      this.versions.set([]);
    }
  }

  private async reloadActiveManifest(): Promise<void> {
    const mod = this.selectedModule();
    if (!mod) { this.activeManifest.set(null); return; }
    try {
      this.activeManifest.set(await this.registry.getActiveManifest(mod.key));
    } catch {
      this.activeManifest.set(null);
    }
  }

  requestRollback(version: VersionOutput): void {
    this.pendingRollback.set(version);
  }

  cancelRollback(): void {
    this.pendingRollback.set(null);
  }

  async confirmRollback(): Promise<void> {
    const mod = this.selectedModule();
    const version = this.pendingRollback();
    if (!mod || !version) return;
    this.pendingRollback.set(null);
    this.busy.set(true);
    try {
      const res = await this.registry.rollback(mod.key, version.id);
      // rollback now creates a draft, enter editing mode
      const draftManifest = await this.registry.getDraft(mod.key);
      if (draftManifest) {
        this.workingManifest.set(draftManifest);
        this.isEditing.set(true);
        this.showManifestInfo.set(true);
      }
      await Promise.all([this.reloadModules(), this.reloadEntryPoints(), this.reloadVersions()]);
    } catch (err) {
      this.error.set((err as Error).message);
    } finally {
      this.busy.set(false);
    }
  }

  async startCompare(version: VersionOutput): Promise<void> {
    const mod = this.selectedModule();
    if (!mod) return;
    this.comparingVersion.set(version);
    // default: left = selected version, right = active
    const versions = this.versions();
    const active = versions.find((v) => v.status === 'active') ?? null;
    this.compareLeftId.set(version.id);
    this.compareRightId.set(active?.id ?? null);
    this.busy.set(true);
    try {
      const [left, right] = await Promise.all([
        this.registry.getVersionManifest(mod.key, version.id),
        active ? this.registry.getVersionManifest(mod.key, active.id) : this.registry.getActiveManifest(mod.key),
      ]);
      this.compareLeftManifest.set(left);
      this.compareRightManifest.set(right);
      this.compareManifest.set(left);
      if (!left || !right) {
        this.compareDiff.set([]);
        return;
      }
      this.compareDiff.set(buildDiffSections(left, right));
    } catch (err) {
      this.error.set((err as Error).message);
    } finally {
      this.busy.set(false);
    }
  }

  async onCompareLeftChange(versionId: number): Promise<void> {
    const mod = this.selectedModule();
    if (!mod) return;
    this.compareLeftId.set(versionId);
    this.busy.set(true);
    try {
      const m = await this.registry.getVersionManifest(mod.key, versionId);
      this.compareLeftManifest.set(m);
      const left = m;
      const right = this.compareRightManifest();
      if (left && right) this.compareDiff.set(buildDiffSections(left, right));
    } catch (err) {
      this.error.set((err as Error).message);
    } finally {
      this.busy.set(false);
    }
  }

  async onCompareRightChange(versionId: number): Promise<void> {
    const mod = this.selectedModule();
    if (!mod) return;
    this.compareRightId.set(versionId);
    this.busy.set(true);
    try {
      const m = await this.registry.getVersionManifest(mod.key, versionId);
      this.compareRightManifest.set(m);
      const left = this.compareLeftManifest();
      const right = m;
      if (left && right) this.compareDiff.set(buildDiffSections(left, right));
    } catch (err) {
      this.error.set((err as Error).message);
    } finally {
      this.busy.set(false);
    }
  }

  closeCompare(): void {
    this.comparingVersion.set(null);
    this.compareManifest.set(null);
    this.compareLeftManifest.set(null);
    this.compareRightManifest.set(null);
    this.compareLeftId.set(null);
    this.compareRightId.set(null);
    this.compareDiff.set([]);
  }

  async selectModule(mod: ModuleOutput): Promise<void> {
    // if already editing, don't auto-switch modal — just switch context
    if (this.isEditing()) {
      this.isEditing.set(false);
      this.workingManifest.set(null);
    }
    this.selectedModule.set(mod);
    this.showDraftChoice.set(false);
    this.pendingDrafts.set([]);
    this.liveVersionForChoice.set(null);
    // Clear stale activation proposal when switching modules — only show when viewing active major & disabled
    this.showActivationProposal.set(false);
    this.activationResult.set(null);
    await Promise.all([this.reloadEntryPoints(), this.reloadVersions(), this.reloadActiveManifest()]);
    // Check for pending drafts more recent than live
    const vers = this.versions();
    const drafts = vers.filter((v) => v.status === 'draft');
    const live = vers.find((v) => v.status === 'active') ?? null;
    if (drafts.length > 0) {
      // sort drafts by installedAt desc already, but ensure
      // Show modal asking to continue editing or view live
      this.pendingDrafts.set(drafts);
      this.liveVersionForChoice.set(live);
      this.showDraftChoice.set(true);
    }
    // Auto-show activation proposal when viewing active major of a disabled module
    if (!mod.active && live && live.version.endsWith('.0') && drafts.length === 0) {
      this.activationResult.set({ moduleKey: mod.key, version: live.version });
      this.showActivationProposal.set(true);
    }
  }

  async chooseContinueEditing(): Promise<void> {
    const mod = this.selectedModule();
    if (!mod) return;
    this.showDraftChoice.set(false);
    this.error.set('');
    try {
      const draft = await this.registry.getDraft(mod.key);
      if (draft) {
        this.workingManifest.set(draft);
        this.isEditing.set(true);
        this.showManifestInfo.set(true);
      } else {
        // fallback create
        const res = await this.registry.createDraft(mod.key);
        this.workingManifest.set(res.manifest);
        this.isEditing.set(true);
        this.showManifestInfo.set(true);
      }
      await this.reloadVersions();
    } catch (err) {
      this.error.set((err as Error).message);
    }
  }

  chooseViewLive(): void {
    this.showDraftChoice.set(false);
    this.isEditing.set(false);
    this.workingManifest.set(null);
  }

  closeDraftChoice(): void {
    this.showDraftChoice.set(false);
  }

  newModule(): void {
    this.wizardMode.set('manual');
    this.installStep.set('basic');
    this.installError.set('');
    this.installResult.set(null);
    this.parsedManifest.set(null);
    this.moduleExists.set(false);
    this.previewActiveManifest.set(null);
    this.healthStatus.set(null);
    this.healthDetail.set('');
    this.diffSections.set([]);
    this.manualKey.set('');
    this.manualName.set('');
    this.manualBaseUrl.set('');
    this.manualHealth.set('');
    this.manualContentEntries.set([
      { key: '', name: '', description: '', type: 'iframe', url: '', path: '', loadPath: '', requiredRoles: '' },
    ]);
    this.manualRoles.set([{ key: '', name: '', description: '' }]);
    this.showInstallWizard.set(true);
  }

  toggleModuleProperties(): void {
    if (!this.isEditing()) {
      this.error.set(this.i18n.t('registry.error.enableEditProperties'));
      return;
    }
    if (this.showModuleProperties()) {
      this.showModuleProperties.set(false);
      return;
    }
    const m = this.workingManifest();
    if (m) {
      this.propName.set(m.name);
      this.propBaseUrl.set(m.baseUrl ?? '');
      this.propHealth.set(m.health ?? '');
    }
    this.showModuleProperties.set(true);
  }

  // Root fields via workingManifest
  updateRootField(field: 'name' | 'baseUrl' | 'health', value: string): void {
    if (!this.isEditing()) return;
    this.workingManifest.update((prev) => {
      if (!prev) return prev;
      return { ...prev, [field]: value } as PortalModuleManifest;
    });
  }

  async saveModuleProperties(): Promise<void> {
    if (!this.isEditing()) {
      this.error.set(this.i18n.t('registry.error.enableEditProperties'));
      return;
    }
    const m = this.workingManifest();
    if (!m) return;
    const name = this.propName().trim();
    if (!name) { this.error.set(this.i18n.t('registry.error.nameRequired')); return; }
    this.workingManifest.update((prev) => prev ? ({ ...prev, name, baseUrl: this.propBaseUrl().trim() || '', health: this.propHealth().trim() || undefined }) : prev);
    this.showModuleProperties.set(false);
  }

  async saveModule(): Promise<void> {
    const key = null;
    const name = this.propName().trim();
    if (!name) { this.error.set(this.i18n.t('registry.error.nameRequired')); return; }
    const payload: ModulePayload = {
      key: name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, ''),
      name,
      baseUrl: this.propBaseUrl().trim() || null,
      health: this.propHealth().trim() || null,
      active: false,
    };
    this.propSaving.set(true);
    this.error.set('');
    try {
      await this.registry.saveModule(payload);
      this.showModuleProperties.set(false);
      await this.reloadModules();
    } catch (err) {
      this.error.set((err as Error).message);
    } finally {
      this.propSaving.set(false);
    }
  }

  async toggleModuleActive(mod: ModuleOutput, active: boolean): Promise<void> {
    try {
      await this.registry.setModuleActive(mod.key, active);
      this.selectedModule.update((m) => (m && m.key === mod.key ? { ...m, active } : m));
      await this.reloadModules();
    } catch (err) {
      this.error.set((err as Error).message);
    }
  }

  requestRemoveModule(mod: ModuleOutput): void {
    this.pendingDeleteModule.set(mod);
  }

  cancelRemoveModule(): void {
    this.pendingDeleteModule.set(null);
  }

  async confirmRemoveModule(): Promise<void> {
    const mod = this.pendingDeleteModule();
    if (!mod) return;
    this.pendingDeleteModule.set(null);
    try {
      await this.registry.removeModule(mod.key);
      if (this.selectedModule()?.key === mod.key) {
        this.selectedModule.set(null);
        this.entryPoints.set([]);
      }
      await this.reloadModules();
    } catch (err) {
      this.error.set((err as Error).message);
    }
  }

  // ── Versions section ─────────────────────────────────────────────

  toggleVersions(): void {
    this.showVersions.update((v) => !v);
  }

  // ── Manifest Info ────────────────────────────────────────────────

  toggleManifestInfo(): void {
    this.showManifestInfo.update((v) => !v);
  }

  showJsonViewer(): void {
    this.showJsonModal.set(true);
  }

  closeJsonModal(): void {
    this.showJsonModal.set(false);
  }

  async copyManifestJson(): Promise<void> {
    const m = this.workingManifest() ?? this.activeManifest();
    if (!m) return;
    try {
      await navigator.clipboard.writeText(JSON.stringify(m, null, 2));
    } catch {
      // fallback: no-op
    }
  }

  // ── Draft / Editing ──────────────────────────────────────────────

  async startEditing(): Promise<void> {
    const mod = this.selectedModule();
    if (!mod) return;
    this.error.set('');
    this.validationErrors.set([]);
    try {
      // Check if draft already exists (latest)
      const draft = await this.registry.getDraft(mod.key);
      if (draft) {
        this.workingManifest.set(draft);
        this.isEditing.set(true);
        this.showManifestInfo.set(true);
        return;
      }
      // Create new draft from active version
      const result = await this.registry.createDraft(mod.key);
      this.workingManifest.set(result.manifest);
      this.isEditing.set(true);
      this.showManifestInfo.set(true);
      await this.reloadVersions();
    } catch (err) {
      this.error.set((err as Error).message);
    }
  }

  async saveDraft(): Promise<void> {
    const mod = this.selectedModule();
    const manifest = this.workingManifest();
    if (!mod || !manifest) return;
    this.busy.set(true);
    this.error.set('');
    try {
      await this.registry.saveDraft(mod.key, manifest);
      await this.reloadVersions();
    } catch (err) {
      this.error.set((err as Error).message);
    } finally {
      this.busy.set(false);
    }
  }

  async applyChanges(): Promise<void> {
    const mod = this.selectedModule();
    const manifest = this.workingManifest();
    if (!mod || !manifest) return;
    this.busy.set(true);
    this.error.set('');
    this.validationErrors.set([]);
    try {
      const result = await this.registry.applyDraft(mod.key, manifest);
      if (result.errors && result.errors.length > 0) {
        this.validationErrors.set(result.errors);
        return;
      }
      // Success — propose activation only for major versions (x.0) and when module disabled
      const isMajor = result.version.endsWith('.0');
      if (result.shouldActivate && isMajor) {
        this.activationResult.set({ moduleKey: result.moduleKey, version: result.version });
        this.showActivationProposal.set(true);
      }
      this.isEditing.set(false);
      this.workingManifest.set(null);
      await Promise.all([this.reloadModules(), this.reloadEntryPoints(), this.reloadVersions(), this.reloadActiveManifest()]);
    } catch (err) {
      this.error.set((err as Error).message);
    } finally {
      this.busy.set(false);
    }
  }

  async confirmActivate(): Promise<void> {
    const result = this.activationResult();
    if (!result) return;
    this.showActivationProposal.set(false);
    this.activationResult.set(null);
    try {
      await this.registry.setModuleActive(result.moduleKey, true);
      this.selectedModule.update((m) => (m && m.key === result.moduleKey ? { ...m, active: true } : m));
      await this.reloadModules();
    } catch (err) {
      this.error.set((err as Error).message);
    }
  }

  dismissActivationProposal(): void {
    this.showActivationProposal.set(false);
    this.activationResult.set(null);
  }

  async discardDraft(): Promise<void> {
    const mod = this.selectedModule();
    if (!mod) return;
    try {
      await this.registry.discardDraft(mod.key);
      // If there are still drafts left, keep editing with latest draft; otherwise exit editing
      const remaining = await this.registry.listVersions(mod.key);
      const drafts = remaining.filter((v) => v.status === 'draft');
      if (drafts.length > 0) {
        const latest = await this.registry.getDraft(mod.key);
        if (latest) this.workingManifest.set(latest);
        else {
          this.isEditing.set(false);
          this.workingManifest.set(null);
        }
      } else {
        this.isEditing.set(false);
        this.workingManifest.set(null);
      }
      this.validationErrors.set([]);
      this.versions.set(remaining);
      await this.reloadActiveManifest();
    } catch (err) {
      this.error.set((err as Error).message);
    }
  }

  async loadVersionAsDraft(version: VersionOutput): Promise<void> {
    const mod = this.selectedModule();
    if (!mod) return;
    this.error.set('');
    try {
      const result = await this.registry.loadVersion(mod.key, version.id);
      this.workingManifest.set(result.manifest);
      this.isEditing.set(true);
      this.showManifestInfo.set(true);
      await this.reloadVersions();
    } catch (err) {
      this.error.set((err as Error).message);
    }
  }

  async downloadVersion(version: VersionOutput): Promise<void> {
    const mod = this.selectedModule();
    if (!mod) return;
    try {
      await this.registry.downloadVersion(mod.key, version.id);
    } catch (err) {
      this.error.set((err as Error).message);
    }
  }

  // ── Manifest section mutations ──────────────────────────────────────

  protected readonly ROLE_KEY_RE = /^[a-z0-9][a-z0-9-]{0,63}$/;
  protected readonly SCOPE_RE = /^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*$/;
  protected readonly EVENT_SUBJECT_RE = /^[a-z0-9][a-z0-9_-]*(?:\.[a-z0-9_*><-]+)+$/;

  addRole(): void {
    if (!this.isEditing()) return;
    const m = this.workingManifest();
    if (!m) return;
    const roles = m.security.roles;
    let newKey = 'new-role';
    let i = 1;
    while (roles.some((r) => r.key === newKey)) { newKey = `new-role-${i++}`; }
    this.workingManifest.update((prev) => prev ? ({
      ...prev, security: { roles: [...prev.security.roles, { key: newKey, name: 'New Role', description: '' }] }
    }) : prev);
  }

  updateRoleField(index: number, field: 'key' | 'name' | 'description', value: string): void {
    if (!this.isEditing()) return;
    this.workingManifest.update((prev) => {
      if (!prev) return prev;
      const roles = [...prev.security.roles];
      roles[index] = { ...roles[index], [field]: value };
      return { ...prev, security: { roles } };
    });
  }

  removeRole(index: number): void {
    if (!this.isEditing()) return;
    this.workingManifest.update((prev) => {
      if (!prev) return prev;
      const roles = prev.security.roles.filter((_, i) => i !== index);
      return { ...prev, security: { roles } };
    });
  }

  roleKeyValid(key: string): boolean {
    return this.ROLE_KEY_RE.test(key);
  }

  addCapability(): void {
    if (!this.isEditing()) return;
    const m = this.workingManifest();
    if (!m) return;
    this.workingManifest.update((prev) => prev ? ({
      ...prev, capabilities: [...(prev.capabilities ?? []), { scope: '' }]
    }) : prev);
  }

  updateCapabilityScope(index: number, value: string): void {
    if (!this.isEditing()) return;
    this.workingManifest.update((prev) => {
      if (!prev) return prev;
      const caps = [...(prev.capabilities ?? [])];
      caps[index] = { scope: value };
      return { ...prev, capabilities: caps };
    });
  }

  removeCapability(index: number): void {
    if (!this.isEditing()) return;
    this.workingManifest.update((prev) => {
      if (!prev) return prev;
      const caps = (prev.capabilities ?? []).filter((_, i) => i !== index);
      return { ...prev, capabilities: caps.length > 0 ? caps : undefined };
    });
  }

  scopeValid(scope: string): boolean {
    return this.SCOPE_RE.test(scope);
  }

  addEvent(section: 'published' | 'consumed'): void {
    if (!this.isEditing()) return;
    const m = this.workingManifest();
    if (!m) return;
    const events = { ...(m.events ?? {}) };
    const list = [...(events[section] ?? [])];
    list.push({ subject: '', schemaVersion: 1, description: '' });
    events[section] = list;
    this.workingManifest.update((prev) => prev ? ({ ...prev, events }) : prev);
  }

  updateEventField(section: 'published' | 'consumed', index: number, field: 'subject' | 'schemaVersion' | 'description', value: string | number): void {
    if (!this.isEditing()) return;
    this.workingManifest.update((prev) => {
      if (!prev) return prev;
      const events = { ...(prev.events ?? {}) };
      const list = [...(events[section] ?? [])];
      list[index] = { ...list[index], [field]: value };
      events[section] = list;
      return { ...prev, events };
    });
  }

  removeEvent(section: 'published' | 'consumed', index: number): void {
    if (!this.isEditing()) return;
    this.workingManifest.update((prev) => {
      if (!prev) return prev;
      const events = { ...(prev.events ?? {}) };
      const list = (events[section] ?? []).filter((_, i) => i !== index);
      events[section] = list.length > 0 ? list : undefined;
      if (!events.published && !events.consumed) return { ...prev, events: undefined };
      return { ...prev, events };
    });
  }

  eventSubjectValid(subject: string): boolean {
    return this.EVENT_SUBJECT_RE.test(subject);
  }

  // ── Content via workingManifest ───────────────────────────────────────

  protected readonly contentCategories: readonly string[] = CONTENT_GROUPS.map((g) => g.key);

  contentEntries(category: string): ManifestContentEntry[] {
    const m = this.workingManifest() ?? this.activeManifest();
    if (!m) return [];
    return (m.content?.[category as keyof typeof m.content] ?? []) as ManifestContentEntry[];
  }

  addContent(category: 'applications' | 'features' | 'adminSettings' | 'userSettings'): void {
    if (!this.isEditing()) return;
    const m = this.workingManifest();
    if (!m) return;
    const list = [...((m.content?.[category] ?? []) as ManifestContentEntry[])];
    let newKey = 'new-entry';
    let i = 1;
    const allKeys = new Set<string>();
    for (const cat of CONTENT_GROUPS) {
      for (const e of (m.content?.[cat.key as keyof typeof m.content] ?? []) as ManifestContentEntry[]) allKeys.add(e.key);
    }
    while (allKeys.has(newKey)) newKey = `new-entry-${i++}`;
    list.push({ key: newKey, name: 'New Entry', type: 'embedded', loadPath: this.loadPaths[0] ?? 'default' } as ManifestContentEntry);
    this.workingManifest.update((prev) => {
      if (!prev) return prev;
      return { ...prev, content: { ...prev.content, [category]: list } };
    });
  }

  updateContentField(category: string, index: number, field: keyof ManifestContentEntry, value: unknown): void {
    if (!this.isEditing()) return;
    this.workingManifest.update((prev) => {
      if (!prev) return prev;
      const list = [...((prev.content?.[category as keyof typeof prev.content] ?? []) as ManifestContentEntry[])];
      list[index] = { ...list[index], [field]: value } as ManifestContentEntry;
      return { ...prev, content: { ...prev.content, [category]: list } };
    });
  }

  removeContent(category: string, index: number): void {
    if (!this.isEditing()) return;
    this.workingManifest.update((prev) => {
      if (!prev) return prev;
      const list = ((prev.content?.[category as keyof typeof prev.content] ?? []) as ManifestContentEntry[]).filter((_, i) => i !== index);
      return { ...prev, content: { ...prev.content, [category]: list } };
    });
  }

  // ── Entry Point CRUD (legacy: now gated, but kept for outside-edit fallback) ───────────────────────────────────────────────

  newEntryPoint(): void {
    if (!this.isEditing()) {
      // In new model, content is via workingManifest
      this.addContent('applications');
      return;
    }
    // When editing, delegate to content add (applications default)
    this.addContent('applications');
  }

  editEntryPoint(ep: EntryPointOutput): void {
    if (!this.isEditing()) {
      this.error.set(this.i18n.t('registry.error.enableEditContent'));
      return;
    }
    // Find content entry by key across categories
    const m = this.workingManifest();
    if (!m) return;
    for (const cat of CONTENT_GROUPS) {
      const list = (m.content?.[cat.key as keyof typeof m.content] ?? []) as ManifestContentEntry[];
      const idx = list.findIndex((e) => e.key === ep.entryKey);
      if (idx >= 0) {
        this.editingContentKey.set(ep.entryKey);
        this.editingContentCategory.set(cat.key as unknown as EntryCategory);
        // Map to epForm for modal reuse? Instead open content edit inline
        this.epForm.set({
          moduleKey: ep.moduleKey,
          entryKey: ep.entryKey,
          category: cat.key as unknown as EntryCategory,
          name: ep.name,
          description: ep.description ?? '',
          type: ep.type,
          url: ep.url ?? '',
          sandbox: ep.sandbox?.join(', ') ?? '',
          allow: ep.allow ?? '',
          loadPath: ep.loadPath ?? '',
          entryUrl: ep.entryUrl ?? '',
          element: ep.element ?? '',
          parentEntryKey: ep.parentEntryKey ?? '',
          active: ep.active,
          color: ep.color ?? COLORS[0],
          multi: ep.multi,
        });
        this.editingEpId.set(ep.id);
        this.showEpForm.set(true);
        return;
      }
    }
  }

  closeEpForm(): void {
    this.showEpForm.set(false);
    this.showColorPicker.set(false);
    this.editingContentKey.set(null);
    this.editingContentCategory.set(null);
    this.error.set('');
  }

  setEpField<K extends keyof EntryPointFormValue>(key: K, value: EntryPointFormValue[K]): void {
    this.epForm.update((f) => ({ ...f, [key]: value }));
  }

  setEpType(type: ModuleType): void {
    this.epForm.update((f) => ({ ...f, type }));
  }

  setEpCategory(category: EntryCategory): void {
    this.epForm.update((f) => ({ ...f, category }));
  }

  onNameInput(name: string): void {
    this.epForm.update((f) => ({
      ...f,
      name,
      entryKey: f.entryKey && !this.autoKeyGenerated ? f.entryKey : this.toKebabCase(name),
    }));
  }

  private autoKeyGenerated = false;

  private toKebabCase(value: string): string {
    return value
      .replace(/[^a-zA-Z0-9\s_-]/g, '_')
      .replace(/[\s_]+/g, '-')
      .replace(/^-+|-+$/g, '')
      .toLowerCase();
  }

  async saveEntryPoint(): Promise<void> {
    // When editing, save to workingManifest instead of direct API
    if (this.isEditing()) {
      const f = this.epForm();
      if (!f.entryKey.trim()) { this.error.set(this.i18n.t('registry.error.entryKeyRequired')); return; }
      if (!f.name.trim()) { this.error.set(this.i18n.t('registry.error.nameRequired')); return; }
      const catKeyMap: Record<string, string> = { applications: 'applications', features: 'features', 'admin-settings': 'adminSettings', 'adminSettings': 'adminSettings', 'user-settings': 'userSettings', 'userSettings': 'userSettings', settings: 'adminSettings' };
      const cat = catKeyMap[f.category] ?? 'applications';
      const entry: ManifestContentEntry = {
        key: f.entryKey.trim(),
        name: f.name.trim(),
        description: f.description?.trim() || undefined,
        type: f.type,
        url: (f.type === 'iframe' || f.type === 'link') ? f.url?.trim() : undefined,
        path: undefined,
        loadPath: f.type === 'embedded' ? f.loadPath?.trim() : undefined,
        entryUrl: f.type === 'mfe' ? f.entryUrl?.trim() : undefined,
        element: f.type === 'mfe' ? f.element?.trim() : undefined,
        sandbox: f.type === 'iframe' && f.sandbox ? f.sandbox.split(',').map((s) => s.trim()).filter(Boolean) : undefined,
        allow: f.type === 'iframe' ? f.allow?.trim() || undefined : undefined,
        requiredRoles: undefined,
        multi: f.multi,
        sortOrder: 0,
      } as unknown as ManifestContentEntry;
      const editingKey = this.editingContentKey();
      const editingCat = this.editingContentCategory();
      this.workingManifest.update((prev) => {
        if (!prev) return prev;
        const content = { ...prev.content } as Record<string, ManifestContentEntry[]>;
        // Remove from old category if editing
        if (editingKey && editingCat) {
          const oldCatKey = catKeyMap[editingCat] ?? editingCat;
          content[oldCatKey] = (content[oldCatKey] ?? []).filter((e) => e.key !== editingKey);
        }
        // If creating, ensure no duplicate key
        if (!editingKey) {
          const allKeys = new Set<string>();
          for (const k of Object.keys(content)) for (const e of content[k] ?? []) allKeys.add(e.key);
          if (allKeys.has(entry.key)) { this.error.set(this.i18n.t('registry.error.entryKeyExists', { key: entry.key })); return prev; }
        }
        content[cat] = [...(content[cat] ?? []), entry];
        return { ...prev, content: content as unknown as PortalModuleManifest['content'] };
      });
      if (this.error()) return;
      this.closeEpForm();
      return;
    }
    // Legacy non-editing path (should be blocked, but keep for safety)
    const f = this.epForm();
    if (!f.entryKey.trim()) { this.error.set(this.i18n.t('registry.error.entryKeyRequired')); return; }
    if (!f.name.trim()) { this.error.set(this.i18n.t('registry.error.nameRequired')); return; }
    if (f.type === 'iframe' && !f.url?.trim()) { this.error.set(this.i18n.t('registry.error.iframeRequiresUrl')); return; }
    if (f.type === 'embedded' && !f.loadPath?.trim()) { this.error.set(this.i18n.t('registry.error.embeddedRequiresLoadPath')); return; }
    if (f.type === 'mfe') {
      if (!f.entryUrl?.trim()) { this.error.set(this.i18n.t('registry.error.mfeRequiresEntryUrl')); return; }
      if (!f.element?.trim()) { this.error.set(this.i18n.t('registry.error.mfeRequiresElement')); return; }
    }
    this.busy.set(true);
    this.error.set('');
    try {
      await this.registry.saveEntryPoint({
        moduleKey: f.moduleKey,
        entryKey: f.entryKey.trim(),
        category: f.category,
        name: f.name.trim(),
        description: f.description?.trim() || undefined,
        type: f.type,
        url: (f.type === 'iframe' || f.type === 'link') ? f.url?.trim() : undefined,
        sandbox: f.type === 'iframe' && f.sandbox ? f.sandbox.split(',').map((s) => s.trim()).filter(Boolean) : undefined,
        allow: f.type === 'iframe' ? f.allow?.trim() || undefined : undefined,
        loadPath: f.type === 'embedded' ? f.loadPath?.trim() : undefined,
        entryUrl: f.type === 'mfe' ? f.entryUrl?.trim() : undefined,
        element: f.type === 'mfe' ? f.element?.trim() : undefined,
        parentEntryKey: f.parentEntryKey?.trim() || null,
        active: f.active,
        color: f.color?.trim() || undefined,
        multi: f.category === 'applications' ? (f.multi ?? false) : false,
      });
      this.closeEpForm();
      await Promise.all([this.reloadEntryPoints(), this.reloadAllEntryPoints()]);
    } catch (err) {
      this.error.set((err as Error).message);
    } finally {
      this.busy.set(false);
    }
  }

  requestRemoveEntryPoint(ep: EntryPointOutput): void {
    this.pendingDeleteEp.set(ep);
  }

  cancelRemoveEntryPoint(): void {
    this.pendingDeleteEp.set(null);
  }

  async confirmRemoveEntryPoint(): Promise<void> {
    const ep = this.pendingDeleteEp();
    if (!ep) return;
    this.pendingDeleteEp.set(null);
    if (this.isEditing()) {
      // Remove from workingManifest
      const m = this.workingManifest();
      if (!m) return;
      for (const cat of CONTENT_GROUPS) {
        const list = (m.content?.[cat.key as keyof typeof m.content] ?? []) as ManifestContentEntry[];
        const idx = list.findIndex((e) => e.key === ep.entryKey);
        if (idx >= 0) { this.removeContent(cat.key as never, idx); break; }
      }
      return;
    }
    try {
      await this.registry.removeEntryPoint(ep.id);
      await Promise.all([this.reloadEntryPoints(), this.reloadAllEntryPoints()]);
    } catch (err) {
      this.error.set((err as Error).message);
    }
  }

  selectColor(c: string): void {
    this.setEpField('color', c);
    this.showColorPicker.set(false);
  }

  async toggleEpActive(ep: EntryPointOutput): Promise<void> {
    if (this.isEditing()) {
      this.error.set(this.i18n.t('registry.error.toggleViaManifest'));
      return;
    }
    try {
      await this.registry.saveEntryPoint({
        ...ep,
        active: !ep.active,
        sandbox: ep.sandbox,
      });
      await Promise.all([this.reloadEntryPoints(), this.reloadAllEntryPoints()]);
    } catch (err) {
      this.error.set((err as Error).message);
    }
  }

  // ── Install Wizard ──────────────────────────────────────────────────
  protected readonly showInstallWizard = signal(false);
  protected readonly wizardMode = signal<'quick' | 'manual'>('quick');
  protected readonly installStep = signal<string>('input');
  protected readonly installInputMode = signal<'url' | 'json'>('url');
  protected readonly installInput = signal('');
  protected readonly parsedManifest = signal<PortalModuleManifest | null>(null);
  protected readonly installError = signal('');
  protected readonly installBusy = signal(false);
  protected readonly installResult = signal<{ ok: boolean; moduleKey?: string; version?: string; error?: string } | null>(null);

  // Preview state
  protected readonly moduleExists = signal(false);
  protected readonly previewActiveManifest = signal<PortalModuleManifest | null>(null);
  protected readonly healthStatus = signal<'ok' | 'error' | 'checking' | null>(null);
  protected readonly healthDetail = signal('');
  protected readonly diffSections = signal<PreviewSection[]>([]);

  // ── Manual creation form state ──────────────────────────────────────
  protected readonly manualKey = signal('');
  protected readonly manualName = signal('');
  protected readonly manualBaseUrl = signal('');
  protected readonly manualHealth = signal('');
  protected readonly manualContentEntries = signal<Array<{
    key: string; name: string; description: string;
    type: 'iframe' | 'embedded' | 'mfe' | 'link';
    url: string; path: string; loadPath: string; requiredRoles: string;
  }>>([{ key: '', name: '', description: '', type: 'iframe', url: '', path: '', loadPath: '', requiredRoles: '' }]);
  protected readonly manualRoles = signal<Array<{ key: string; name: string; description: string }>>(
    [{ key: '', name: '', description: '' }]
  );

  // ── Wizard step definitions ─────────────────────────────────────────
  protected get quickStepDefs(): WizardStep[] {
    return [
      { key: 'input', title: this.i18n.t('registry.wizard.stepManifestSource'), description: this.i18n.t('registry.wizard.stepManifestSourceDesc') },
      { key: 'preview', title: this.i18n.t('registry.wizard.stepPreview'), description: this.i18n.t('registry.wizard.stepPreviewDesc') },
      { key: 'done', title: this.i18n.t('registry.wizard.stepResult'), description: this.i18n.t('registry.wizard.stepResultDesc') },
    ];
  }

  protected get manualStepDefs(): WizardStep[] {
    return [
      { key: 'basic', title: this.i18n.t('registry.wizard.stepBasic'), description: this.i18n.t('registry.wizard.stepBasicDesc') },
      { key: 'content', title: this.i18n.t('registry.wizard.stepContent'), description: this.i18n.t('registry.wizard.stepContentDesc') },
      { key: 'security', title: this.i18n.t('registry.wizard.stepSecurity'), description: this.i18n.t('registry.wizard.stepSecurityDesc') },
      { key: 'review', title: this.i18n.t('registry.wizard.stepReview'), description: this.i18n.t('registry.wizard.stepReviewDesc') },
      { key: 'done', title: this.i18n.t('registry.wizard.stepResult'), description: this.i18n.t('registry.wizard.stepResultDesc') },
    ];
  }

  protected readonly activeStepDefs = computed(() =>
    this.wizardMode() === 'quick' ? this.quickStepDefs : this.manualStepDefs
  );

  protected readonly wizardStepIndex = computed(() => {
    const step = this.installStep();
    if (this.wizardMode() === 'quick') {
      if (step === 'input') return 0;
      if (step === 'preview') return 1;
      return 2;
    }
    const map: Record<string, number> = { basic: 0, content: 1, security: 2, review: 3, done: 4 };
    return map[step] ?? 0;
  });

  protected readonly wizardCompleted = computed<boolean[]>(() => {
    const idx = this.wizardStepIndex();
    return this.activeStepDefs().map((_, i) => i < idx);
  });

  protected readonly wizardErrors = computed<string[]>(() => {
    return this.activeStepDefs().map((_, i) => {
      if (i === this.wizardStepIndex() && this.installError()) return this.installError();
      return '';
    });
  });

  protected readonly manualManifest = computed<PortalModuleManifest>(() => {
    const entries = this.manualContentEntries();
    const apps: ManifestContentEntry[] = [];
    for (const e of entries) {
      if (!e.key || !e.name) continue;
      const entry: ManifestContentEntry = {
        key: e.key, name: e.name, type: e.type as ManifestContentEntry['type'],
        ...(e.description ? { description: e.description } : {}),
        ...(e.url ? { url: e.url } : {}),
        ...(e.path ? { path: e.path } : {}),
        ...(e.loadPath ? { loadPath: e.loadPath } : {}),
        ...(e.requiredRoles ? { requiredRoles: e.requiredRoles.split(',').map((r: string) => r.trim()).filter(Boolean) } : {}),
      };
      apps.push(entry);
    }
    const roles = this.manualRoles()
      .filter(r => r.key && r.name)
      .map(r => ({ key: r.key, name: r.name, ...(r.description ? { description: r.description } : {}) }));
    return {
      manifestVersion: 1,
      key: this.manualKey(),
      name: this.manualName(),
      baseUrl: this.manualBaseUrl(),
      ...(this.manualHealth() ? { health: this.manualHealth() } : {}),
      content: { applications: apps, features: [], adminSettings: [], userSettings: [] },
      ...(roles.length ? { security: { roles } } : {}),
    } as PortalModuleManifest;
  });

  // ── Wizard navigation ───────────────────────────────────────────────
  onInstallNext(): void {
    const mode = this.wizardMode();
    const step = this.installStep();

    if (mode === 'quick') {
      if (step === 'input') { this.fetchManifest(); return; }
      if (step === 'preview') { this.executeInstall(); return; }
      this.closeInstallWizard();
      return;
    }

    // Manual mode
    if (step === 'basic') {
      if (!this.validateManualBasic()) return;
      this.installStep.set('content');
    } else if (step === 'content') {
      this.installStep.set('security');
    } else if (step === 'security') {
      this.buildManualPreview();
    } else if (step === 'review') {
      this.executeInstall();
    } else {
      this.closeInstallWizard();
    }
  }

  onInstallBack(): void {
    const step = this.installStep();
    if (this.wizardMode() === 'quick') {
      if (step === 'preview') this.installStep.set('input');
      return;
    }
    const backMap: Record<string, string> = { content: 'basic', security: 'content', review: 'security' };
    if (backMap[step]) this.installStep.set(backMap[step]);
  }

  private validateManualBasic(): boolean {
    const key = this.manualKey().trim();
    const name = this.manualName().trim();
    const baseUrl = this.manualBaseUrl().trim();
    if (!key) { this.installError.set(this.i18n.t('registry.wizard.error.keyRequired')); return false; }
    if (!/^[a-z0-9][a-z0-9-]{0,63}$/.test(key)) { this.installError.set(this.i18n.t('registry.wizard.error.keyFormat')); return false; }
    if (!name) { this.installError.set(this.i18n.t('registry.wizard.error.nameRequired')); return false; }
    if (!baseUrl) { this.installError.set(this.i18n.t('registry.wizard.error.baseUrlRequired')); return false; }
    try { new URL(baseUrl); } catch { this.installError.set(this.i18n.t('registry.wizard.error.baseUrlInvalid')); return false; }
    this.installError.set('');
    return true;
  }

  private async buildManualPreview(): Promise<void> {
    const manifest = this.manualManifest();
    this.parsedManifest.set(manifest);
    await this.buildPreview(manifest);
    this.installStep.set('review');
  }

  addManualContentEntry(): void {
    this.manualContentEntries.update(entries => [
      ...entries,
      { key: '', name: '', description: '', type: 'iframe' as const, url: '', path: '', loadPath: '', requiredRoles: '' },
    ]);
  }

  removeManualContentEntry(index: number): void {
    this.manualContentEntries.update(entries => entries.filter((_, i) => i !== index));
  }

  updateManualContentEntry(index: number, field: string, value: string): void {
    this.manualContentEntries.update(entries => entries.map((e, i) =>
      i === index ? { ...e, [field]: value } : e
    ));
  }

  addManualRole(): void {
    this.manualRoles.update(roles => [...roles, { key: '', name: '', description: '' }]);
  }

  removeManualRole(index: number): void {
    this.manualRoles.update(roles => roles.filter((_, i) => i !== index));
  }

  updateManualRole(index: number, field: string, value: string): void {
    this.manualRoles.update(roles => roles.map((r, i) =>
      i === index ? { ...r, [field]: value } : r
    ));
  }

  openInstallWizard(): void {
    this.wizardMode.set('quick');
    this.installStep.set('input');
    this.installInputMode.set('url');
    this.installInput.set('');
    this.parsedManifest.set(null);
    this.installError.set('');
    this.installResult.set(null);
    this.moduleExists.set(false);
    this.previewActiveManifest.set(null);
    this.healthStatus.set(null);
    this.healthDetail.set('');
    this.diffSections.set([]);
    this.showInstallWizard.set(true);
  }

  openInstallWizardBlank(): void {
    this.installStep.set('input');
    this.installInputMode.set('json');
    this.installInput.set('');
    this.parsedManifest.set(null);
    this.installError.set('');
    this.installResult.set(null);
    this.moduleExists.set(false);
    this.previewActiveManifest.set(null);
    this.healthStatus.set(null);
    this.healthDetail.set('');
    this.diffSections.set([]);
    this.showInstallWizard.set(true);
  }

  closeInstallWizard(): void {
    this.showInstallWizard.set(false);
  }

  setInstallInputMode(mode: 'url' | 'json'): void {
    this.installInputMode.set(mode);
    this.installInput.set('');
    this.installError.set('');
  }

  fillDummyManifest(): void {
    const dummy = JSON.stringify({
      manifestVersion: 1,
      key: 'my-module',
      name: 'My Module',
      baseUrl: 'http://localhost:3000',
      health: '/healthz',
      content: {
        applications: [
          {
            key: 'my-app',
            name: 'My App',
            type: 'iframe',
            url: 'http://localhost:3000/app',
            requiredRoles: ['app-user'],
          },
        ],
        features: [],
        adminSettings: [],
        userSettings: [],
      },
      security: {
        roles: [
          { key: 'app-user', name: 'App User', description: 'Standard user role' },
        ],
      },
    }, null, 2);
    this.installInput.set(dummy);
    this.installError.set('');
  }

  async fetchManifest(): Promise<void> {
    const input = this.installInput().trim();
    if (!input) { this.installError.set(this.i18n.t('registry.wizard.error.inputRequired')); return; }

    this.installBusy.set(true);
    this.installError.set('');
    try {
      let manifest: PortalModuleManifest;
      if (this.installInputMode() === 'url') {
        const res = await this.registry.fetchManifestFromUrl(input);
        manifest = res.manifest;
      } else {
        manifest = JSON.parse(input) as PortalModuleManifest;
      }
      this.parsedManifest.set(manifest);
      await this.buildPreview(manifest);
      this.installStep.set('preview');
    } catch (err) {
      this.installError.set((err as Error).message);
    } finally {
      this.installBusy.set(false);
    }
  }

  private async buildPreview(m: PortalModuleManifest): Promise<void> {
    // Check if module already exists
    const existing = this.modules().find((mod) => mod.key === m.key);
    this.moduleExists.set(!!existing);

    // Fetch active manifest if exists
    let active: PortalModuleManifest | null = null;
    if (existing) {
      active = await this.registry.getActiveManifest(m.key);
      this.previewActiveManifest.set(active);
    } else {
      this.previewActiveManifest.set(null);
    }

    // Health check
    if (m.health && m.baseUrl) {
      this.healthStatus.set('checking');
      this.healthDetail.set('');
      try {
        const url = m.baseUrl.replace(/\/+$/, '') + m.health;
        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), 5000);
        const resp = await fetch(url, { signal: controller.signal });
        clearTimeout(timeout);
        if (resp.ok) {
          this.healthStatus.set('ok');
          this.healthDetail.set(`${resp.status} ${resp.statusText}`);
        } else {
          this.healthStatus.set('error');
          this.healthDetail.set(`${resp.status} ${resp.statusText}`);
        }
      } catch (err) {
        this.healthStatus.set('error');
        this.healthDetail.set((err as Error).message);
      }
    } else {
      this.healthStatus.set(null);
    }

    // Build diff sections
    this.diffSections.set(buildDiffSections(active, m));
  }

  async executeInstall(): Promise<void> {
    const manifest = this.parsedManifest();
    if (!manifest) return;

    this.installBusy.set(true);
    this.installError.set('');
    try {
      const result = await this.registry.installManifest(manifest);
      this.installResult.set(result);
      this.installStep.set('done');
      await this.reloadModules();
      await this.reloadAllEntryPoints();
      const fresh = this.modules().find((m) => m.key === result.moduleKey);
      if (fresh) {
        await this.selectModule(fresh);
        // Show activation proposal when we just created a major version and module is disabled
        if (!fresh.active && result.version.endsWith('.0')) {
          this.activationResult.set({ moduleKey: result.moduleKey, version: result.version });
          this.showActivationProposal.set(true);
        }
      }
    } catch (err) {
      this.installError.set((err as Error).message);
    } finally {
      this.installBusy.set(false);
    }
  }

  async executeInstallAsNew(): Promise<void> {
    const manifest = this.parsedManifest();
    if (!manifest) return;

    // Find existing keys with the same base to determine next suffix
    const baseKey = manifest.key;
    const existingKeys = this.modules().map((m) => m.key).filter((k) => k === baseKey || k.startsWith(baseKey + '-'));
    let nextNum = 2;
    for (const k of existingKeys) {
      const match = k.match(new RegExp(`^${baseKey.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}-(\\d+)$`));
      if (match) nextNum = Math.max(nextNum, parseInt(match[1], 10) + 1);
    }

    const newManifest = { ...manifest, key: `${baseKey}-${nextNum}`, name: `${manifest.name} ${nextNum}` };
    this.parsedManifest.set(newManifest);

    this.installBusy.set(true);
    this.installError.set('');
    try {
      const result = await this.registry.installManifest(newManifest);
      this.installResult.set(result);
      this.installStep.set('done');
      await this.reloadModules();
      await this.reloadAllEntryPoints();
      const fresh = this.modules().find((m) => m.key === result.moduleKey);
      if (fresh) {
        await this.selectModule(fresh);
        if (!fresh.active && result.version.endsWith('.0')) {
          this.activationResult.set({ moduleKey: result.moduleKey, version: result.version });
          this.showActivationProposal.set(true);
        }
      }
    } catch (err) {
      this.installError.set((err as Error).message);
    } finally {
      this.installBusy.set(false);
    }
  }

  // ── Helpers ────────────────────────────────────────────────────────

  private emptyEpForm(): EntryPointFormValue {
    return {
      moduleKey: '',
      entryKey: '',
      category: 'applications',
      name: '',
      description: '',
      type: 'embedded',
      url: '',
      sandbox: 'allow-scripts, allow-popups, allow-forms',
      allow: 'clipboard-write; fullscreen',
      loadPath: EMBEDDED_LOAD_PATHS[0] ?? '',
      entryUrl: '',
      element: '',
      parentEntryKey: '',
      active: true,
      color: COLORS[0],
      multi: false,
    };
  }

  typeBadge(type: ModuleType): string {
    switch (type) {
      case 'iframe': return 'ds-badge ds-badge-warning';
      case 'embedded': return 'ds-badge ds-badge-info';
      case 'mfe': return 'ds-badge ds-badge-success';
      case 'link': return 'ds-badge ds-badge-danger';
    }
  }

  epTarget(ep: EntryPointOutput): string {
    if (ep.type === 'iframe') return ep.url ?? '';
    if (ep.type === 'embedded') return `loadPath:${ep.loadPath}`;
    if (ep.type === 'link') return ep.url ?? '';
    return ep.element ?? '';
  }

  contentTarget(entry: ManifestContentEntry, baseUrl: string): string {
    if (entry.type === 'iframe' || entry.type === 'link') return entry.url ?? entry.path ?? '';
    if (entry.type === 'embedded') return `loadPath:${entry.loadPath}`;
    return entry.element ?? entry.entryUrl ?? entry.path ?? '';
  }

  protected sectionTitle(title: string): string {
    const keys: Record<string, string> = {
      'Module': 'registry.section.module',
      'Applications': 'registry.category.applications',
      'Features': 'registry.category.features',
      'Admin Settings': 'registry.category.adminSettings',
      'User Settings': 'registry.category.userSettings',
      'Security Roles': 'registry.section.securityRoles',
      'Capabilities': 'registry.section.capabilities',
      'Events': 'registry.section.events',
    };
    return keys[title] ? this.i18n.t(keys[title]) : title;
  }

  protected fieldLabel(label: string): string {
    const keys: Record<string, string> = {
      'Manifest Version': 'registry.field.manifestVersion',
      'Key': 'registry.field.key',
      'Name': 'registry.field.name',
      'Base URL': 'registry.field.baseUrl',
      'Health': 'registry.field.health',
      'deleted': 'registry.diff.deleted',
    };
    return keys[label] ? this.i18n.t(keys[label]) : label;
  }
}

// ── Preview types ─────────────────────────────────────────────────────

export interface PreviewField {
  key: string;
  label: string;
  oldValue?: unknown;
  newValue: unknown;
  changed?: boolean;
}

export interface PreviewItem {
  action: 'create' | 'update' | 'unchanged';
  key: string;
  fields: PreviewField[];
}

export interface PreviewSection {
  title: string;
  action: 'create' | 'update' | 'unchanged';
  fields?: PreviewField[];
  items?: PreviewItem[];
}

function objectToFields(obj: Record<string, unknown> | object): PreviewField[] {
  const skip = new Set(['key', 'type']);
  const o = obj as Record<string, unknown>;
  return Object.entries(o)
    .filter(([k, v]) => !skip.has(k) && v !== undefined)
    .map(([k, v]) => ({ key: k, label: k, newValue: v }));
}

function diffObjects(old: Record<string, unknown> | object, cur: Record<string, unknown> | object): PreviewField[] {
  const skip = new Set(['key', 'type', 'description']);
  const fields: PreviewField[] = [];
  const o = old as Record<string, unknown>;
  const c = cur as Record<string, unknown>;
  const allKeys = new Set([...Object.keys(o), ...Object.keys(c)]);
  for (const k of allKeys) {
    if (skip.has(k)) continue;
    const ov = o[k];
    const nv = c[k];
    if (JSON.stringify(ov) === JSON.stringify(nv)) {
      fields.push({ key: k, label: k, oldValue: ov, newValue: nv, changed: false });
    } else {
      fields.push({ key: k, label: k, oldValue: ov, newValue: nv, changed: true });
    }
  }
  return fields;
}

function computeSectionAction(items: PreviewItem[]): 'create' | 'update' | 'unchanged' {
  if (items.every((i) => i.action === 'unchanged')) return 'unchanged';
  if (items.every((i) => i.action === 'create')) return 'create';
  return 'update';
}

function buildDiffSections(oldManifest: PortalModuleManifest | null, newManifest: PortalModuleManifest): PreviewSection[] {
  const sections: PreviewSection[] = [];

  // Root properties
  if (oldManifest) {
    const rootFields: PreviewField[] = [
      { key: 'manifestVersion', label: 'Manifest Version', oldValue: oldManifest.manifestVersion, newValue: newManifest.manifestVersion, changed: oldManifest.manifestVersion !== newManifest.manifestVersion },
      { key: 'key', label: 'Key', oldValue: oldManifest.key, newValue: newManifest.key, changed: oldManifest.key !== newManifest.key },
      { key: 'name', label: 'Name', oldValue: oldManifest.name, newValue: newManifest.name, changed: oldManifest.name !== newManifest.name },
      { key: 'baseUrl', label: 'Base URL', oldValue: oldManifest.baseUrl, newValue: newManifest.baseUrl, changed: oldManifest.baseUrl !== newManifest.baseUrl },
      { key: 'health', label: 'Health', oldValue: oldManifest.health ?? null, newValue: newManifest.health ?? null, changed: (oldManifest.health ?? null) !== (newManifest.health ?? null) },
    ];
    sections.push({ title: 'Module', fields: rootFields, action: rootFields.some((f) => f.changed) ? 'update' : 'unchanged' });
  } else {
    sections.push({
      title: 'Module', action: 'create',
      fields: [
        { key: 'manifestVersion', label: 'Manifest Version', newValue: newManifest.manifestVersion },
        { key: 'key', label: 'Key', newValue: newManifest.key },
        { key: 'name', label: 'Name', newValue: newManifest.name },
        { key: 'baseUrl', label: 'Base URL', newValue: newManifest.baseUrl },
        { key: 'health', label: 'Health', newValue: newManifest.health ?? null },
      ],
    });
  }

  // Content groups
  const groups = [
    { key: 'applications', label: 'Applications' },
    { key: 'features', label: 'Features' },
    { key: 'adminSettings', label: 'Admin Settings' },
    { key: 'userSettings', label: 'User Settings' },
  ];
  for (const g of groups) {
    const newEntries = (newManifest.content?.[g.key as keyof typeof newManifest.content] ?? []) as ManifestContentEntry[];
    const oldEntries = (oldManifest?.content?.[g.key as keyof typeof oldManifest.content] ?? []) as ManifestContentEntry[];
    if (oldEntries.length === 0 && newEntries.length === 0) continue;

    const oldMap = new Map(oldEntries.map((e) => [e.key, e]));
    const newMap = new Map(newEntries.map((e) => [e.key, e]));
    const allKeys = new Set([...oldMap.keys(), ...newMap.keys()]);
    const items: PreviewItem[] = [];
    for (const k of allKeys) {
      const ov = oldMap.get(k);
      const nv = newMap.get(k);
      if (!ov) items.push({ action: 'create', key: k, fields: objectToFields(nv!) });
      else if (!nv) items.push({ action: 'update', key: k, fields: [{ key: '_deleted', label: 'deleted', oldValue: ov.name, newValue: null, changed: true }] });
      else {
        const diffs = diffObjects(ov, nv);
        items.push({ action: diffs.some((f) => f.changed) ? 'update' : 'unchanged', key: k, fields: diffs });
      }
    }
    sections.push({ title: g.label, items, action: computeSectionAction(items) });
  }

  // Security roles
  const newRoles = newManifest.security?.roles ?? [];
  const oldRoles = oldManifest?.security?.roles ?? [];
  if (oldRoles.length > 0 || newRoles.length > 0) {
    const oldRoleMap = new Map(oldRoles.map((r) => [r.key, r]));
    const newRoleMap = new Map(newRoles.map((r) => [r.key, r]));
    const allRoleKeys = new Set([...oldRoleMap.keys(), ...newRoleMap.keys()]);
    const items: PreviewItem[] = [];
    for (const k of allRoleKeys) {
      const ov = oldRoleMap.get(k);
      const nv = newRoleMap.get(k);
      if (!ov) items.push({ action: 'create', key: k, fields: objectToFields(nv!) });
      else if (!nv) items.push({ action: 'update', key: k, fields: [{ key: '_deleted', label: 'deleted', oldValue: ov.name, newValue: null, changed: true }] });
      else {
        const diffs = diffObjects(ov, nv);
        items.push({ action: diffs.some((f) => f.changed) ? 'update' : 'unchanged', key: k, fields: diffs });
      }
    }
    sections.push({ title: 'Security Roles', items, action: computeSectionAction(items) });
  }

  return sections;
}
