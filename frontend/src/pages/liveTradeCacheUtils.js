function finiteNumberOrNull(value) {
  if (value == null || value === "") return null;
  const numeric = Number(value);
  return Number.isFinite(numeric) ? numeric : null;
}

function roundCurrency(value) {
  const numeric = finiteNumberOrNull(value);
  if (numeric == null) return 0;
  return Math.round((numeric + Number.EPSILON) * 100) / 100;
}

function firstFinite(...values) {
  for (const value of values) {
    const numeric = finiteNumberOrNull(value);
    if (numeric != null) return numeric;
  }
  return null;
}

export function composeTradeCacheRealizedPnl(row, fallbackPnl = 0) {
  const finalLegPnl = finiteNumberOrNull(row?.finalLegPnl);
  const dtmRealizedPnl = finiteNumberOrNull(row?.dtmRealizedPnl) || 0;
  const partialContracts = finiteNumberOrNull(row?.dtmPartialContractsClosed) || 0;
  const hasDtmPartial = partialContracts > 0 || Math.abs(dtmRealizedPnl) > 0;
  if (hasDtmPartial && finalLegPnl != null) {
    return roundCurrency(finalLegPnl + dtmRealizedPnl);
  }
  return roundCurrency(firstFinite(row?.pnl, fallbackPnl) || 0);
}

export function mergeTradeCachePnlFields(row = {}, cachedRow = {}) {
  const dtmRealizedPnl = firstFinite(row.dtmRealizedPnl, cachedRow.dtmRealizedPnl);
  const partialContracts = firstFinite(row.dtmPartialContractsClosed, cachedRow.dtmPartialContractsClosed);
  const hasDtmPartial = (partialContracts || 0) > 0 || Math.abs(dtmRealizedPnl || 0) > 0;
  if (!hasDtmPartial) {
    return { pnl: roundCurrency(firstFinite(row.pnl, cachedRow.pnl) || 0) };
  }

  const finalLegPnl = firstFinite(row.finalLegPnl, cachedRow.finalLegPnl, row.pnl, cachedRow.pnl);
  const fields = {
    pnl: composeTradeCacheRealizedPnl({
      pnl: firstFinite(row.pnl, cachedRow.pnl) || 0,
      finalLegPnl,
      dtmRealizedPnl: dtmRealizedPnl || 0,
      dtmPartialContractsClosed: partialContracts || 0,
    }),
  };
  if (finalLegPnl != null) fields.finalLegPnl = roundCurrency(finalLegPnl);
  if (dtmRealizedPnl != null) fields.dtmRealizedPnl = roundCurrency(dtmRealizedPnl);
  if (partialContracts != null) fields.dtmPartialContractsClosed = partialContracts;
  return fields;
}
