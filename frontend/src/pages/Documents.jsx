const APPROVED_PRESET = {
  name: "bestbiasfree",
  label: "Best Bias-Free",
  policy: "2026-06-14-bestbiasfree-v19-orbx-vabs",
};

const CONTRACTS = [
  { symbol: "MES", name: "Micro S&P 500", market: "Equity index", tick: "0.25", tickValue: "$1.25", pointValue: "$5.00" },
  { symbol: "MNQ", name: "Micro Nasdaq-100", market: "Equity index", tick: "0.25", tickValue: "$0.50", pointValue: "$2.00" },
  { symbol: "NQ", name: "E-mini Nasdaq-100", market: "Equity index", tick: "0.25", tickValue: "$5.00", pointValue: "$20.00" },
  { symbol: "MGC", name: "Micro Gold", market: "Metal", tick: "0.10", tickValue: "$1.00", pointValue: "$10.00" },
  { symbol: "ES", name: "E-mini S&P 500", market: "Equity index", tick: "0.25", tickValue: "$12.50", pointValue: "$50.00" },
  { symbol: "M2K", name: "Micro Russell 2000", market: "Equity index", tick: "0.10", tickValue: "$0.50", pointValue: "$5.00" },
  { symbol: "MYM", name: "Micro Dow", market: "Equity index", tick: "1.00", tickValue: "$0.50", pointValue: "$0.50" },
  { symbol: "MCL", name: "Micro WTI Crude", market: "Energy", tick: "0.01", tickValue: "$1.00", pointValue: "$100.00" },
];

const PIPELINE_STAGES = [
  {
    number: "01",
    lane: "signal",
    eyebrow: "Configure",
    title: "Choose the mandate",
    detail: "Account, Strategy Config, Risk Config, symbols, Entry Optimizer, and DTM are fixed at live start.",
    output: "Frozen session configuration",
  },
  {
    number: "02",
    lane: "signal",
    eyebrow: "Observe",
    title: "Build market context",
    detail: "Fresh 1m bars are enriched with prior-day levels, VWAP, EMA, RSI, ATR, volume, and derived 15m / 1h structure.",
    output: "Comparable market state",
  },
  {
    number: "03",
    lane: "signal",
    eyebrow: "Detect",
    title: "Detect & rank a setup",
    detail: "Shared detectors read a closed candle, then rank eligible plans by reward/risk, participation, trend, candle quality, and stop geometry. The winner is scheduled for the next bar—not chased later.",
    output: "Side + entry + stop + target",
  },
  {
    number: "04",
    lane: "capital",
    eyebrow: "Protect",
    title: "Price the risk",
    detail: "Stop distance becomes dollars per contract. Daily loss, drawdown, open risk, duplicates, and exposure caps can reduce size or reject.",
    output: "Risk-approved contract count",
  },
  {
    number: "05",
    lane: "execution",
    eyebrow: "Execute",
    title: "Ask the broker",
    detail: "The optional Entry Optimizer runs, then TopstepX receives a protected limit entry with attached stop and target. A resting order is not a fill; missing protection triggers cancel or flatten.",
    output: "Filled, resting, or blocked",
  },
  {
    number: "06",
    lane: "management",
    eyebrow: "Manage",
    title: "Control live exposure",
    detail: "Broker fills are reconciled and exits run before new entries. Static protection remains authoritative while optional DTM may protect, partial, trail, extend, or cut.",
    output: "Protected open position",
  },
  {
    number: "07",
    lane: "management",
    eyebrow: "Account",
    title: "Close the loop",
    detail: "Stop, target, managed exit, max-hold, or session flatten closes risk; fills flow to the ledger, trade cache, and UI.",
    output: "Realized P/L + audit trail",
  },
];

