const ACTIVE_SYMBOLS = ["MES", "MNQ", "NQ", "MGC", "ES", "M2K", "MYM", "MCL"];

const APPROVED_PRESET = {
  name: "bestbiasfree",
  label: "Best Bias-Free",
  policy: "2026-06-14-bestbiasfree-v19-orbx-vabs",
  source: "FuturesManager.applyBestBiasFreePresetPolicy",
};

const LIVE_API_NOTE = "Live API confirms bestbiasfree is the default visible preset. Dev source includes ORBX and VABS in the approved policy; running live may show them after backend promotion.";

const pipelineChecks = [
  {
    label: "Preset source",
    detail: "Live start selects a Strategy Config preset directly. Legacy copy-to-live is disabled.",
  },
  {
    label: "Signal timing",
    detail: "Detectors read closed candles and schedule normal entries for the next bar open before the session guard.",
  },
  {
    label: "Context stack",
    detail: "1m realtime candles can use previous-day levels, 15m, 1h, VWAP, EMA stack, RSI, volume, and source-stack settings.",
  },
  {
    label: "Risk handoff",
    detail: "Candidates pass portfolio budget, daily caps, per-strategy caps, duplicate-symbol, broker exposure, and account checks.",
  },
  {
    label: "Execution",
    detail: "TopstepX orders use the shared futures adapter with entry, stop, target, ledger, trade cache, and audit/risk logs.",
  },
  {
    label: "Exit stack",
    detail: "Stop, target, managed stop, adaptive loss cut, max-hold, broker-flat sync, and session flatten all share the exit path.",
  },
];

const activeBySymbol = {
  MES: ["OMOM", "AFT", "CMOM", "LIQREC"],
  MNQ: ["ORB", "OMOM", "SWEEP", "PDB", "VWAP", "AFT", "LIQREC", "VABS"],
  NQ: ["ORB", "LORB", "OMOM", "SWEEP", "PDB", "VWAP", "VRCL", "FVG", "MIM", "KREV", "LIQREC"],
  MGC: ["ORB", "OMOM", "SWEEP", "PDB", "VWAP", "CMOM", "LIQREC", "VABS"],
  ES: ["ORB", "LIQREC", "VABS"],
  M2K: ["ORB", "OMOM", "CMOM", "ORBX", "LIQREC", "VABS"],
  MYM: ["ORB", "OMOM", "CMOM", "LIQREC", "VABS"],
  MCL: ["ORB", "IFVG", "AFT", "MIM", "CMOM", "LIQREC"],
};

const removedStrategies = [
  "ORB2",
  "MRVWAP",
  "KELT",
  "MSCALP",
  "TLAD",
  "RCB",
  "VPB",
  "SHDW",
  "ECHO",
  "WFT",
  "SWEEP2",
  "COPEN",
  "IDXCONF",
  "MCLTC",
];

const REMOVED_STRATEGY_COUNT = removedStrategies.length;

