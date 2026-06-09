# Live Backtest Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make live portfolio trade acceptance match portfolio backtest acceptance for the selected Strategy Config, Risk Config, account, and symbols.

**Architecture:** Introduce an explicit portfolio validation policy so portfolio backtest and live can share the same acceptance contract. The default live policy for this work is `BACKTEST_PARITY`: live uses the planned next-bar entry contract for submit/no-submit decisions, while broker executable price, spread, and entry decay are logged as diagnostics instead of blocking trades. Strict live-only protection can remain available as a separately named policy, but it must not silently override the backtest parity mode.

**Tech Stack:** Java, JUnit 5, SQLite live diagnostics, existing `FuturesManager` nested classes and reflection-based backend tests.

---

## Current Evidence

- Live session `43` is running `TOPSTEPX`, preset `bestbiasfree`, slot `PRESET_BESTBIASFREE`, symbols `MES,MNQ,NQ,MGC,ES,M2K,MYM,MCL`.
- Live decision `54` rejected `MES OMOM SHORT`, signal `2026-06-09 10:41`, entry `2026-06-09 10:42`, reason `LIVE_ENTRY_DECAY`.
- The sizing payload for decision `54` shows planned stop `7437.75`, live executable entry `7429.75`, effective target `7425.25`, raw risk `32.0`, max risk `32.0`, remaining reward `12.95` ticks, consumed reward `3.0` ticks, live reward/risk `0.40`, minimum live reward/risk `0.50`.
- The 1-second loop was active. Cycle `2584` saw the `10:42` bar with `candidateCount=1`, `rejectedCount=1`, `cycleDurationMs=2023`, `latestBarLagSeconds=3`, `cycleDelaySeconds=1`.
- Source asymmetry:
  - Live builds the same next-bar `SignalEvent` contract in `FuturesManager.prepareLivePortfolioSignalEvents`.
  - Live then replaces the planned entry bar with `liveExecutionBarForCandidate` and blocks via `liveEntryDecayRejectReason` before normal sizing.
  - Portfolio backtest opens through `openPortfolioPosition` using the planned `entryBar` and does not run `liveEntryDecayRejectReason`.

## Files

- Modify: `trading_bot/backend/src/main/java/com/tradingbot/FuturesManager.java`
  - Add a small validation policy object or enum near the live/portfolio validation helpers.
  - Refactor live validation so decay can be a warning in parity mode.
  - Keep broker exposure, duplicate position, account risk, trailing drawdown, daily loss, max contracts, aggregate funded-unit limits, and per-strategy daily limits active.
  - Ensure risk compression policy is identical between portfolio backtest and live parity.
- Modify: `trading_bot/backend/src/test/java/com/tradingbot/FuturesBacktestLiveParityIntegrityTest.java`
  - Add focused regression tests for OMOM decay, ORB compression parity, and shared policy selection.
- Optional later: `trading_bot/frontend/src/...`
  - Only if a UI toggle is desired. This plan can ship backend-first with a fixed `BACKTEST_PARITY` live policy.

---

### Task 1: Add the Failing OMOM Parity Test

**Files:**
- Modify: `trading_bot/backend/src/test/java/com/tradingbot/FuturesBacktestLiveParityIntegrityTest.java`

- [ ] **Step 1: Write a failing test proving live parity mode does not block OMOM on entry decay**

Add a test that constructs the exact decision-54 geometry and expects no blocking reason in backtest parity mode:

```java
@Test
public void backtestParityPolicyDoesNotBlockOmomForLiveEntryDecay() throws Exception {
	Object context = portfolioSymbolContext("MES", 1.0);
	Object event = signalEvent("MES", "OMOM", "Compressed Opening Momentum", "SHORT", "2026-06-09", "10:42", 0);
	Object signal = field(event, "signal");
	setField(signal, "entryPrice", Double.valueOf(7430.5));
	setField(signal, "stopPrice", Double.valueOf(7437.75));
	setField(signal, "targetPrice", Double.valueOf(7426.51));
	Object executionBar = bar("2026-06-09 10:42", 7429.75);
	setField(executionBar, "open", Double.valueOf(7429.75));
	setField(executionBar, "high", Double.valueOf(7430.0));
	setField(executionBar, "low", Double.valueOf(7428.0));
	setField(executionBar, "close", Double.valueOf(7429.75));

	String reason = liveEntryDecayRejectReason(context, event, executionBar, "BACKTEST_PARITY");

	assertEquals("", reason);
}
```

