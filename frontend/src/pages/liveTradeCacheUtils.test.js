import assert from "node:assert/strict";
import test from "node:test";

import {
  composeTradeCacheRealizedPnl,
  mergeTradeCachePnlFields,
} from "./liveTradeCacheUtils.js";

test("composeTradeCacheRealizedPnl adds DTM partial PnL to broker final leg", () => {
  assert.equal(
    composeTradeCacheRealizedPnl({
      pnl: 72.69,
      finalLegPnl: 72.69,
      dtmRealizedPnl: 73.56,
      dtmPartialContractsClosed: 3,
    }),
    146.25
  );
});

test("mergeTradeCachePnlFields keeps broker final leg but preserves local DTM realized total", () => {
  const merged = mergeTradeCachePnlFields(
    {
      cacheSource: "topstep-enriched",
      pnl: 72.69,
      finalLegPnl: 72.69,
      contracts: 3,
    },
    {
      cacheSource: "local-decision",
      pnl: 146.25,
      finalLegPnl: 72.69,
      dtmRealizedPnl: 73.56,
      dtmPartialContractsClosed: 3,
      contracts: 6,
    }
  );

  assert.equal(merged.pnl, 146.25);
  assert.equal(merged.finalLegPnl, 72.69);
  assert.equal(merged.dtmRealizedPnl, 73.56);
  assert.equal(merged.dtmPartialContractsClosed, 3);
});

test("mergeTradeCachePnlFields keeps plain broker PnL when no DTM partial exists", () => {
  const merged = mergeTradeCachePnlFields(
    { cacheSource: "topstep-enriched", pnl: -171.48, contracts: 4 },
    { cacheSource: "local-decision", pnl: -169.2, contracts: 4 }
  );

  assert.equal(merged.pnl, -171.48);
  assert.equal(merged.finalLegPnl, undefined);
  assert.equal(merged.dtmRealizedPnl, undefined);
});
