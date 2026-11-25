import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseDateInput } from './date-input.js';

test('all agreed input formats represent the same date', () => {
  for (const value of ['251126', '25112026', '25.11.26', '25.11.2026']) {
    assert.deepEqual(parseDateInput(value), {day:25, month:10, year:2026}, value);
  }
});
test('compact and dotted dates are strict calendar dates', () => {
  assert.deepEqual(parseDateInput('311226'), {day:31, month:11, year:2026});
  assert.deepEqual(parseDateInput(' 01.02.2027 '), {day:1, month:1, year:2027});
  assert.deepEqual(parseDateInput('290228'), {day:29, month:1, year:2028});
  for (const text of ['290227','310427','000127','011327','0102','01 0227','01.02.0000','']) {
    assert.equal(parseDateInput(text), undefined, text);
  }
});

test('two-digit years always mean 2000 through 2099', () => {
  assert.deepEqual(parseDateInput('010100'), {day:1, month:0, year:2000});
  assert.deepEqual(parseDateInput('311299'), {day:31, month:11, year:2099});
  assert.deepEqual(parseDateInput('290200'), {day:29, month:1, year:2000});
  assert.deepEqual(parseDateInput('010227'), {day:1, month:1, year:2027});
  assert.deepEqual(parseDateInput('01.02.1999'), {day:1, month:1, year:1999});
});