- [ ] **Step 2: Add reflection helpers required by the test**

```java
private static Object signalEvent(String symbol, String strategyCode, String strategyName, String side, String day, String entryTime, int executionIndex) throws Exception {
	Object event = nestedInstance("SignalEvent");
	Object signal = signal(strategyCode, strategyName, side);
	setField(event, "symbol", symbol);
	setField(event, "signal", signal);
	setField(event, "day", java.time.LocalDate.parse(day));
	setField(event, "entryTime", java.time.LocalTime.parse(entryTime));
	setField(event, "executionIndex", Integer.valueOf(executionIndex));
	return event;
}

private static Object field(Object target, String name) throws Exception {
	Field field = target.getClass().getDeclaredField(name);
	field.setAccessible(true);
	return field.get(target);
}

private static String liveEntryDecayRejectReason(Object context, Object event, Object executionBar, String policyName) throws Exception {
	Method method = FuturesManager.class.getDeclaredMethod(
		"liveEntryDecayRejectReason",
		context.getClass(),
		event.getClass(),
		executionBar.getClass(),
		String.class
	);
	method.setAccessible(true);
	return (String) method.invoke(null, context, event, executionBar, policyName);
}
```

- [ ] **Step 3: Run the focused test and confirm it fails**

Run:

```bash
cd /Users/anishpatel/Documents/SoftwareProject/trading_bot/backend
./mvnw -q -Dtest=FuturesBacktestLiveParityIntegrityTest#backtestParityPolicyDoesNotBlockOmomForLiveEntryDecay test
```

Expected: compile failure or assertion failure because `liveEntryDecayRejectReason(..., String policyName)` does not exist yet.

---

### Task 2: Add an Explicit Validation Policy

**Files:**
- Modify: `trading_bot/backend/src/main/java/com/tradingbot/FuturesManager.java`

- [ ] **Step 1: Add the policy helpers near the live validation helpers**

Add:

```java
private static final String PORTFOLIO_VALIDATION_BACKTEST_PARITY = "BACKTEST_PARITY";
private static final String PORTFOLIO_VALIDATION_LIVE_STRICT = "LIVE_STRICT";

private static String activeLivePortfolioValidationPolicy(FuturesLiveSession session, LiveStrategySnapshotRow snapshot) {
	return PORTFOLIO_VALIDATION_BACKTEST_PARITY;
}

private static boolean portfolioPolicyBlocksEntryDecay(String policyName) {
	return PORTFOLIO_VALIDATION_LIVE_STRICT.equals(cleanOrDefault(policyName, "").toUpperCase(Locale.US));
}
```

- [ ] **Step 2: Overload entry-decay validation**

Replace the existing three-argument method with a delegating overload:

```java
private static String liveEntryDecayRejectReason(PortfolioSymbolContext context, SignalEvent event, Bar executionBar) {
	return liveEntryDecayRejectReason(context, event, executionBar, PORTFOLIO_VALIDATION_LIVE_STRICT);
}

private static String liveEntryDecayRejectReason(PortfolioSymbolContext context, SignalEvent event, Bar executionBar, String policyName) {
	if (!portfolioPolicyBlocksEntryDecay(policyName)) {
		return "";
	}
	if (context == null || context.spec == null || event == null || event.signal == null || executionBar == null) {
		return "Rejected: live signal could not be checked for entry decay.";
	}
	Signal signal = event.signal;
	String code = cleanOrDefault(signal.strategyCode, "").toUpperCase(Locale.US);
	double tick = Math.max(0.000001, context.spec.tickSize);
	double minimumLiveRewardRisk = liveEntryDecayMinimumRewardRisk(code);
	double liveRisk = Math.abs(executionBar.open - signal.stopPrice);
	double remainingReward = "LONG".equals(signal.side)
		? signal.targetPrice - executionBar.open
		: executionBar.open - signal.targetPrice;
	if (liveRisk <= 0.0) {
		return "Rejected: live entry decayed because the executable price crossed the original invalidation/stop.";
	}
	if ("LONG".equals(signal.side)) {
		if (executionBar.open >= signal.targetPrice - tick || executionBar.high >= signal.targetPrice - tick) {
			return "Rejected: " + code + " live entry decayed because the first target zone already traded before a fresh executable entry was available.";
		}
	} else if ("SHORT".equals(signal.side)) {
		if (executionBar.open <= signal.targetPrice + tick || executionBar.low <= signal.targetPrice + tick) {
			return "Rejected: " + code + " live entry decayed because the first target zone already traded before a fresh executable entry was available.";
		}
	}
	double liveRewardRisk = remainingReward / liveRisk;
	if (liveRewardRisk < minimumLiveRewardRisk) {
		return "Rejected: " + code + " live entry decayed because the executable price consumed too much reward versus the original stop/target plan.";
	}
	return "";
}
```