const ORDER_STATES = [
  { state: "Candidate", exposure: "$0 exposure", detail: "A detector found valid geometry. Nothing has reached the broker." },
  { state: "Order", exposure: "Instruction only", detail: "Submitted or resting. It can still reject, cancel, expire, or miss." },
  { state: "Fill", exposure: "Market risk begins", detail: "Actual fill price and quantity—not the signal—create the position." },
  { state: "Partial exit", exposure: "Split state", detail: "P/L is realized on exited contracts while the runner remains exposed and protected." },
  { state: "Flat", exposure: "$0 exposure", detail: "Remaining exposure is closed; final fills and P/L are reconciled to the trading record." },
];

const STRATEGY_FAMILIES = [
  {
    number: "01",
    stance: "Follow",
    title: "Range escape",
    headline: "A meaningful range breaks—and price keeps accepting beyond it.",
    summary: "The bot joins directional pressure after an opening or session structure gives way with enough participation and trend agreement.",
    earns: "Continuation travels farther than the distance risked behind the broken structure.",
    proof: "A closed candle clears the range or channel; volume, time window, and trend context agree.",
    wrong: "Price falls back into the range, violates the swing, or the move arrives too late to execute.",
    strategies: [
      { code: "ORB", name: "Opening Range Breakout", difference: "The initial 9:30–9:45 range breaks with trend and candle-quality confirmation." },
      { code: "LORB", name: "Late ORB Continuation", difference: "Opening-range structure stays valid and resumes in a later continuation window." },
      { code: "ORBX", name: "ORB Event Pack", difference: "Tracks the opening range as a state path: first break, held retest, or failed-break reversal." },
      { code: "OMOM", name: "Opening Momentum", difference: "A compressed early range releases with volume and a bounded swing stop." },
      { code: "PDB", name: "Prior-Day Breakout", difference: "A prior-session high or low breaks—or retests after the break—and continues." },
    ],
    variant: "expansion",
  },
  {
    number: "02",
    stance: "Resume",
    title: "Trend resume",
    headline: "Momentum pauses—or rebuilds—then proves it can resume.",
    summary: "Some paths wait for a pullback to VWAP or an EMA; others require a fresh time-of-day range and continued directional pressure.",
    earns: "The temporary pullback resolves back in the prevailing direction toward a structured reward target.",
    proof: "The reference level holds or is reclaimed, then price breaks the confirming candle in trend direction.",
    wrong: "The reference level fails, the trend stack rolls over, or entry-to-stop geometry becomes too expensive.",
    strategies: [
      { code: "VWAP", name: "VWAP Pullback", difference: "A trend-side pullback holds VWAP and the EMA stack, then resumes." },
      { code: "VRCL", name: "VWAP Reclaim", difference: "Price crosses back through VWAP and proves the reclaim before continuation." },
      { code: "AFT", name: "Afternoon Continuation", difference: "A local afternoon range resolves in the established trend direction." },
      { code: "MIM", name: "Market Momentum", difference: "Intraday momentum aligns with VWAP, EMA direction, and higher-timeframe context." },
      { code: "CMOM", name: "Close Momentum", difference: "Late-session pressure holds near the session extreme into the closing window." },
    ],
    variant: "continuation",
  },
  {
    number: "03",
    stance: "Fade",
    title: "Failed auction",
    headline: "A breakout attracts orders—but cannot hold.",
    summary: "The bot looks for a sweep, band stretch, or high-volume push that is rejected, then trades back toward accepted value.",
    earns: "Trapped breakout pressure unwinds and price rotates away from the failed extreme.",
    proof: "Price pierces a known extreme, closes back through it, and confirms the reclaim with usable risk.",
    wrong: "The breakout holds outside value, absorption never confirms, or higher-timeframe structure stays strongly opposed.",
    strategies: [
      { code: "SWEEP", name: "Liquidity Sweep", difference: "A prior-day extreme is taken, rejected, and reclaimed." },
      { code: "KREV", name: "Keltner Reversion", difference: "A volatility-band stretch reclaims the band in flatter trend conditions." },
      { code: "VABS", name: "Volume Absorption", difference: "High-volume effort through an extreme fails, then the next candle reclaims it." },
      { code: "IFVG", name: "Inversion Gap", difference: "A fair-value gap fails, price displaces through it, and the retest confirms the opposite side." },
    ],
    variant: "failure",
  },
  {
    number: "04",
    stance: "Resolve",
    title: "Imbalance hold",
    headline: "Price revisits an inefficient move—and chooses a side.",
    summary: "Fair-value gaps and reclaimed structures provide a compact location for entry, invalidation, and a measured objective.",
    earns: "The retest respects the imbalance—or a failed gap confirms an inversion—and price expands away from it.",
    proof: "Gap width, impulse, retest depth, reclaim quality, and directional context form one coherent setup.",
    wrong: "Price crosses the invalidation side of the gap or the reclaim lacks enough structure to justify the stop.",
    strategies: [
      { code: "FVG", name: "Fair Value Gap", difference: "A three-candle displacement gap survives a qualified retest and acceptance check." },
      { code: "LIQREC", name: "Liquidity Reclaim", difference: "An ownership overlay that preserves an approved source setup's original entry, stop, and target." },
    ],
    variant: "imbalance",
    note: "LIQREC is an overlay: it preserves an accepted source setup’s entry/stop/target geometry and labels the reclaim path; it is not a new price pattern.",
  },
];

