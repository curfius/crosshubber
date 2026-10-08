/** Task spec shapes shared by the task panel and the framework-free form-model helpers. */

export type TaskI18nMap = Record<string, string>;

export interface TaskSpec {
  kind?: string;
  completion?: string;
  completionEvent?: string;
  expiresAt?: string;
  claim?: { enabled?: boolean; mode?: string };
  fields?: TaskFieldSpec[];
  sections?: TaskSectionSpec[];
  template?: { key: string; version: number };
}

export interface TaskFieldSpec {
  name: string;
  required?: boolean;
  label?: TaskI18nMap;
  multiline?: boolean;
  schema: {
    type: string;
    enum?: unknown[];
    format?: string;
    pattern?: string;
    minLength?: number;
    maxLength?: number;
    minimum?: number;
    maximum?: number;
  };
}

export interface TaskSectionSpec {
  title?: TaskI18nMap;
  description?: TaskI18nMap;
  fields?: string[];
}
