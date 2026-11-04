/** Strict, date-only parsing for the Addendum end date. Month is zero-based for Vaadin. */
export function parseEndDate(input) {
  const text = input.trim();
  const match = /^(\d{2})(\d{2})(\d{2})$/.exec(text) || /^(\d{2})\.(\d{2})\.(\d{4})$/.exec(text);
  if (!match) return undefined;
  const [, d, m, y] = match;
  const day = Number(d), month = Number(m), year = y.length === 2 ? 2000 + Number(y) : Number(y);
  if (year < 1 || month < 1 || month > 12 || day < 1) return undefined;
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  return day <= days[month - 1] ? {day, month: month - 1, year} : undefined;
}

export function configureEndDate(picker) {
  picker.i18n = {...picker.i18n, parseDate: parseEndDate};
}
if (typeof window !== 'undefined') window.finassistConfigureEndDate = configureEndDate;
