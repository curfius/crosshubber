// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';

// Browser twin of SubmitDataValidatorContractTest (portal/server .../tasks/): both suites read
// the SAME fixture and must agree on accept/reject for every case (plan checkpoints 3/4).
// The canonical fixture lives in the server test resources so the two engines can never drift.
import contractFixture from '../../../../../server/src/test/resources/msgcenter/schema-contract.json';

import { fieldError, missingRequired } from './task-form-model';
import type { TaskFieldSpec } from '../../portal-core/modules/msg-center/task-panel.types';

interface Fixture {
  fields: TaskFieldSpec[];
  cases: Array<{ field: string; value: unknown; verdict: 'accept' | 'reject' }>;
  requiredCase: { field: string; payload: Record<string, unknown> };
}

const fixture = contractFixture as Fixture;

function specFor(name: string): TaskFieldSpec {
  const spec = fixture.fields.find((f) => f.name === name);
  if (!spec) throw new Error('fixture field missing: ' + name);
  return spec;
}

describe('schema-equivalence contract (browser side)', () => {
  it('agrees with the server on every fixture case', () => {
    for (const c of fixture.cases) {
      // the form coerces '' → null before submission (inputValueFor), so the browser contract
      // is evaluated over POST-coerced values; the server would reject a raw '' on minLength
      const coerced = c.value === '' ? null : c.value;
      const error = fieldError(specFor(c.field), coerced);
      const accepted = error === null;
      const expected = c.verdict === 'accept';
      expect(
        accepted,
        `divergence on ${c.field}=${String(c.value)} → error: ${error ?? 'none'}`,
      ).toBe(expected);
    }
  });

  it('flags the required field when it is empty', () => {
    const required = fixture.requiredCase.field;
    expect(missingRequired(fixture.fields, fixture.requiredCase.payload)).toContain(required);
  });

  it('fixture case count drift check', () => {
    expect(fixture.cases.length).toBe(27);
  });
});