- [ ] **Step 3: Run the focused test**

Run:

```bash
cd /Users/anishpatel/Documents/SoftwareProject/trading_bot/backend
./mvnw -q -Dtest=FuturesBacktestLiveParityIntegrityTest#backtestParityPolicyDoesNotBlockOmomForLiveEntryDecay test
```

Expected: PASS.

---

### Task 3: Apply Backtest Parity Policy in Live Validation

**Files:**
- Modify: `trading_bot/backend/src/main/java/com/tradingbot/FuturesManager.java`

- [ ] **Step 1: Use the active policy in `validateLivePortfolioSignal`**

Change:

```java
String liveDecayReject = liveEntryDecayRejectReason(context, event, liveExecutionBar);
```

to:

```java
String validationPolicy = activeLivePortfolioValidationPolicy(session, null);
String liveDecayReject = liveEntryDecayRejectReason(context, event, liveExecutionBar, validationPolicy);
```

- [ ] **Step 2: Preserve decay diagnostics without blocking in parity mode**

After building `liveExecutionBar`, compute the strict decay reason for diagnostics:

```java
String liveDecayWarning = liveEntryDecayRejectReason(context, event, liveExecutionBar, PORTFOLIO_VALIDATION_LIVE_STRICT);
```

Include `liveDecayWarning` and `validationPolicy` in accepted/rejected payloads by extending `liveSizingDiagnosticsJson` or wrapping the existing diagnostics JSON at the insert point.

- [ ] **Step 3: Decide whether sizing uses planned bar or executable bar**

For strict backtest parity, keep `candidate.entryBar` as the planned backtest entry bar for risk/sizing. Do not assign `candidate.entryBar = liveExecutionBar` in `BACKTEST_PARITY`.

Use:

```java
if (!PORTFOLIO_VALIDATION_BACKTEST_PARITY.equals(validationPolicy)) {
	candidate.entryBar = liveExecutionBar;
}
```

- [ ] **Step 4: Run focused parity tests**

Run:

```bash
cd /Users/anishpatel/Documents/SoftwareProject/trading_bot/backend
./mvnw -q -Dtest=FuturesBacktestLiveParityIntegrityTest test
```

Expected: PASS.

---

### Task 4: Lock Risk Compression Parity

**Files:**
- Modify: `trading_bot/backend/src/test/java/com/tradingbot/FuturesBacktestLiveParityIntegrityTest.java`
- Modify: `trading_bot/backend/src/main/java/com/tradingbot/FuturesManager.java` only if the test exposes mismatch.

- [ ] **Step 1: Replace ambiguous ORB tests with policy-parity tests**

Use this test shape:

```java
@Test
public void riskCompressionPolicyMatchesBetweenLiveAndBacktest() throws Exception {
	assertEquals(portfolioBacktestAllowsLiveRiskCompression("ORB"), portfolioRiskCompressionAllowed("ORB"));
	assertEquals(portfolioBacktestAllowsLiveRiskCompression("ORB2"), portfolioRiskCompressionAllowed("ORB2"));
	assertEquals(portfolioBacktestAllowsLiveRiskCompression("OMOM"), portfolioRiskCompressionAllowed("OMOM"));
	assertEquals(portfolioBacktestAllowsLiveRiskCompression("PDB"), portfolioRiskCompressionAllowed("PDB"));
}
```