const strategyGroups = [
  {
    title: "Opening Range Engine",
    detail: "Trades the first range break, late continuation, and the approved M2K event-pack retest.",
    strategies: [
      {
        code: "ORB",
        name: "Opening Range Breakout",
        variant: "range",
        symbols: symbolsFor("ORB"),
        entry: "Breaks the opening range high or low with side-specific permission and quality filters.",
        reads: ["9:30 range", "close location", "volume", "VWAP/EMA guard"],
        gates: ["ORB side policy", "compressed risk", "daily cap", "HTF guard"],
        exit: "Range or compressed swing stop, target from configured reward/risk, common exit stack.",
      },
      {
        code: "LORB",
        name: "Late ORB Continuation",
        variant: "lateRange",
        symbols: symbolsFor("LORB"),
        entry: "NQ-only late continuation after the range is already established and trend filters agree.",
        reads: ["ORB edge", "late window", "volume ratio", "VWAP/EMA alignment"],
        gates: ["NQ policy", "short/long permission", "max risk ticks", "late max hold"],
        exit: "Compressed swing stop with late-ORB max-hold protection.",
      },
      {
        code: "ORBX",
        name: "ORB Event Pack",
        variant: "state",
        symbols: symbolsFor("ORBX"),
        entry: "M2K approved event pack: first range break arms the setup, then a retest confirms the broken level.",
        reads: ["5m ORB", "first break", "retest touch", "VWAP side"],
        gates: ["M2K_RETEST_LONG", "risk <= 220 ticks", "8/day cap", "no similar-ORB requirement"],
        exit: "Retest swing stop with event-pack reward multiple and 390-bar max hold.",
      },
    ],
  },
  {
    title: "Trend And Momentum Stack",
    detail: "Uses trend context, VWAP, volume participation, and late-session continuation checks.",
    strategies: [
      {
        code: "OMOM",
        name: "Compressed Opening Momentum",
        variant: "momentum",
        symbols: symbolsFor("OMOM"),
        entry: "Opening impulse breaks out after the configured opening range and passes side/time buckets.",
        reads: ["opening impulse", "volume", "close location", "EMA/VWAP"],
        gates: ["time bucket", "DOW masks", "max initial risk", "portfolio compression"],
        exit: "Recent swing stop, opening momentum target, and max-hold cutoff.",
      },
      {
        code: "VWAP",
        name: "VWAP Trend Pullback",
        variant: "vwap",
        symbols: symbolsFor("VWAP"),
        entry: "Pullback holds trend structure around VWAP/EMA and breaks the prior candle in trend direction.",
        reads: ["VWAP distance", "EMA stack", "slope", "prior high/low"],
        gates: ["trend slope", "volume ratio", "max VWAP distance", "HTF guard"],
        exit: "Swing or EMA stop, minimum reward/risk target, short max-hold.",
      },
      {
        code: "VRCL",
        name: "VWAP Reclaim Continuation",
        variant: "reclaim",
        symbols: symbolsFor("VRCL"),
        entry: "NQ reclaim setup after price touches VWAP and then reclaims the directional side.",
        reads: ["VWAP touch", "reclaim candle", "EMA stack", "slope"],
        gates: ["bucket spacing", "volume", "VWAP distance", "prior candle break"],
        exit: "VWAP/swing stop, reclaim reward/risk, 18-bar max hold.",
      },
      {
        code: "AFT",
        name: "Afternoon Continuation",
        variant: "channel",
        symbols: symbolsFor("AFT"),
        entry: "Afternoon channel break with VWAP, EMA, slope, and higher-timeframe agreement.",
        reads: ["afternoon channel", "volume", "VWAP side", "HTF trend"],
        gates: ["13:00+ window", "side policy", "max risk", "daily cap"],
        exit: "Compressed swing stop, afternoon target, and afternoon max hold.",
      },
      {
        code: "MIM",
        name: "Market Intraday Momentum",
        variant: "session",
        symbols: symbolsFor("MIM"),
        entry: "Late-day continuation when both the open move and late move agree.",
        reads: ["first-half-hour move", "late move", "VWAP", "volume"],
        gates: ["15:25-15:35 window", "EMA/close location", "HTF guard", "max hold"],
        exit: "Compressed stop, configured market momentum target, 35-bar max hold.",
      },
      {
        code: "CMOM",
        name: "Close Momentum",
        variant: "close",
        symbols: symbolsFor("CMOM"),
        entry: "Late continuation near session high/low after a meaningful move from RTH open.",
        reads: ["RTH open move", "session extreme", "volume", "EMA stack"],
        gates: ["14:30-15:25 window", "side policy", "close location", "HTF guard"],
        exit: "EMA/swing stop, close momentum target, 40-bar max hold.",
      },
    ],
  },
  {
    title: "Liquidity, Imbalance, And Reclaim Stack",
    detail: "Tracks prior-day levels, sweeps, fair-value gaps, Keltner reclaims, and the LIQREC source overlay.",
    strategies: [
      {
        code: "SWEEP",
        name: "Prior-Day Liquidity Sweep",
        variant: "sweep",
        symbols: symbolsFor("SWEEP"),
        entry: "Sweeps prior-day high/low, then closes back through the level with reclaim quality.",
        reads: ["prior high/low", "wick sweep", "reclaim ticks", "close location"],
        gates: ["previous day exists", "13:00-14:30 late sweep", "HTF context", "risk ticks"],
        exit: "Stop beyond sweep wick, target from reward/risk, common exit stack.",
      },
      {
        code: "PDB",
        name: "Prior-Day Breakout Retest",
        variant: "levelRetest",
        symbols: symbolsFor("PDB"),
        entry: "Breaks a prior-day level, waits for retest, then confirms continuation.",
        reads: ["prior-day high/low", "break distance", "retest", "VWAP/EMA"],
        gates: ["retest window", "volume", "close location", "HTF guard"],
        exit: "Retest/swing stop, prior-day breakout target, 35-bar max hold.",
      },
      {
        code: "FVG",
        name: "Fair Value Gap Reclaim",
        variant: "gap",
        symbols: symbolsFor("FVG"),
        entry: "NQ-only three-candle imbalance retest and reclaim through the gap structure.",
        reads: ["gap width", "impulse body", "retest depth", "trend slope"],
        gates: ["core quality", "EMA stack", "source mode", "risk band"],
        exit: "Gap/swing stop, FVG reward/risk, configured max hold.",
      },
      {
        code: "IFVG",
        name: "Inversion Fair Value Gap",
        variant: "inversion",
        symbols: symbolsFor("IFVG"),
        entry: "MCL inversion path: gap fails, structure breaks, and reclaim quality confirms the reversal.",
        reads: ["failed gap", "structure break", "reclaim close", "MCL trend slope"],
        gates: ["MCL long policy", "break bars", "16-48 risk ticks", "VWAP extension"],
        exit: "Invalidation beyond gap/swing, 1.2R target, 18-bar max hold.",
      },
      {
        code: "KREV",
        name: "Keltner Band Reclaim Reversion",
        variant: "band",
        symbols: symbolsFor("KREV"),
        entry: "NQ reversion after a stretch outside the Keltner band reclaims back through the band.",
        reads: ["Keltner band", "RSI stretch", "VWAP distance", "reversal candle"],
        gates: ["flat-enough slope", "band width", "body quality", "HTF context"],
        exit: "Compressed stop with target toward EMA20 and Keltner max hold.",
      },
      {
        code: "LIQREC",
        name: "Liquidity Reclaim Overlay",
        variant: "overlay",
        symbols: symbolsFor("LIQREC"),
        entry: "First-class source-stack overlay relabels accepted reclaim structures into LIQREC signals.",
        reads: ["FVG", "VWAP", "AFT", "SWEEP", "PDB", "KREV", "VPB", "SHDW"],
        gates: ["09:30-15:30", "source ownership", "duplicate policy", "MNQ max contracts"],
        exit: "Uses the original source geometry with LIQREC label, then the shared exit stack.",
      },
    ],
  },
  {
    title: "Volume Absorption Reversal",
    detail: "New approved volume-price path for high-volume failure and next-candle reclaim behavior.",
    strategies: [
      {
        code: "VABS",
        name: "Volume Absorption Reversal",
        variant: "absorption",
        symbols: symbolsFor("VABS"),
        entry: "A high-volume effort breaks a recent extreme but closes absorbed; next candle reclaims the failure level.",
        reads: ["24-bar lookback", "volume spike", "absorption candle", "next reclaim"],
        gates: ["10:00-11:59 window", "body >= 45%", "volume ratio", "HTF constructive"],
        exit: "Stop beyond absorption extreme plus buffer, 1.5R target, 90-bar max hold.",
      },
    ],
  },
];

