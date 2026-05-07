export const EASTERN_TIME_LABEL = "ET";

export function formatEstTime(value) {
  if (value == null || value === "") return "--";
  const raw = String(value).trim();
  if (!raw || raw === "--" || raw === "—" || raw.toLowerCase() === "open" || raw.toLowerCase() === "running") {
    return raw;
  }

  const clean = raw
    .replace(/\s*(ET|EST|EDT)(?:\s*\([^)]*\))?$/i, "")
    .replace(/\s*\(America\/New_York[^)]*\)$/i, "")
    .trim();

  const isoDateOnly = clean.match(/^(\d{4})-(\d{2})-(\d{2})$/);
  if (isoDateOnly) {
    const [, year, month, day] = isoDateOnly;
    return `${month}/${day}/${year}`;
  }

  const usDateOnly = clean.match(/^(\d{1,2})\/(\d{1,2})\/(\d{4})$/);
  if (usDateOnly) {
    const [, month, day, year] = usDateOnly;
    return `${pad2(month)}/${pad2(day)}/${year}`;
  }

  if (!hasClock(clean)) {
    return clean;
  }

  const offsetDate = parseOffsetDate(clean);
  if (offsetDate) {
    return formatDateParts(offsetDate);
  }

  const fullMatch = clean.match(/^(\d{4})-(\d{2})-(\d{2})[ T](\d{1,2}):(\d{2})(?::\d{2}(?:\.\d+)?)?(?:\s*(AM|PM))?/i);
  if (fullMatch) {
    const [, year, month, day, hour, minute, period] = fullMatch;
    return `${month}/${day}/${year} ${formatClock(hour, minute, period)} ${easternAbbreviation(year, month, day)}`;
  }

  const usMatch = clean.match(/^(\d{1,2})\/(\d{1,2})\/(\d{4})[ T](\d{1,2}):(\d{2})(?::\d{2})?(?:\s*(AM|PM))?/i);
  if (usMatch) {
    const [, month, day, year, hour, minute, period] = usMatch;
    return `${pad2(month)}/${pad2(day)}/${year} ${formatClock(hour, minute, period)} ${easternAbbreviation(year, month, day)}`;
  }

  const timeOnly = clean.match(/^(\d{1,2}):(\d{2})(?::\d{2})?(?:\s*(AM|PM))?/i);
  if (timeOnly) {
    const [, hour, minute, period] = timeOnly;
    return `${formatClock(hour, minute, period)} ${currentEasternAbbreviation()}`;
  }

  return `${clean} ${EASTERN_TIME_LABEL}`;
}

export function formatEstRange(start, end) {
  const left = formatEstTime(start);
  const right = formatEstTime(end);
  return `${left} -> ${right}`;
}

function hasClock(value) {
  return /(?:T|\s)\d{1,2}:\d{2}|^\d{1,2}:\d{2}/.test(value);
}

function parseOffsetDate(value) {
  if (!/(Z|[+-]\d{2}:?\d{2})$/i.test(value)) return null;
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? null : parsed;
}

function formatDateParts(date) {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: "America/New_York",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "numeric",
    minute: "2-digit",
    hour12: true,
    timeZoneName: "short",
  }).formatToParts(date);
  const lookup = Object.fromEntries(parts.map((part) => [part.type, part.value]));
  return `${lookup.month}/${lookup.day}/${lookup.year} ${lookup.hour}:${lookup.minute} ${lookup.dayPeriod} ${lookup.timeZoneName || EASTERN_TIME_LABEL}`;
}

function formatClock(hourValue, minuteValue, periodValue = "") {
  const hour24 = Number(hourValue);
  const minute = pad2(minuteValue);
  const suppliedPeriod = String(periodValue || "").toUpperCase();
  if (suppliedPeriod === "AM" || suppliedPeriod === "PM") {
    const hour12 = hour24 % 12 || 12;
    return `${hour12}:${minute} ${suppliedPeriod}`;
  }
  const period = hour24 >= 12 ? "PM" : "AM";
  const hour12 = hour24 % 12 || 12;
  return `${hour12}:${minute} ${period}`;
}

function pad2(value) {
  return String(value).padStart(2, "0");
}

function easternAbbreviation(year, month, day) {
  const date = new Date(Date.UTC(Number(year), Number(month) - 1, Number(day), 12, 0, 0));
  if (Number.isNaN(date.getTime())) return EASTERN_TIME_LABEL;
  return easternAbbreviationForDate(date);
}

function currentEasternAbbreviation() {
  return easternAbbreviationForDate(new Date());
}

function easternAbbreviationForDate(date) {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: "America/New_York",
    timeZoneName: "short",
  }).formatToParts(date);
  return parts.find((part) => part.type === "timeZoneName")?.value || EASTERN_TIME_LABEL;
}