- [ ] **Step 2: Choose one source of truth**

If the selected portfolio backtest currently rejects oversized ORB risk, live parity must reject it too. Keep:

```java
private static boolean portfolioRiskCompressionAllowed(String strategyCode) {
	return false;
}
```

If the selected portfolio backtest is intentionally changed to compress ORB, change both live and backtest together:

```java
private static boolean portfolioRiskCompressionAllowed(String strategyCode) {
	return "ORB".equals(cleanOrDefault(strategyCode, "").toUpperCase(Locale.US));
}
```

Do not allow live-only compression.

- [ ] **Step 3: Run focused tests**

Run:

```bash
cd /Users/anishpatel/Documents/SoftwareProject/trading_bot/backend
./mvnw -q -Dtest=FuturesBacktestLiveParityIntegrityTest test
```

Expected: PASS.

---

### Task 5: Add Live Decision Replay Verification

**Files:**
- Modify: `trading_bot/backend/src/test/java/com/tradingbot/FuturesBacktestLiveParityIntegrityTest.java`

- [ ] **Step 1: Add a regression test for decision-54 geometry**

The test should assert:

```java
assertEquals("", liveEntryDecayRejectReason(context, event, executionBar, "BACKTEST_PARITY"));
assertTrue(liveEntryDecayRejectReason(context, event, executionBar, "LIVE_STRICT").contains("consumed too much reward"));
```

- [ ] **Step 2: Add a test proving planned entry sizing accepts the OMOM geometry**

Call `openPortfolioPosition` reflectively with:

```java
entryBar.open = 7430.50;
signal.stopPrice = 7437.75;
signal.targetPrice = 7426.51;
riskBudget = 700.0;
aggregateRoom = 50;
aggregateGuardBudget = 1000.0;
allowLiveRiskCompression = false;
```

Expected: position is not null, `rawRiskTicks` is near `29.0`, and contracts are at least `1`.

- [ ] **Step 3: Run focused tests**

Run:

```bash
cd /Users/anishpatel/Documents/SoftwareProject/trading_bot/backend
./mvnw -q -Dtest=FuturesBacktestLiveParityIntegrityTest test
```

Expected: PASS.

---

### Task 6: Verification Before Promotion

**Files:**
- No additional source files.

- [ ] **Step 1: Compile**

Run:

```bash
cd /Users/anishpatel/Documents/SoftwareProject/trading_bot/backend
./mvnw -q -DskipTests compile
```

Expected: PASS.

- [ ] **Step 2: Run focused backend tests**

Run:

```bash
cd /Users/anishpatel/Documents/SoftwareProject/trading_bot/backend
./mvnw -q -Dtest=FuturesBacktestLiveParityIntegrityTest,FuturesLivePipelineSelfTest test
```

Expected: PASS.

- [ ] **Step 3: Run a live-style replay check against captured bars**

Use the existing same-day captured bars from `shared_runtime/db/tradingbot.db` in dev only. The expected result for decision `54` after this fix is:

```text
strategyCode=OMOM
status no longer rejected by LIVE_ENTRY_DECAY under BACKTEST_PARITY
entry decay appears as diagnostic warning only
normal account/risk/broker gates still execute
```

- [ ] **Step 4: Package before any promotion handoff**

Run:

```bash
cd /Users/anishpatel/Documents/SoftwareProject/trading_bot/backend
./mvnw -q clean package
```

Expected: PASS.

---

## Self-Review

- Spec coverage: The plan addresses the user goal by making live submit/no-submit decisions follow portfolio backtest validation instead of a live-only decay gate.
- Current OMOM block coverage: The exact decision-54 geometry is captured in tests and expected behavior.
- ORB/risk coverage: Risk compression is made explicitly identical between live and portfolio backtest; no live-only ORB exception remains unless both sides opt into it.
- Safety boundaries: Broker exposure, duplicate/correlation checks, account drawdown, daily loss, aggregate limits, and per-strategy limits stay active.
- Live runtime: No live DB, source, jar, or process should be modified while implementing this plan. Promotion requires a separate explicit user request.
