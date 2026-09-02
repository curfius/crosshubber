import { TestBed } from '@angular/core/testing';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { ThemeService, DEFAULT_THEME } from './theme.service';
import { UserSettingsService } from '../settings/user-settings.service';

const STORAGE_KEY = 'portal-theme';

function theme(): string | null {
  return document.documentElement.getAttribute('data-theme');
}

describe('ThemeService persistence precedence', () => {
  let updateMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    localStorage.clear();
    document.documentElement.removeAttribute('data-theme');
    updateMock = vi.fn(async () => ({}));
    TestBed.configureTestingModule({
      providers: [{ provide: UserSettingsService, useValue: { update: updateMock } }],
    });
  });

  it('falls back to localStorage, then the default theme', () => {
    localStorage.setItem(STORAGE_KEY, 'nord');
    const svc = TestBed.inject(ThemeService);
    expect(svc.theme()).toBe('nord');
    expect(theme()).toBe('nord');

    TestBed.resetTestingModule();
    localStorage.removeItem(STORAGE_KEY);
    const fresh = TestBed.inject(ThemeService);
    expect(fresh.theme()).toBe(DEFAULT_THEME);
  });

  it('setTheme applies immediately, caches locally, and persists to the DB (fire-and-forget)', () => {
    const svc = TestBed.inject(ThemeService);
    svc.setTheme('dracula');
    expect(svc.theme()).toBe('dracula');
    expect(theme()).toBe('dracula');
    expect(localStorage.getItem(STORAGE_KEY)).toBe('dracula');
    expect(updateMock).toHaveBeenCalledWith('general', { theme: 'dracula' });
  });

  it('ignores unknown theme values entirely', () => {
    const svc = TestBed.inject(ThemeService);
    svc.setTheme('not-a-theme');
    expect(updateMock).not.toHaveBeenCalled();
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  it('initFromPreferences applies a valid DB preference without persisting it back', () => {
    localStorage.setItem(STORAGE_KEY, 'light');
    const svc = TestBed.inject(ThemeService);
    svc.initFromPreferences({ theme: 'nord' });
    expect(svc.theme()).toBe('nord');
    expect(theme()).toBe('nord');
    expect(localStorage.getItem(STORAGE_KEY)).toBe('nord');
    expect(updateMock).not.toHaveBeenCalled();
  });

  it('initFromPreferences ignores invalid DB values (localStorage wins)', () => {
    localStorage.setItem(STORAGE_KEY, 'light');
    const svc = TestBed.inject(ThemeService);
    svc.initFromPreferences({ theme: 'bogus' });
    expect(svc.theme()).toBe('light');
    expect(theme()).toBe('light');
  });

  it('initFromPreferences is a no-op when the DB preference matches the current theme', () => {
    localStorage.setItem(STORAGE_KEY, 'light');
    const svc = TestBed.inject(ThemeService);
    svc.initFromPreferences({ theme: 'light' });
    expect(theme()).toBe('light');
    expect(updateMock).not.toHaveBeenCalled();
  });
});
