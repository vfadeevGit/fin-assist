import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseEndDate } from './compact-date.js';
test('compact and dotted dates are strict calendar dates', () => {
  assert.deepEqual(parseEndDate('311226'), {day:31, month:11, year:2026});
  assert.deepEqual(parseEndDate(' 01.02.2027 '), {day:1, month:1, year:2027});
  assert.deepEqual(parseEndDate('290228'), {day:29, month:1, year:2028});
  for (const text of ['290227','310427','000127','011327','31122026','0102','01 0227','01.02.0000','']) {
    assert.equal(parseEndDate(text), undefined, text);
  }
});

test('two-digit years always mean 2000 through 2099', () => {
  assert.deepEqual(parseEndDate('010100'), {day:1, month:0, year:2000});
  assert.deepEqual(parseEndDate('311299'), {day:31, month:11, year:2099});
  assert.deepEqual(parseEndDate('290200'), {day:29, month:1, year:2000});
  assert.deepEqual(parseEndDate('010227'), {day:1, month:1, year:2027});
  assert.deepEqual(parseEndDate('01.02.1999'), {day:1, month:1, year:1999});
});
