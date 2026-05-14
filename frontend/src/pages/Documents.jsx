const STRATEGY_IMAGE_BASE = "/documents/futures-strategies";

const pipelineChecks = [
  "Live futures starts only after a Live Strategy snapshot exists; the snapshot is copied/promoted from backtest settings and remains read-only during live trading.",
  "The live cycle runs every 5 seconds, builds 1-minute candles from ProjectX realtime plus warmup bars, and supplies previous-day, 15-minute, and 1-hour context where required.",
  "Every normal strategy signal is generated from a closed signal candle and executes on the next bar open before 15:55 ET.",
  "Entries pass portfolio risk, funded-unit, max-position, per-strategy daily cap, duplicate-symbol, broker-exposure, and Topstep account checks before submission.",
  "TopstepX entry submission uses the shared futures order adapter with entry, stop, and target prices. If the broker rejects brackets, the response is logged and surfaced.",
  "Tracked exits are checked before new entries. Stop, target, managed stop, adaptive loss cut, time stop, and session-flat exits route through the TopstepX close adapter.",
  "At the end of day the runner cancels resting entry orders, flattens positions, and verifies flat state after the regular-session close guard.",
];

const exitRequirements = [
  "Long exits sell to close; short exits buy to cover.",
  "If stop and target touch in the same candle, the engine assumes the stop fills first.",
  "Broker brackets protect stop and target; the live engine also detects candle-based exits and syncs already-flat broker states.",
  "Software max-hold and managed-stop state is rebuilt from the stored live entry plus current candle context each cycle.",
  "All live decisions, broker submissions, close attempts, and risk blocks are written into FuturesLiveSignalDecisions, FuturesLiveOrderLedger, and audit/risk logs.",
];