const GUARDRAILS = [
  {
    action: "Reject",
    title: "Market integrity",
    detail: "Closed session, holiday, stale feed, missing warmup, or insufficient bars means the strategy loop cannot open risk.",
  },
  {
    action: "Reject",
    title: "Setup integrity",
    detail: "No closed-candle confirmation, stale next-bar entry, invalid stop geometry, or decayed reward leaves no executable candidate.",
  },
  {
    action: "Size / reject",
    title: "Capital integrity",
    detail: "Daily loss, trailing drawdown, reserved open risk, correlated exposure, duplicate symbols, and contract caps control size.",
  },
  {
    action: "Block / reconcile",
    title: "Broker integrity",
    detail: "Unverified exposure, rejected protection, a resting non-fill, or broker-state disagreement cannot be treated as a position.",
  },
];

export default function Documents() {
  return (
    <article className="documents-page" aria-labelledby="documents-title">
      <section className="documents-hero">
        <div className="documents-hero-copy">
          <div className="documents-eyebrow">Futures system guide</div>
          <h1 id="documents-title">From market structure to dollars at risk.</h1>
          <p className="documents-hero-lede">
            The bot does not trade because a line crossed. It waits for a recognizable auction pattern, defines exactly where that idea is wrong, converts that distance into financial risk, and asks the broker only after every capital guard approves it.
          </p>
          <div className="documents-hero-principles" aria-label="Core trading principles">
            <div>
              <span>01</span>
              <strong>Structure first</strong>
              <small>Price behavior creates the idea.</small>
            </div>
            <div>
              <span>02</span>
              <strong>Invalidation before size</strong>
              <small>The stop prices the risk.</small>
            </div>
            <div>
              <span>03</span>
              <strong>Fill before P/L</strong>
              <small>An order is not a position.</small>
            </div>
          </div>
          <div className="documents-preset-line">
            <span>Approved strategy config</span>
            <strong>{APPROVED_PRESET.label}</strong>
            <code>{APPROVED_PRESET.name}</code>
          </div>
        </div>

        <div className="documents-hero-visual">
          <div className="documents-visual-kicker">
            <span>Financial anatomy</span>
            <strong>One idea. Three prices.</strong>
          </div>
          <TradeGeometryChart />
          <div className="documents-geometry-key">
            <div><i className="target" /> <span><strong>Target</strong> planned reward</span></div>
            <div><i className="entry" /> <span><strong>Entry estimate</strong> sizing input</span></div>
            <div><i className="stop" /> <span><strong>Stop</strong> thesis invalidated</span></div>
          </div>
          <p className="documents-visual-note">Before submission, the bot estimates entry-to-stop loss. The broker's actual fill starts exposure and determines actual P/L.</p>
        </div>
      </section>

      <nav className="documents-jump-nav" aria-label="On this page">
        <span>Read the system</span>
        <a href="#pipeline">Decision pipeline</a>
        <a href="#playbooks">Strategy playbooks</a>
        <a href="#economics">Trade economics</a>
        <a href="#guardrails">Why it says no</a>
      </nav>

      <section className="documents-section documents-pipeline" id="pipeline">
        <SectionHeading
          eyebrow="The complete live workflow"
          title="A signal is the beginning—not the trade."
          detail="Every stage must hand a valid result to the next. Strategy logic is shared with backtests; broker fills, Entry Optimizer, and DTM are live execution layers."
        />
        <div className="documents-pipeline-grid">
          {PIPELINE_STAGES.map((stage) => (
            <article className={`documents-pipeline-step ${stage.lane}`} key={stage.number}>
              <div className="documents-step-top">
                <span>{stage.number}</span>
                <small>{stage.eyebrow}</small>
              </div>
              <h3>{stage.title}</h3>
              <p>{stage.detail}</p>
              <div className="documents-step-output">{stage.output}</div>
            </article>
          ))}
        </div>

        <div className="documents-no-trade-rail">
          <div>
            <span>No trade</span>
            <strong>is a correct system output.</strong>
          </div>
          <p>Stale data · no qualified structure · late entry · invalid stop · no risk room · duplicate exposure · broker reject · resting non-fill</p>
        </div>

        <div className="documents-order-states" aria-label="Trade state and financial exposure">
          {ORDER_STATES.map((item, index) => (
            <div className="documents-order-state" key={item.state}>
              <span>{String(index + 1).padStart(2, "0")}</span>
              <div>
                <strong>{item.state}</strong>
                <b>{item.exposure}</b>
                <p>{item.detail}</p>
              </div>
            </div>
          ))}
        </div>
      </section>

      <section className="documents-section documents-playbooks" id="playbooks">
        <SectionHeading
          eyebrow="Four economic playbooks"
          title="The strategies are different bets on market behavior."
          detail="The approved signal codes are easier to understand when grouped by what must happen for the trade to make money—and what would prove the thesis wrong."
        />
        <div className="documents-playbook-grid">
          {STRATEGY_FAMILIES.map((family) => (
            <StrategyFamilyCard family={family} key={family.title} />
          ))}
        </div>
      </section>

      <section className="documents-section documents-economics" id="economics">
        <SectionHeading
          eyebrow="Trade economics"
          title="A chart move becomes money through contract math."
          detail="The same price move has a different financial value in each futures contract. Position sizing starts with the instrument’s tick value and the planned stop—not a desired contract count."
        />
        <div className="documents-economics-grid">
          <div className="documents-money-math">
            <FormulaCard
              number="01"
              title="Risk per contract"
              formula="stop distance ÷ tick size × tick value + estimated round-trip commission"
              detail="This is the planned dollar loss for one contract if the stop is filled, including modeled costs."
            />
            <FormulaCard
              number="02"
              title="Maximum position"
              formula="available risk budget ÷ buffered risk per contract"
              detail="The result is rounded down, then reduced by symbol, account, aggregate-contract, and funded-unit limits. Below one contract means reject."
            />
            <FormulaCard
              number="03"
              title="Realized P/L"
              formula="signed fill-to-exit ticks × tick value × filled contracts − costs"
              detail="The signal’s planned entry is not authoritative. Actual broker fills and exits determine the financial result."
            />
          </div>

          <div className="documents-contract-panel">
            <div className="documents-contract-head">
              <div>
                <span>Supported live universe</span>
                <h3>What one tick is worth</h3>
              </div>
              <small>per contract</small>
            </div>
            <div className="documents-contract-table-wrap">
              <table className="documents-contract-table">
                <thead>
                  <tr>
                    <th scope="col">Contract</th>
                    <th scope="col">Market</th>
                    <th scope="col">Min move</th>
                    <th scope="col">$/tick</th>
                    <th scope="col">$/point</th>
                  </tr>
                </thead>
                <tbody>
                  {CONTRACTS.map((contract) => (
                    <tr key={contract.symbol}>
                      <th scope="row">
                        <strong>{contract.symbol}</strong>
                        <small>{contract.name}</small>
                      </th>
                      <td>{contract.market}</td>
                      <td>{contract.tick}</td>
                      <td>{contract.tickValue}</td>
                      <td>{contract.pointValue}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      </section>

      <section className="documents-section documents-guardrails" id="guardrails">
        <SectionHeading
          eyebrow="Account protection"
          title="The bot protects the right to trade tomorrow."
          detail="A technically valid setup can still be financially wrong for the account. These gates decide whether to reject, reduce, manage, or flatten exposure."
        />
        <div className="documents-guard-grid">
          {GUARDRAILS.map((guard) => (
            <article className="documents-guard-card" key={guard.title}>
              <span>{guard.action}</span>
              <h3>{guard.title}</h3>
              <p>{guard.detail}</p>
            </article>
          ))}
        </div>
        <div className="documents-risk-close">
          <strong>The operating principle</strong>
          <p>Find an asymmetric idea, define the loss before entry, size to the account’s remaining room, and never confuse a broker instruction with money actually at risk.</p>
        </div>
      </section>

      <details className="documents-provenance">
        <summary>
          <span>Scope, configuration, and source note</span>
          <small>Audit details</small>
        </summary>
        <div className="documents-provenance-body">
          <p>
            This guide describes the approved <strong>{APPROVED_PRESET.label}</strong> source policy (<code>{APPROVED_PRESET.policy}</code>). The selected runtime configuration can change the enabled symbols, risk limits, Entry Optimizer, and DTM behavior.
          </p>
          <p>
            Strategy charts are conceptual market-structure diagrams—not live data, recommendations, or performance claims. Backtest and live signal generation share the detector path; broker execution, fills, reconciliation, and optional live management determine the realized outcome.
          </p>
        </div>
      </details>
    </article>
  );
}

function SectionHeading({ eyebrow, title, detail }) {
  return (
    <header className="documents-section-heading">
      <div>
        <span>{eyebrow}</span>
        <h2>{title}</h2>
      </div>
      <p>{detail}</p>
    </header>
  );
}

function FormulaCard({ number, title, formula, detail }) {
  return (
    <article className="documents-formula-card">
      <div>
        <span>{number}</span>
        <h3>{title}</h3>
      </div>
      <div className="documents-formula-expression">{formula}</div>
      <p>{detail}</p>
    </article>
  );
}

function StrategyFamilyCard({ family }) {
  return (
    <article className={`documents-playbook-card ${family.variant}`}>
      <div className="documents-playbook-top">
        <span>{family.number}</span>
        <b>{family.stance}</b>
      </div>
      <div className="documents-playbook-title">
        <small>{family.title}</small>
        <h3>{family.headline}</h3>
        <p>{family.summary}</p>
      </div>
      <StrategyChart family={family} />
      <div className="documents-playbook-facts">
        <div>
          <span>Why it can pay</span>
          <p>{family.earns}</p>
        </div>
        <div>
          <span>Proof required</span>
          <p>{family.proof}</p>
        </div>
        <div>
          <span>Thesis is wrong when</span>
          <p>{family.wrong}</p>
        </div>
      </div>
      {family.note && <p className="documents-overlay-note">{family.note}</p>}
      <div className="documents-code-row" aria-label={`${family.title} strategy codes`}>
        {family.strategies.map((strategy) => <code key={strategy.code}>{strategy.code}</code>)}
      </div>
      <details className="documents-family-decoder">
        <summary>
          <span>Differences within this family</span>
          <small>{family.strategies.length} paths</small>
        </summary>
        <div className="documents-family-decoder-list">
          {family.strategies.map((strategy) => (
            <div key={strategy.code}>
              <code>{strategy.code}</code>
              <p><strong>{strategy.name}</strong>{strategy.difference}</p>
            </div>
          ))}
        </div>
      </details>
      <small className="documents-chart-disclaimer">Conceptual setup · not live market data</small>
    </article>
  );
}

function TradeGeometryChart() {
  return (
    <svg className="documents-geometry-chart" viewBox="0 0 560 310" role="img" aria-labelledby="trade-geometry-title trade-geometry-desc">
      <title id="trade-geometry-title">Risk-defined long trade geometry</title>
      <desc id="trade-geometry-desc">An illustrative price path reaches a broker fill, with a stop below as invalidation and a target above as planned reward. The space to the stop determines risk and contract sizing.</desc>
      <defs>
        <linearGradient id="reward-zone" x1="0" x2="0" y1="0" y2="1">
          <stop offset="0%" stopColor="#78e2a8" stopOpacity="0.2" />
          <stop offset="100%" stopColor="#78e2a8" stopOpacity="0.03" />
        </linearGradient>
        <linearGradient id="risk-zone" x1="0" x2="0" y1="0" y2="1">
          <stop offset="0%" stopColor="#ff987f" stopOpacity="0.03" />
          <stop offset="100%" stopColor="#ff987f" stopOpacity="0.18" />
        </linearGradient>
      </defs>
      <rect className="chart-shell" x="1" y="1" width="558" height="308" rx="18" />
      <path className="chart-grid" d="M30 64H530 M30 132H530 M30 200H530 M30 268H530" />
      <rect fill="url(#reward-zone)" x="302" y="48" width="194" height="100" rx="10" />
      <rect fill="url(#risk-zone)" x="302" y="148" width="194" height="104" rx="10" />
      <line className="geometry-line target" x1="286" y1="48" x2="512" y2="48" />
      <line className="geometry-line entry" x1="286" y1="148" x2="512" y2="148" />
      <line className="geometry-line stop" x1="286" y1="252" x2="512" y2="252" />
      <polyline className="geometry-price" points="36,232 78,218 112,226 146,186 182,196 222,164 260,174 304,148 342,126 386,132 430,92 478,72 522,54" />
      <circle className="geometry-trigger" cx="304" cy="148" r="7" />
      <text className="geometry-label target" x="414" y="38">TARGET</text>
      <text className="geometry-label entry" x="404" y="138">PLANNED ENTRY</text>
      <text className="geometry-label stop" x="430" y="242">STOP</text>
      <text className="geometry-zone-label reward" x="324" y="86">planned reward</text>
      <text className="geometry-zone-label risk" x="324" y="226">planned risk</text>
    </svg>
  );
}

function StrategyChart({ family }) {
  const titleId = `strategy-chart-${family.variant}-title`;
  const descId = `strategy-chart-${family.variant}-desc`;
  return (
    <svg className={`documents-strategy-chart ${family.variant}`} viewBox="0 0 640 260" role="img" aria-labelledby={`${titleId} ${descId}`}>
      <title id={titleId}>{family.title} conceptual strategy chart</title>
      <desc id={descId}>{strategyChartDescription(family.variant)}</desc>
      <rect className="strategy-chart-shell" x="1" y="1" width="638" height="258" rx="16" />
      <path className="strategy-chart-grid" d="M28 52H612 M28 104H612 M28 156H612 M28 208H612" />
      {renderStrategyChart(family.variant)}
    </svg>
  );
}

function renderStrategyChart(variant) {
  if (variant === "expansion") {
    return (
      <>
        <rect className="strategy-range" x="54" y="112" width="220" height="70" rx="9" />
        <line className="strategy-reference" x1="48" y1="112" x2="594" y2="112" />
        <line className="strategy-target" x1="314" y1="54" x2="594" y2="54" />
        <line className="strategy-stop" x1="314" y1="190" x2="594" y2="190" />
        <polyline className="strategy-price" points="42,168 82,144 122,158 166,132 208,154 252,126 290,110 326,94 362,100 406,76 448,82 494,62 546,54 600,48" />
        <circle className="strategy-trigger" cx="326" cy="94" r="7" />
        <text className="strategy-label" x="74" y="136">opening balance</text>
        <text className="strategy-label entry" x="338" y="88">break + acceptance</text>
        <text className="strategy-label target" x="524" y="44">target</text>
        <text className="strategy-label stop" x="536" y="181">stop</text>
      </>
    );
  }
  if (variant === "continuation") {
    return (
      <>
        <path className="strategy-value-line" d="M38 184 C128 170 194 148 270 134 C350 118 426 102 604 72" />
        <line className="strategy-target" x1="374" y1="48" x2="594" y2="48" />
        <line className="strategy-stop" x1="278" y1="196" x2="594" y2="196" />
        <polyline className="strategy-price" points="42,190 86,160 128,168 176,132 224,112 268,126 306,156 344,138 380,110 424,114 468,82 520,72 600,46" />
        <circle className="strategy-trigger" cx="380" cy="110" r="7" />
        <text className="strategy-label" x="86" y="190">VWAP / accepted value</text>
        <text className="strategy-label entry" x="318" y="102">hold + resume</text>
        <text className="strategy-label target" x="524" y="38">target</text>
        <text className="strategy-label stop" x="536" y="187">stop</text>
      </>
    );
  }
  if (variant === "failure") {
    return (
      <>
        <rect className="strategy-value-zone" x="46" y="116" width="548" height="70" rx="9" />
        <line className="strategy-reference amber" x1="46" y1="112" x2="594" y2="112" />
        <line className="strategy-stop" x1="300" y1="48" x2="594" y2="48" />
        <line className="strategy-target" x1="332" y1="204" x2="594" y2="204" />
        <polyline className="strategy-price" points="42,172 90,158 136,144 184,126 230,106 274,84 306,42 326,132 364,142 408,160 454,154 510,186 600,206" />
        <line className="strategy-wick" x1="306" y1="42" x2="306" y2="126" />
        <circle className="strategy-trigger amber" cx="326" cy="132" r="7" />
        <text className="strategy-label" x="60" y="138">accepted value</text>
        <text className="strategy-label entry" x="342" y="126">failed break + reclaim</text>
        <text className="strategy-label stop" x="536" y="39">stop</text>
        <text className="strategy-label target" x="524" y="195">target</text>
      </>
    );
  }
  return (
    <>
      <rect className="strategy-gap" x="220" y="100" width="150" height="62" rx="9" />
      <line className="strategy-target" x1="384" y1="48" x2="594" y2="48" />
      <line className="strategy-stop" x1="354" y1="202" x2="594" y2="202" />
      <polyline className="strategy-price" points="42,190 92,178 138,148 182,122 226,78 266,70 302,150 338,138 376,106 416,112 462,86 514,66 600,48" />
      <circle className="strategy-trigger" cx="376" cy="106" r="7" />
      <text className="strategy-label" x="238" y="128">imbalance</text>
      <text className="strategy-label entry" x="390" y="100">retest + decision</text>
      <text className="strategy-label target" x="524" y="38">target</text>
      <text className="strategy-label stop" x="536" y="193">stop</text>
    </>
  );
}

function strategyChartDescription(variant) {
  if (variant === "expansion") return "Price trades within an opening balance, breaks above it, confirms acceptance, and travels toward a target with a stop back below structure.";
  if (variant === "continuation") return "Price trends higher, pulls back toward accepted value, confirms a hold, and resumes with a stop below the failed reference.";
  if (variant === "failure") return "Price briefly breaks above a known extreme, fails back into accepted value, and reverses toward a lower target with a stop above the sweep.";
  return "Price creates an imbalance, retests the gap, confirms a directional decision, and expands toward a target with a stop beyond invalidation.";
}