export default function Documents() {
  const activeCodes = uniqueCodes(strategyGroups);
  const liveApiConfirmed = liveApiConfirmedCodes();

  return (
    <div className="documents-page">
      <section className="documents-hero">
        <div className="documents-hero-copy">
          <div className="documents-label">Futures Documentation</div>
          <h2 className="app-title">Live Strategy Atlas</h2>
          <p>
            Source-backed map of the currently approved live futures strategy stack. The page now removes old standalone research cards and shows how each active detector reads context, creates a candidate, hands risk to the live pipeline, and exits.
          </p>
        </div>
        <div className="documents-source-panel">
          <span>Default strategy config</span>
          <strong>{APPROVED_PRESET.label}</strong>
          <code>{APPROVED_PRESET.name}</code>
          <small>{APPROVED_PRESET.policy}</small>
        </div>
      </section>

      <section className="documents-source-grid" aria-label="Documents source of truth">
        <SourceTile label="Symbols" value={`${ACTIVE_SYMBOLS.length} live contracts`} detail={ACTIVE_SYMBOLS.join(" / ")} />
        <SourceTile label="Active strategies" value={`${activeCodes.length} top-level paths`} detail={activeCodes.join(", ")} />
        <SourceTile label="Source" value="Preset policy method" detail={`${APPROVED_PRESET.source} plus detector dispatch.`} />
        <SourceTile label="Removed" value={`${REMOVED_STRATEGY_COUNT} stale cards`} detail="Deprecated and disabled research strategies are not shown on this page." tone="muted" />
      </section>

      <section className="documents-note-panel">
        <strong>Live runtime note</strong>
        <span>{LIVE_API_NOTE}</span>
      </section>

      <section className="documents-pipeline">
        <div className="documents-section-head">
          <div>
            <span>Live Pipeline</span>
            <h3>How a candle becomes a trade</h3>
          </div>
          <p>Every strategy card below follows the same operational chain: context read, signal trigger, risk gate, broker submit, then managed exits.</p>
        </div>
        <div className="documents-pipeline-grid">
          {pipelineChecks.map((item, index) => (
            <div className="documents-pipeline-step" key={item.label}>
              <b>{String(index + 1).padStart(2, "0")}</b>
              <strong>{item.label}</strong>
              <span>{item.detail}</span>
            </div>
          ))}
        </div>
      </section>

      <section className="documents-symbol-matrix">
        <div className="documents-section-head">
          <div>
            <span>Coverage Matrix</span>
            <h3>Active paths by contract</h3>
          </div>
          <p>Bright chips are approved in the dev source policy. A subtle outline marks what the read-only live API currently reports as enabled on port 7070.</p>
        </div>
        <div className="documents-matrix-grid">
          {ACTIVE_SYMBOLS.map((symbol) => (
            <div className="documents-symbol-row" key={symbol}>
              <strong>{symbol}</strong>
              <div>
                {activeBySymbol[symbol].map((code) => (
                  <span className={liveApiConfirmed[symbol]?.includes(code) ? "live-confirmed" : ""} key={`${symbol}-${code}`}>
                    {code}
                  </span>
                ))}
              </div>
            </div>
          ))}
        </div>
      </section>

      <section className="documents-strategy-atlas">
        {strategyGroups.map((group) => (
          <div className="documents-strategy-group" key={group.title}>
            <div className="documents-section-head">
              <div>
                <span>{group.title}</span>
                <h3>{group.strategies.length} active path{group.strategies.length === 1 ? "" : "s"}</h3>
              </div>
              <p>{group.detail}</p>
            </div>
            <div className="documents-strategy-grid">
              {group.strategies.map((strategy) => (
                <StrategyCard strategy={strategy} key={strategy.code} />
              ))}
            </div>
          </div>
        ))}
      </section>
    </div>
  );
}