const strategyDocs = [
  {
    code: "ORB",
    title: "Opening Range Breakout",
    image: "orb-breakout.png",
    paths: ["Long closes above the 9:30-9:45 range high.", "Short closes below the range low after the short confirmation time."],
    requirements: ["Opening range has enough bars.", "Volume is at least 85% of opening-range average.", "Body closes in the accepted close-location zone.", "Optional higher-timeframe guard passes."],
    exit: "Range stop or compressed swing stop, target from reward/risk, plus common exit stack.",
  },
  {
    code: "ORB2",
    title: "Opening Range Retest",
    image: "orb-retest.png",
    paths: ["Breakout first arms the long/short side.", "Retest candle touches the range edge and closes back through it."],
    requirements: ["Retest window and skip windows pass.", "Volume is at least 65% of opening-range average.", "Risk is under ORB retest max risk.", "Short side can be day-of-week filtered."],
    exit: "Swing stop beyond the retest, minimum reward/risk target, opening momentum max hold.",
  },
  {
    code: "LORB",
    title: "Late ORB Continuation",
    image: "late-orb-continuation.png",
    paths: ["Late long continuation above ORB high.", "Late short continuation below ORB low with VWAP/EMA bearish filters."],
    requirements: ["Late ORB time window is active.", "Volume ratio meets the late threshold.", "Risk fits compressed ORB risk.", "Shorts require RSI and EMA/VWAP alignment."],
    exit: "Compressed swing stop, configured reward/risk, late ORB max hold.",
  },
  {
    code: "OMOM",
    title: "Compressed Opening Momentum",
    image: "opening-momentum.png",
    paths: ["Long opening-range breakout.", "Short opening-range breakdown.", "Optional multiple entries by side and time bucket."],
    requirements: ["Configurable opening range is complete.", "Side-specific time windows and DOW masks pass.", "Volume ratio and close location pass.", "Initial risk is under opening momentum max risk."],
    exit: "Recent swing stop, opening momentum reward/risk target, opening momentum max hold.",
  },
  {
    code: "VWAP",
    title: "VWAP Trend Pullback",
    image: "vwap-pullback.png",
    paths: ["Long pullback holds EMA20 above VWAP and breaks prior high.", "Short pullback fails EMA20 below VWAP and breaks prior low."],
    requirements: ["VWAP time window and skip windows pass.", "Price stays within VWAP max distance.", "Volume and EMA20 slope pass.", "EMA stack confirms trend direction."],
    exit: "Swing/EMA stop, minimum reward/risk target, 15-bar max hold.",
  },
  {
    code: "VRCL",
    title: "VWAP Reclaim Continuation",
    image: "vwap-reclaim.png",
    paths: ["Long reclaims VWAP after a touch.", "Short loses VWAP after a touch."],
    requirements: ["10:00-15:10 ET scan window.", "Bucket spacing prevents repeat entries.", "Volume, VWAP distance, EMA stack, and slope pass.", "Signal candle breaks the prior candle in the trade direction."],
    exit: "VWAP/swing stop, configured reclaim reward/risk, 18-bar max hold.",
  },
  {
    code: "MSCALP",
    title: "Micro Trend Scalp",
    image: "micro-scalp.png",
    paths: ["Long micro pullback above VWAP.", "Short micro pullback below VWAP."],
    requirements: ["Micro scalp time and side windows pass.", "Volume, body percent, EMA stack, and trend slope pass.", "Prior bar touches EMA9/EMA20 zone.", "Risk is under micro scalp max risk."],
    exit: "Tight EMA/swing stop, micro scalp reward/risk, micro scalp max hold.",
  },
  {
    code: "TLAD",
    title: "Trend Ladder Pullback",
    image: "trend-ladder.png",
    paths: ["Long ladder pullback in rising VWAP/EMA trend.", "Short ladder pullback in falling VWAP/EMA trend."],
    requirements: ["Trend ladder window and bucket pass.", "Volume, VWAP distance, RSI, EMA stack, and trend slope pass.", "Higher timeframe guard passes when enabled.", "Pullback touches EMA20 area."],
    exit: "EMA/swing stop, trend ladder reward/risk, trend ladder max hold.",
  },
  {
    code: "RCB",
    title: "Range Compression Breakout",
    image: "range-compression-breakout.png",
    paths: ["Long breaks above a compressed box.", "Short breaks below a compressed box."],
    requirements: ["Compression box range is small versus ATR.", "Box width fits max risk.", "Volume, body, VWAP/EMA distance, RSI, and trend slope pass.", "Higher-timeframe breakout guard passes."],
    exit: "Stop beyond opposite side of box, configured reward/risk, compression max hold.",
  },
  {
    code: "VPB",
    title: "Prior Value Area Reclaim",
    image: "value-area-reclaim.png",
    paths: ["Long reclaims prior value area high.", "Short rejects prior value area low."],
    requirements: ["Prior session volume profile is valid.", "Value area window and bucket pass.", "Volume, VWAP, EMA, close location, and trend slope pass.", "Higher-timeframe guard passes when enabled."],
    exit: "Stop beyond value-area edge or swing, value-area reward/risk, value-area max hold.",
  },
  {
    code: "KELT",
    title: "Keltner ATR Breakout Scalp",
    image: "keltner-scalp.png",
    paths: ["Long closes above upper Keltner band.", "Short closes below lower Keltner band."],
    requirements: ["Keltner scan window and bucket pass.", "Band width, volume, body, RSI, VWAP, EMA stack, and slope pass.", "Momentum confirms with prior band break or current range break.", "Higher-timeframe guard passes."],
    exit: "Compressed swing stop, Keltner reward/risk, Keltner max hold.",
  },
  {
    code: "KREV",
    title: "Keltner Band Reclaim Reversion",
    image: "keltner-reversion.png",
    paths: ["Long reclaims the lower band after a downside stretch.", "Short reclaims below the upper band after an upside stretch."],
    requirements: ["Trend slope is flat enough.", "Band width and VWAP distance pass.", "Volume, body, RSI, close location, and higher-timeframe context pass.", "Signal candle reverses through the prior candle."],
    exit: "Compressed stop, target toward EMA20, Keltner max hold.",
  },
  {
    code: "MRVWAP",
    title: "VWAP Mean Reversion",
    image: "vwap-mean-reversion.png",
    paths: ["Long reversal after oversold stretch below VWAP.", "Short reversal after overbought stretch above VWAP."],
    requirements: ["11:30-15:15 ET window.", "Distance from VWAP exceeds threshold.", "RSI extreme confirms stretch.", "Signal closes through prior high/low with accepted close location."],
    exit: "Stop past current/prior candle, target toward VWAP or minimum reward/risk.",
  },
  {
    code: "AFT",
    title: "Afternoon Continuation",
    image: "afternoon-continuation.png",
    paths: ["Long breaks afternoon channel high.", "Short breaks afternoon channel low."],
    requirements: ["Afternoon window, side windows, skips, and DOW masks pass.", "Volume, VWAP, EMA stack, close location, and trend slope pass.", "Higher-timeframe breakout/bearish guard passes."],
    exit: "Compressed swing stop, afternoon reward/risk, afternoon max hold.",
  },
  {
    code: "IPB",
    title: "Opening Impulse Pullback",
    image: "opening-impulse-pullback.png",
    paths: ["Long pullback after strong bullish first-half-hour impulse.", "Short pullback after strong bearish first-half-hour impulse."],
    requirements: ["Opening impulse is large enough.", "Strong opening context and impulse window pass.", "Volume, VWAP, EMA stack, slope, close location, and higher-timeframe guard pass.", "Requires market intraday max trades above one."],
    exit: "Compressed stop, impulse reward/risk, 25-bar max hold.",
  },
  {
    code: "MIM",
    title: "Market Intraday Momentum",
    image: "market-intraday-momentum.png",
    paths: ["Late long when first-half-hour and late move are both bullish.", "Late short when both are bearish."],
    requirements: ["15:25-15:35 ET signal window.", "Opening and late move thresholds pass.", "VWAP, EMA, close location, volume, and higher-timeframe guard pass."],
    exit: "Compressed stop, configured market momentum reward/risk, 35-bar max hold.",
  },
  {
    code: "SWEEP",
    title: "Prior-Day Liquidity Sweep",
    image: "prior-day-liquidity-sweep.png",
    paths: ["Late long sweeps prior-day low and closes back above it.", "Late short sweeps prior-day high and closes back below it."],
    requirements: ["Previous-day bars exist.", "13:00-14:30 ET sweep window.", "Late reclaim ticks and close-location thresholds pass.", "Higher-timeframe guard passes for longs; shorts reject bullish higher-timeframe context."],
    exit: "Stop beyond sweep wick, target from minimum reward/risk.",
  },
  {
    code: "SWEEP2",
    title: "Confirmed Prior-Day Sweep",
    image: "confirmed-prior-day-sweep.png",
    paths: ["Early long sweep of prior-day low followed by confirmation candle."],
    requirements: ["Early sweep and second-chance toggles enabled.", "Reclaim ticks, close location, and body percent pass.", "Confirmation candle closes above sweep high and prior low buffer.", "Risk is under sweep max risk."],
    exit: "Stop below sweep/confirmation low, target from minimum reward/risk.",
  },
  {
    code: "PDB",
    title: "Prior-Day Breakout Retest",
    image: "prior-day-breakout-retest.png",
    paths: ["Long retest after accepted prior-day high break.", "Short retest after accepted prior-day low break."],
    requirements: ["Previous-day high/low exists.", "Break exceeds minimum ticks before retest.", "Retest window, bucket, skips, volume, VWAP, EMA, close location, and higher-timeframe guard pass."],
    exit: "Retest/swing stop, prior-day breakout reward/risk, 35-bar max hold.",
  },
  {
    code: "FVG",
    title: "Fair Value Gap Reclaim",
    image: "fair-value-gap-reclaim.png",
    paths: ["Bullish three-candle FVG retest and reclaim.", "Bearish three-candle FVG retest and reclaim."],
    requirements: ["Gap width exceeds minimum ticks.", "FVG time window, skip windows, DOW masks, and volume pass.", "Retest happens inside max retest bars.", "Risk is at least minimum FVG risk and under max FVG risk."],
    exit: "Gap/swing stop, FVG reward/risk, FVG max hold.",
  },
  {
    code: "CMOM",
    title: "Close Momentum",
    image: "close-momentum.png",
    paths: ["Late long continuation near session high after move from RTH open.", "Late short continuation near session low after move from RTH open."],
    requirements: ["14:30-15:25 ET window and side windows pass.", "Move from RTH open, volume, VWAP, EMA stack, slope, close location, and higher-timeframe guard pass."],
    exit: "EMA/swing stop, close momentum reward/risk, 40-bar max hold.",
  },
  {
    code: "SHDW",
    title: "Mini-Confirmed Micro Shadow",
    image: "micro-shadow.png",
    paths: ["ES source signal shadows MES.", "NQ source signal shadows MNQ."],
    requirements: ["Target micro shadow toggle and side permission enabled.", "Source strategy is one of the high-quality source codes.", "Target micro confirms VWAP/EMA direction, trend slope, volume, and close location.", "Risk is under micro shadow max risk."],
    exit: "Compressed micro stop, micro shadow reward/risk, micro shadow max hold.",
  },
  {
    code: "ECHO",
    title: "Profit-Buffered Micro Echo",
    image: "micro-echo.png",
    paths: ["Delayed echo from ES to MES or NQ to MNQ.", "Same-symbol micro echoes for MES, MNQ, M2K, and MGC."],
    requirements: ["Echo delay and max-delay windows pass.", "Source strategy is allowed.", "Target confirms VWAP/EMA, RSI, trend slope, volume, and close location.", "Risk is under micro echo max risk."],
    exit: "Compressed micro stop, micro echo reward/risk, micro echo max hold.",
  },
  {
    code: "WFT",
    title: "Winner Follow-Through",
    image: "winner-follow-through.png",
    paths: ["Same-symbol follow-through after a source trade reaches target."],
    requirements: ["Source trade exit reason is Target reached.", "Source code is allowed and source PnL threshold passes.", "Delay bars and time window pass.", "VWAP/EMA, RSI, volume, body, slope, and close-location continuation checks pass."],
    exit: "Compressed stop, winner follow-through reward/risk, winner follow-through max hold.",
  },
];

