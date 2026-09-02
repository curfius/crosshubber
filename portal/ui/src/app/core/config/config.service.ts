import { Injectable } from '@angular/core';
import { Subject } from 'rxjs';
import type { PortalConfig } from '../models';

@Injectable({ providedIn: 'root' })
export class ConfigService {
  private readonly changedSubject = new Subject<void>();

  /** Emits when config data should be re-fetched (e.g. after nav-tree edits). */
  readonly changed$ = this.changedSubject.asObservable();

  notifyChanged(): void {
    this.changedSubject.next();
  }

  async load(): Promise<PortalConfig> {
    const res = await fetch('/api/config');
    if (!res.ok) throw new Error('unauthorized');
    return (await res.json()) as PortalConfig;
  }
}