function SourceTile({ label, value, detail, tone = "" }) {
  return (
    <div className={`documents-source-tile ${tone}`}>
      <span>{label}</span>
      <strong>{value}</strong>
      <small>{detail}</small>
    </div>
  );
}

function StrategyCard({ strategy }) {
  return (
    <article className="documents-strategy-card">
      <div className="documents-strategy-top">
        <div>
          <span>{strategy.code}</span>
          <h4>{strategy.name}</h4>
        </div>
        <div className="documents-symbol-chip-row" aria-label={`${strategy.code} active symbols`}>
          {strategy.symbols.map((symbol) => <b key={`${strategy.code}-${symbol}`}>{symbol}</b>)}
        </div>
      </div>
      <StrategyDiagram variant={strategy.variant} code={strategy.code} />
      <p className="documents-entry-copy">{strategy.entry}</p>
      <div className="documents-strategy-columns">
        <SignalList title="Reads" items={strategy.reads} />
        <SignalList title="Gates" items={strategy.gates} />
      </div>
      <div className="documents-exit-row">
        <span>Exit</span>
        <p>{strategy.exit}</p>
      </div>
    </article>
  );
}

function SignalList({ title, items }) {
  return (
    <div className="documents-signal-list">
      <span>{title}</span>
      <ul>
        {items.map((item) => <li key={item}>{item}</li>)}
      </ul>
    </div>
  );
}

