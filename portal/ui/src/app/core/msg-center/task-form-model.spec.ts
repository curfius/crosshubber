// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';

// Task-form model helpers are framework-free (the component's import graph pulls CDK private
// style loaders that need the JIT compiler, unavailable in vitest) — the model is what carries
// the validation semantics worth pinning; the component is a thin shell around it.
const { fieldError, missingRequired, inputValueFor } = await import('./task-form-model');

const daysField = {
  name: 'days',
  required: true,
  schema: { type: 'integer', maximum: 30 },
};

const commentField = {
  name: 'comment',
  schema: { type: 'string', maxLength: 200 },
};

describe('task-form-model', () => {
  it('missingRequired lists unfilled required fields', () => {
    expect(missingRequired([daysField, commentField], {})).toEqual(['days']);
    expect(missingRequired([daysField], { days: null })).toEqual(['days']);
    expect(missingRequired([daysField, commentField], { days: 0, comment: '' })).toEqual([]);
  });

  it('fieldError enforces bounds and integer-ness', () => {
    expect(fieldError(daysField, 44)).toBe('≤ 30');
    expect(fieldError(daysField, 2.5)).toBe('integer required');
    expect(fieldError(daysField, 3)).toBeNull();
  });

  it('fieldError checks string caps and patterns', () => {
    expect(fieldError(commentField, 'x'.repeat(201))).toBe('≤ 200 chars');
    expect(fieldError(commentField, 'fine')).toBeNull();
    const patternField = { name: 'code', schema: { type: 'string', pattern: '^[a-z]{3}$' } };
    expect(fieldError(patternField, 'ABC')).toBe('pattern mismatch');
    expect(fieldError(patternField, 'abc')).toBeNull();
  });

  it('fieldError passes through empty values (required handled at submit)', () => {
    expect(fieldError(daysField, undefined)).toBeNull();
    expect(fieldError(daysField, '')).toBeNull();
  });

  it('inputValueFor coerces checkbox/number/text targets', () => {
    const checkbox = { type: 'checkbox', checked: true };
    expect(inputValueFor(checkbox)).toEqual({ ok: true, value: true });

    const number = { type: 'number', value: '3.5' };
    expect(inputValueFor(number)).toEqual({ ok: true, value: 3.5 });

    const emptyNumber = { type: 'number', value: '' };
    expect(inputValueFor(emptyNumber)).toEqual({ ok: true, value: null });

    const text = { type: 'text', value: 'hello' };
    expect(inputValueFor(text)).toEqual({ ok: true, value: 'hello' });

    const textarea = { value: '' };
    expect(inputValueFor(textarea)).toEqual({ ok: true, value: null });
  });
});
