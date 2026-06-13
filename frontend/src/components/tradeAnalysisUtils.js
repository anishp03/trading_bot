export function tradeAnalysisPriceDomain({ series = [], annotations = [], tradePrices = [] } = {}) {
  const values = [];
  const candleValues = [];
  (Array.isArray(series) ? series : []).forEach((candle) => {
    ["high", "low", "vwap", "ema20", "ema50"].forEach((key) => {
      const value = Number(candle?.[key]);
      if (!Number.isFinite(value) || value <= 0) return;
      values.push(value);
      candleValues.push(value);
    });
  });
  (Array.isArray(tradePrices) ? tradePrices : []).forEach((value) => {
    const number = Number(value);
    if (Number.isFinite(number) && number > 0) values.push(number);
  });

  const validCandleValues = candleValues.filter(Number.isFinite);
  const candleMin = validCandleValues.length ? Math.min(...validCandleValues) : Math.min(...values.filter(Number.isFinite));
  const candleMax = validCandleValues.length ? Math.max(...validCandleValues) : Math.max(...values.filter(Number.isFinite));
  const candleRange = Math.max(candleMax - candleMin, 1e-9);
  const referencePrice = positiveMedian(validCandleValues.length ? validCandleValues : values);
  const relevantPadding = Math.max(candleRange * 0.8, referencePrice * 0.003, adaptiveMinimumPadding(referencePrice, candleRange) * 4);
  const relevantFloor = candleMin - relevantPadding;
  const relevantCeiling = candleMax + relevantPadding;

  (Array.isArray(annotations) ? annotations : []).forEach((mark) => {
    ["price", "high", "low", "gapHigh", "gapLow"].forEach((key) => {
      const value = Number(mark?.[key]);
      if (value > 0 && value >= relevantFloor && value <= relevantCeiling) values.push(value);
    });
  });

  const finiteValues = values.filter((value) => Number.isFinite(value) && value > 0);
  if (!finiteValues.length) {
    return { min: 0, max: 1, range: 1 };
  }

  const minValue = Math.min(...finiteValues);
  const maxValue = Math.max(...finiteValues);
  const cleanTradePrices = (Array.isArray(tradePrices) ? tradePrices : [])
    .map(Number)
    .filter((value) => Number.isFinite(value) && value > 0);
  const tradeFloor = cleanTradePrices.length ? Math.min(...cleanTradePrices) : minValue;
  const tradeCeiling = cleanTradePrices.length ? Math.max(...cleanTradePrices) : maxValue;
  const rawRange = Math.max(maxValue - minValue, 1e-9);
  const padding = Math.max(
    rawRange * 0.22,
    Math.max(tradeCeiling - tradeFloor, 0) * 0.55,
    adaptiveMinimumPadding(referencePrice || positiveMedian(finiteValues), rawRange)
  );
  const min = minValue - padding;
  const max = maxValue + padding;
  return { min, max, range: Math.max(max - min, 1) };
}

function adaptiveMinimumPadding(referencePrice, currentRange) {
  const reference = Number(referencePrice);
  const pricePadding = Number.isFinite(reference) && reference > 0 ? reference * 0.0015 : 0.1;
  const rangePadding = Number.isFinite(currentRange) && currentRange > 0 ? currentRange * 0.28 : 0;
  return Math.max(Math.min(pricePadding, 3.5), rangePadding, 0.08);
}

function positiveMedian(values) {
  const sorted = (Array.isArray(values) ? values : [])
    .map(Number)
    .filter((value) => Number.isFinite(value) && value > 0)
    .sort((a, b) => a - b);
  if (!sorted.length) return 0;
  return sorted[Math.floor(sorted.length / 2)];
}