function StrategyDiagram({ variant, code }) {
  return (
    <svg className={`documents-strategy-diagram ${variant}`} viewBox="0 0 360 160" role="img" aria-label={`${code} strategy mechanism`}>
      <rect className="chart-bg" x="0" y="0" width="360" height="160" rx="8" />
      <line className="grid-line" x1="24" y1="122" x2="336" y2="122" />
      <line className="grid-line faint" x1="24" y1="82" x2="336" y2="82" />
      <line className="grid-line faint" x1="24" y1="42" x2="336" y2="42" />
      {renderDiagramVariant(variant)}
      <text className="diagram-code" x="22" y="26">{code}</text>
    </svg>
  );
}

function renderDiagramVariant(variant) {
  if (variant === "range") {
    return (
      <>
        <rect className="range-zone" x="44" y="58" width="94" height="48" rx="4" />
        <line className="level-line" x1="42" y1="58" x2="296" y2="58" />
        <polyline className="price-line" points="32,98 62,88 92,102 126,74 154,56 188,48 224,42 286,34 326,28" />
        <path className="arrow-line" d="M232 48 L286 34" />
        <text className="diagram-label" x="44" y="132">range break</text>
      </>
    );
  }
  if (variant === "lateRange") {
    return (
      <>
        <rect className="range-zone" x="38" y="64" width="88" height="42" rx="4" />
        <line className="level-line" x1="34" y1="64" x2="326" y2="64" />
        <polyline className="price-line" points="32,98 72,90 116,68 158,58 204,52 244,48 294,40 326,36" />
        <rect className="time-window" x="210" y="28" width="94" height="108" rx="5" />
        <text className="diagram-label" x="218" y="132">late continuation</text>
      </>
    );
  }
  if (variant === "state") {
    return (
      <>
        <rect className="state-node" x="36" y="58" width="74" height="38" rx="6" />
        <rect className="state-node active" x="143" y="36" width="76" height="38" rx="6" />
        <rect className="state-node final" x="252" y="58" width="72" height="38" rx="6" />
        <path className="state-link" d="M110 77 C124 76 130 56 143 55" />
        <path className="state-link" d="M219 55 C232 56 238 77 252 77" />
        <text className="state-text" x="52" y="82">range</text>
        <text className="state-text" x="158" y="60">break</text>
        <text className="state-text" x="265" y="82">retest</text>
        <text className="diagram-label" x="40" y="132">state machine</text>
      </>
    );
  }
  if (variant === "momentum") {
    return (
      <>
        <path className="trend-band" d="M30 114 C84 100 112 86 158 70 C214 50 254 42 330 30 L330 52 C250 64 218 72 162 92 C118 108 82 120 30 132 Z" />
        <polyline className="price-line" points="32,122 70,108 104,112 138,86 174,78 214,58 248,62 292,42 330,34" />
        <circle className="signal-dot" cx="292" cy="42" r="7" />
        <text className="diagram-label" x="44" y="132">impulse + continuation</text>
      </>
    );
  }
  if (variant === "vwap" || variant === "reclaim") {
    return (
      <>
        <path className="vwap-line" d="M30 92 C88 84 142 82 196 72 C248 62 292 58 330 50" />
        <polyline className="price-line" points="34,104 82,84 118,96 154,82 190,72 226,80 260,58 300,54 330,44" />
        <circle className="signal-dot" cx="260" cy="58" r="7" />
        <text className="diagram-label" x="42" y="132">{variant === "reclaim" ? "touch + reclaim" : "pullback hold"}</text>
      </>
    );
  }
  if (variant === "channel" || variant === "session" || variant === "close") {
    return (
      <>
        <path className="channel-line" d="M34 112 L322 54" />
        <path className="channel-line faint" d="M34 88 L322 30" />
        <polyline className="price-line" points="36,110 78,96 116,100 154,76 196,78 240,58 282,62 326,42" />
        <rect className="time-window" x="228" y="22" width="86" height="112" rx="5" />
        <text className="diagram-label" x="42" y="132">{variant === "close" ? "late session drive" : "channel break"}</text>
      </>
    );
  }
  if (variant === "sweep" || variant === "levelRetest") {
    return (
      <>
        <line className="level-line amber" x1="34" y1="78" x2="328" y2="78" />
        <polyline className="price-line" points="34,94 74,86 112,74 148,58 184,86 220,74 260,70 300,58 326,52" />
        <path className="wick-line" d="M184 86 L184 116" />
        <circle className="signal-dot amber" cx={variant === "levelRetest" ? "220" : "184"} cy={variant === "levelRetest" ? "74" : "86"} r="7" />
        <text className="diagram-label" x="42" y="132">{variant === "levelRetest" ? "break + retest" : "sweep + reclaim"}</text>
      </>
    );
  }
  if (variant === "gap" || variant === "inversion") {
    return (
      <>
        <rect className="gap-zone" x="132" y="58" width="92" height="34" rx="4" />
        <polyline className="price-line" points="32,108 74,96 116,78 152,48 190,96 224,76 260,62 302,52 330,46" />
        <path className="arrow-line amber" d="M190 96 L224 76" />
        <text className="diagram-label" x="42" y="132">{variant === "inversion" ? "failed gap inversion" : "gap retest"}</text>
      </>
    );
  }
  if (variant === "band") {
    return (
      <>
        <path className="band-line" d="M34 58 C98 42 162 50 226 42 C268 38 302 46 330 42" />
        <path className="band-line lower" d="M34 112 C98 96 162 104 226 96 C268 92 302 100 330 96" />
        <polyline className="price-line" points="34,84 76,98 112,118 152,96 196,88 238,74 286,78 326,66" />
        <circle className="signal-dot" cx="152" cy="96" r="7" />
        <text className="diagram-label" x="42" y="132">band stretch reclaim</text>
      </>
    );
  }
  if (variant === "overlay") {
    return (
      <>
        <rect className="overlay-box" x="38" y="42" width="70" height="30" rx="5" />
        <rect className="overlay-box" x="38" y="88" width="70" height="30" rx="5" />
        <rect className="overlay-box active" x="145" y="64" width="86" height="34" rx="5" />
        <rect className="overlay-box final" x="268" y="64" width="58" height="34" rx="5" />
        <path className="state-link" d="M108 57 L145 78" />
        <path className="state-link" d="M108 103 L145 84" />
        <path className="state-link" d="M231 81 L268 81" />
        <text className="state-text" x="57" y="62">src</text>
        <text className="state-text" x="162" y="86">reclaim</text>
        <text className="state-text" x="279" y="86">risk</text>
        <text className="diagram-label" x="42" y="132">source-stack relabel</text>
      </>
    );
  }
  return (
    <>
      <rect className="volume-bar" x="58" y="92" width="18" height="32" />
      <rect className="volume-bar" x="88" y="82" width="18" height="42" />
      <rect className="volume-bar spike" x="118" y="42" width="18" height="82" />
      <rect className="volume-bar" x="148" y="86" width="18" height="38" />
      <polyline className="price-line" points="34,86 78,78 118,112 150,70 194,58 240,54 286,46 326,42" />
      <line className="level-line amber" x1="108" y1="112" x2="230" y2="112" />
      <circle className="signal-dot" cx="150" cy="70" r="7" />
      <text className="diagram-label" x="42" y="132">absorption + reclaim</text>
    </>
  );
}

function symbolsFor(code) {
  return ACTIVE_SYMBOLS.filter((symbol) => activeBySymbol[symbol]?.includes(code));
}

function uniqueCodes(groups) {
  return groups.flatMap((group) => group.strategies.map((strategy) => strategy.code));
}

function liveApiConfirmedCodes() {
  return {
    MES: ["OMOM", "CMOM", "AFT", "LIQREC"],
    MNQ: ["ORB", "OMOM", "SWEEP", "PDB", "VWAP", "AFT", "LIQREC"],
    NQ: ["ORB", "LORB", "OMOM", "SWEEP", "PDB", "VWAP", "VRCL", "FVG", "MIM", "KREV", "LIQREC"],
    MGC: ["ORB", "OMOM", "SWEEP", "PDB", "VWAP", "CMOM", "LIQREC"],
    ES: ["ORB", "LIQREC"],
    M2K: ["ORB", "OMOM", "CMOM", "LIQREC"],
    MYM: ["ORB", "OMOM", "CMOM", "LIQREC"],
    MCL: ["ORB", "IFVG", "CMOM", "AFT", "MIM", "LIQREC"],
  };
}