export default function Documents() {
  return (
    <div className="documents-page d-grid gap-3">
      <section className="documents-hero">
        <div>
          <div className="app-kicker">Futures Live Bot</div>
          <h2 className="app-title">Candlestick Entry Paths And Exit Requirements</h2>
        </div>
        <p>
          These diagrams map the bot's actual strategy branches and requirements using synthetic example candles. The live strategy slot is still controlled by promoted backtests; GC is supported and covered here, but it only trades live if a future promoted snapshot includes it.
        </p>
      </section>

      <section className="documents-summary-grid">
        <div className="app-panel">
          <div className="fw-bold app-kicker mb-2">Pipeline Sanity Checks</div>
          <ul className="documents-check-list">
            {pipelineChecks.map((item) => <li key={item}>{item}</li>)}
          </ul>
        </div>
        <div className="app-panel">
          <div className="fw-bold app-kicker mb-2">Exit Completion Requirements</div>
          <ul className="documents-check-list">
            {exitRequirements.map((item) => <li key={item}>{item}</li>)}
          </ul>
        </div>
      </section>

      <section className="documents-flow app-panel">
        <div className="fw-bold app-kicker mb-2">Candle To Trade Path</div>
        <div className="documents-flow-grid" aria-label="Futures live bot candle to trade path">
          <FlowStep label="Candles" detail="1m live bars, previous day, 15m, 1h" />
          <FlowStep label="Signal" detail="Closed signal candle creates strategy event" />
          <FlowStep label="Entry" detail="Next bar open enters after risk validation" />
          <FlowStep label="Broker" detail="TopstepX order adapter submits entry bracket" />
          <FlowStep label="Exit" detail="Stop, target, time, managed, adaptive, or flat" />
        </div>
      </section>

      <section className="strategy-doc-grid">
        {strategyDocs.map((strategy) => (
          <article className="strategy-doc-card" key={strategy.code}>
            <div className="strategy-doc-media">
              <img src={`${STRATEGY_IMAGE_BASE}/${strategy.image}`} alt={`${strategy.code} candlestick example`} />
            </div>
            <div className="strategy-doc-body">
              <div className="strategy-doc-heading">
                <span>{strategy.code}</span>
                <h3>{strategy.title}</h3>
              </div>
              <DocList title="Entry Paths" items={strategy.paths} />
              <DocList title="Requirements" items={strategy.requirements} />
              <div className="strategy-doc-exit">
                <span>Exit</span>
                <p>{strategy.exit}</p>
              </div>
            </div>
          </article>
        ))}
      </section>
    </div>
  );
}

function FlowStep({ label, detail }) {
  return (
    <div className="documents-flow-step">
      <strong>{label}</strong>
      <span>{detail}</span>
    </div>
  );
}

function DocList({ title, items }) {
  return (
    <div className="strategy-doc-list">
      <span>{title}</span>
      <ul>
        {items.map((item) => <li key={item}>{item}</li>)}
      </ul>
    </div>
  );
}
