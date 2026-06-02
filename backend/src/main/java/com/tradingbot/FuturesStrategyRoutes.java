package com.tradingbot;

import io.javalin.Javalin;
import io.javalin.http.Context;

final class FuturesStrategyRoutes {
    private FuturesStrategyRoutes() {
    }

    static void register(Javalin app) {
        app.get("/api/futures/strategy", ctx -> {
            String symbol = ApiRequestUtils.valueOrDefault(ctx.queryParam("symbol"), "MNQ");
            String preset = ctx.queryParam("preset");
            String slot = preset == null ? ApiRequestUtils.valueOrDefault(ctx.queryParam("slot"), "BACKTEST") : FuturesManager.strategyPresetSlot(preset);
            ctx.contentType("application/json").result(FuturesManager.getFuturesStrategySettingsJson(symbol, slot));
        });

        app.get("/api/futures/strategy-presets", ctx -> {
            ctx.contentType("application/json").result(FuturesManager.getStrategyPresetsJson());
        });

        app.get("/api/futures/strategy-window-policy", ctx -> {
            ctx.contentType("application/json").result(FuturesManager.getStrategyWindowPolicyJson());
        });

        app.post("/api/futures/strategy-presets", ctx -> {
            String preset = ApiRequestUtils.valueOrDefault(ctx.queryParam("preset"), "");
            String sourcePreset = ApiRequestUtils.valueOrDefault(ctx.queryParam("sourcePreset"), "backtestbias92k");
            String result = FuturesManager.createStrategyPreset(preset, sourcePreset);
            if (result.contains("\"success\":false")) {
                ctx.status(400);
            }
            ctx.contentType("application/json").result(result);
        });

        app.post("/api/futures/strategy", ctx -> {
            String symbol = ApiRequestUtils.valueOrDefault(ctx.queryParam("symbol"), "MNQ");
            String preset = ctx.queryParam("preset");
            String slot = preset == null ? ApiRequestUtils.valueOrDefault(ctx.queryParam("slot"), "BACKTEST") : FuturesManager.strategyPresetSlot(preset);
            if ("LIVE".equalsIgnoreCase(slot)) {
                ctx.status(400).contentType("application/json").result("{\"message\":\"Live Strategy slot is legacy read-only. Save a named strategy preset instead.\"}");
                return;
            }
            if (preset != null && ("94k".equalsIgnoreCase(preset.trim()) || "backtestbias92k".equalsIgnoreCase(preset.trim()) || "backtestwindows94k".equalsIgnoreCase(preset.trim()))) {
                ctx.status(400).contentType("application/json").result("{\"message\":\"backtestbias92k is the frozen Strategy Config. Save edits to biasfree92k or bestbiasfree.\"}");
                return;
            }
            FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, slot);
            applyStrategySettings(ctx, settings);

            if (FuturesManager.saveFuturesStrategySettings(symbol, slot, settings)) {
                ctx.contentType("application/json").result(FuturesManager.getFuturesStrategySettingsJson(symbol, slot));
            } else {
                ctx.status(500).contentType("application/json").result("{\"message\":\"Failed to save futures strategy settings.\"}");
            }
        });

        app.post("/api/futures/strategy-configs/copy-to-live", ctx -> {
            ctx.status(410).contentType("application/json").result("{\"success\":false,\"message\":\"Legacy copy-to-live is disabled. Save or select a named Strategy Config preset instead.\"}");
        });

        app.get("/api/futures/strategy/lab", ctx -> {
            String json = FuturesManager.getStrategyLabJson(
                ctx.queryParam("symbols"),
                ctx.queryParam("startDate"),
                ctx.queryParam("endDate"),
                ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("accountSize"), 50000.0),
                ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("maxTrailingDrawdown"), 2500.0),
                ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("dailyLossLimit"), 500.0),
                ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("maxRiskPerTrade"), 400.0),
                ApiRequestUtils.parseIntOrDefault(ctx.queryParam("maxContracts"), 12),
                ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("commissionPerContract"), 1.24),
                ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("slippageTicks"), 1.0),
                ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("useSavedRisk"), false)
            );
            ctx.contentType("application/json").result(json);
        });

        app.get("/api/futures/strategy/diagnostics", ctx -> {
            String json = FuturesManager.getStrategyDiagnosticsJson(
                ctx.queryParam("symbol"),
                ctx.queryParam("startDate"),
                ctx.queryParam("endDate"),
                ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("accountSize"), 50000.0),
                ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("maxTrailingDrawdown"), 2500.0),
                ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("dailyLossLimit"), 500.0),
                ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("maxRiskPerTrade"), 400.0),
                ApiRequestUtils.parseIntOrDefault(ctx.queryParam("maxContracts"), 12),
                ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("commissionPerContract"), 1.24),
                ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("slippageTicks"), 1.0),
                ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("useSavedRisk"), false)
            );
            ctx.contentType("application/json").result(json);
        });

        app.get("/api/futures/execution-options", ctx -> {
            ctx.contentType("application/json").result(FuturesManager.getExecutionOptionsJson());
        });
    }

    private static void applyStrategySettings(Context ctx, FuturesManager.FuturesStrategySettings settings) {
        settings.orb.enabled = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("orbEnabled"), settings.orb.enabled);
        settings.orb.maxTradesPerDay = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("orbMaxTradesPerDay"), settings.orb.maxTradesPerDay);
        settings.openingMomentum.enabled = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("openingMomentumEnabled"), settings.openingMomentum.enabled);
        settings.openingMomentum.maxTradesPerDay = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("openingMomentumMaxTradesPerDay"), settings.openingMomentum.maxTradesPerDay);
        settings.sweep.enabled = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("sweepEnabled"), settings.sweep.enabled);
        settings.sweep.maxTradesPerDay = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("sweepMaxTradesPerDay"), settings.sweep.maxTradesPerDay);
        settings.vwapPullback.enabled = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("vwapPullbackEnabled"), settings.vwapPullback.enabled);
        settings.vwapPullback.maxTradesPerDay = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("vwapPullbackMaxTradesPerDay"), settings.vwapPullback.maxTradesPerDay);
        settings.vwapMeanReversion.enabled = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("vwapMeanReversionEnabled"), settings.vwapMeanReversion.enabled);
        settings.vwapMeanReversion.maxTradesPerDay = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("vwapMeanReversionMaxTradesPerDay"), settings.vwapMeanReversion.maxTradesPerDay);
        settings.fvg.enabled = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("fvgEnabled"), settings.fvg.enabled);
        settings.fvg.maxTradesPerDay = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("fvgMaxTradesPerDay"), settings.fvg.maxTradesPerDay);
        settings.ifvg.enabled = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("ifvgEnabled"), settings.ifvg.enabled);
        settings.ifvg.maxTradesPerDay = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("ifvgMaxTradesPerDay"), settings.ifvg.maxTradesPerDay);
        settings.closeMomentum.enabled = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("closeMomentumEnabled"), settings.closeMomentum.enabled);
        settings.closeMomentum.maxTradesPerDay = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("closeMomentumMaxTradesPerDay"), settings.closeMomentum.maxTradesPerDay);
        settings.afternoonContinuation.enabled = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("afternoonContinuationEnabled"), settings.afternoonContinuation.enabled);
        settings.afternoonContinuation.maxTradesPerDay = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("afternoonContinuationMaxTradesPerDay"), settings.afternoonContinuation.maxTradesPerDay);
        settings.marketIntradayMomentum.enabled = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("marketIntradayMomentumEnabled"), settings.marketIntradayMomentum.enabled);
        settings.marketIntradayMomentum.maxTradesPerDay = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("marketIntradayMomentumMaxTradesPerDay"), settings.marketIntradayMomentum.maxTradesPerDay);
        settings.keltnerScalp.enabled = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("keltnerScalpEnabled"), settings.keltnerScalp.enabled);
        settings.keltnerScalp.maxTradesPerDay = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("keltnerScalpMaxTradesPerDay"), settings.keltnerScalp.maxTradesPerDay);
        settings.keltnerReversion.enabled = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("keltnerReversionEnabled"), settings.keltnerReversion.enabled);
        settings.keltnerReversion.maxTradesPerDay = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("keltnerReversionMaxTradesPerDay"), settings.keltnerReversion.maxTradesPerDay);
        settings.microScalp.enabled = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("microScalpEnabled"), settings.microScalp.enabled);
        settings.microScalp.maxTradesPerDay = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("microScalpMaxTradesPerDay"), settings.microScalp.maxTradesPerDay);
        settings.mclEiaContinuation.enabled = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("mclEiaContinuationEnabled"), settings.mclEiaContinuation.enabled);
        settings.mclEiaContinuation.maxTradesPerDay = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("mclEiaContinuationMaxTradesPerDay"), settings.mclEiaContinuation.maxTradesPerDay);
        settings.mclCrudeSessionOpen.enabled = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("mclCrudeSessionOpenEnabled"), settings.mclCrudeSessionOpen.enabled);
        settings.mclCrudeSessionOpen.maxTradesPerDay = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("mclCrudeSessionOpenMaxTradesPerDay"), settings.mclCrudeSessionOpen.maxTradesPerDay);
        settings.mymIndexConfirmation.enabled = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("mymIndexConfirmationEnabled"), settings.mymIndexConfirmation.enabled);
        settings.mymIndexConfirmation.maxTradesPerDay = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("mymIndexConfirmationMaxTradesPerDay"), settings.mymIndexConfirmation.maxTradesPerDay);
        settings.mymOrbRetest.enabled = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("mymOrbRetestEnabled"), settings.mymOrbRetest.enabled);
        settings.mymOrbRetest.maxTradesPerDay = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("mymOrbRetestMaxTradesPerDay"), settings.mymOrbRetest.maxTradesPerDay);
        settings.mymBreadthConfirmation.enabled = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("mymBreadthConfirmationEnabled"), settings.mymBreadthConfirmation.enabled);
        settings.mymBreadthConfirmation.maxTradesPerDay = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("mymBreadthConfirmationMaxTradesPerDay"), settings.mymBreadthConfirmation.maxTradesPerDay);
        settings.mclTrendContinuation.enabled = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("mclTrendContinuationEnabled"), settings.mclTrendContinuation.enabled);
        settings.mclTrendContinuation.maxTradesPerDay = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("mclTrendContinuationMaxTradesPerDay"), settings.mclTrendContinuation.maxTradesPerDay);
        settings.liquidityReclaim.enabled = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("liquidityReclaimEnabled"), settings.liquidityReclaim.enabled);
        settings.liquidityReclaim.maxTradesPerDay = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("liquidityReclaimMaxTradesPerDay"), settings.liquidityReclaim.maxTradesPerDay);
        settings.liquidityReclaimSourceCodes = ApiRequestUtils.valueOrDefault(ctx.queryParam("liquidityReclaimSourceCodes"), settings.liquidityReclaimSourceCodes);
        settings.liquidityReclaimStartMinute = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("liquidityReclaimStartMinute"), settings.liquidityReclaimStartMinute);
        settings.liquidityReclaimEndMinute = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("liquidityReclaimEndMinute"), settings.liquidityReclaimEndMinute);
        settings.liquidityReclaimAllowDuplicates = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("liquidityReclaimAllowDuplicates"), settings.liquidityReclaimAllowDuplicates);
        settings.liquidityReclaimMaxContracts = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("liquidityReclaimMaxContracts"), settings.liquidityReclaimMaxContracts);
        settings.enableEarlySweep = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("enableEarlySweep"), settings.enableEarlySweep);
        settings.enableLateSweep = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("enableLateSweep"), settings.enableLateSweep);
        settings.enableSweepSecondChance = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("enableSweepSecondChance"), settings.enableSweepSecondChance);
        settings.enableOrbRetest = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("enableOrbRetest"), settings.enableOrbRetest);
        settings.allowOrbRetestLongs = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("allowOrbRetestLongs"), settings.allowOrbRetestLongs);
        settings.allowOrbRetestShorts = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("allowOrbRetestShorts"), settings.allowOrbRetestShorts);
        settings.orbRetestStartMinutes = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("orbRetestStartMinutes"), settings.orbRetestStartMinutes);
        settings.orbRetestEndMinutes = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("orbRetestEndMinutes"), settings.orbRetestEndMinutes);
        settings.orbBreakoutEndMinute = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("orbBreakoutEndMinute"), settings.orbBreakoutEndMinute);
        settings.orbShortConfirmationMinute = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("orbShortConfirmationMinute"), settings.orbShortConfirmationMinute);
        settings.enableCompressedOrbBreakout = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("enableCompressedOrbBreakout"), settings.enableCompressedOrbBreakout);
        settings.skipMidmorningOrbRetest = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("skipMidmorningOrbRetest"), settings.skipMidmorningOrbRetest);
        settings.requireHigherTimeframeGuard = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("requireHigherTimeframeGuard"), settings.requireHigherTimeframeGuard);
        settings.relaxPatternHardWindows = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("relaxPatternHardWindows"), settings.relaxPatternHardWindows);
        settings.allowShorts = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("allowShorts"), settings.allowShorts);
        settings.openingMomentumRangeMinutes = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("openingMomentumRangeMinutes"), settings.openingMomentumRangeMinutes);
        settings.openingMomentumMaxHoldBars = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("openingMomentumMaxHoldBars"), settings.openingMomentumMaxHoldBars);
        settings.openingMomentumVolumeRatio = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("openingMomentumVolumeRatio"), settings.openingMomentumVolumeRatio);
        settings.openingMomentumRewardRisk = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("openingMomentumRewardRisk"), settings.openingMomentumRewardRisk);
        settings.earlySweepReclaimTicks = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("earlySweepReclaimTicks"), settings.earlySweepReclaimTicks);
        settings.lateSweepReclaimTicks = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("lateSweepReclaimTicks"), settings.lateSweepReclaimTicks);
        settings.sweepCloseLocation = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("sweepCloseLocation"), settings.sweepCloseLocation);
        settings.lateSweepCloseLocation = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("lateSweepCloseLocation"), settings.lateSweepCloseLocation);
        settings.minBodyPct = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("minBodyPct"), settings.minBodyPct);
        settings.vwapMinVolumeRatio = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("vwapMinVolumeRatio"), settings.vwapMinVolumeRatio);
        settings.vwapMinTrendSlopeTicks = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("vwapMinTrendSlopeTicks"), settings.vwapMinTrendSlopeTicks);
        settings.vwapMaxDistanceTicks = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("vwapMaxDistanceTicks"), settings.vwapMaxDistanceTicks);
        settings.vwapMaxRiskTicks = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("vwapMaxRiskTicks"), settings.vwapMaxRiskTicks);
        settings.vwapRequireHigherTimeframeGuard = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("vwapRequireHigherTimeframeGuard"), settings.vwapRequireHigherTimeframeGuard);
        settings.fvgRequireCoreQuality = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("fvgRequireCoreQuality"), settings.fvgRequireCoreQuality);
        settings.fvgRequireEmaStack = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("fvgRequireEmaStack"), settings.fvgRequireEmaStack);
        settings.fvgRequireHigherTimeframeGuard = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("fvgRequireHigherTimeframeGuard"), settings.fvgRequireHigherTimeframeGuard);
        settings.fvgTradeInversions = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("fvgTradeInversions"), settings.fvgTradeInversions);
        settings.fvgRequireInversionStructureBreak = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("fvgRequireInversionStructureBreak"), settings.fvgRequireInversionStructureBreak);
        settings.fvgInversionBreakBars = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("fvgInversionBreakBars"), settings.fvgInversionBreakBars);
        settings.fvgInversionStructureBars = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("fvgInversionStructureBars"), settings.fvgInversionStructureBars);
        settings.fvgMinInversionBodyPct = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("fvgMinInversionBodyPct"), settings.fvgMinInversionBodyPct);
        settings.fvgMinImpulseBodyPct = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("fvgMinImpulseBodyPct"), settings.fvgMinImpulseBodyPct);
        settings.fvgMinTrendSlopeTicks = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("fvgMinTrendSlopeTicks"), settings.fvgMinTrendSlopeTicks);
        settings.fvgMaxVwapDistanceTicks = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("fvgMaxVwapDistanceTicks"), settings.fvgMaxVwapDistanceTicks);
        settings.fvgMaxEntryExtensionTicks = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("fvgMaxEntryExtensionTicks"), settings.fvgMaxEntryExtensionTicks);
        String fvgSourceMode = ctx.queryParam("fvgSourceMode");
        if (fvgSourceMode != null && fvgSourceMode.trim().length() > 0) {
            settings.fvgSourceMode = fvgSourceMode;
        }
        settings.fvgSourceRangeBars = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("fvgSourceRangeBars"), settings.fvgSourceRangeBars);
        settings.fvgMinSourceBreakTicks = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("fvgMinSourceBreakTicks"), settings.fvgMinSourceBreakTicks);
        settings.meanReversionMinDistanceTicks = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("meanReversionMinDistanceTicks"), settings.meanReversionMinDistanceTicks);
        settings.meanReversionOversoldRsi = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("meanReversionOversoldRsi"), settings.meanReversionOversoldRsi);
        settings.meanReversionOverboughtRsi = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("meanReversionOverboughtRsi"), settings.meanReversionOverboughtRsi);
        settings.minRewardRisk = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("minRewardRisk"), settings.minRewardRisk);
        settings.allowCloseMomentumLongs = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("allowCloseMomentumLongs"), settings.allowCloseMomentumLongs);
        settings.allowCloseMomentumShorts = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("allowCloseMomentumShorts"), settings.allowCloseMomentumShorts);
        settings.closeMomentumMinMoveTicks = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("closeMomentumMinMoveTicks"), settings.closeMomentumMinMoveTicks);
        settings.closeMomentumVolumeRatio = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("closeMomentumVolumeRatio"), settings.closeMomentumVolumeRatio);
        settings.closeMomentumRewardRisk = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("closeMomentumRewardRisk"), settings.closeMomentumRewardRisk);
        settings.orbCompressedMaxRiskTicks = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("orbCompressedMaxRiskTicks"), settings.orbCompressedMaxRiskTicks);
        settings.orbRetestMaxRiskTicks = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("orbRetestMaxRiskTicks"), settings.orbRetestMaxRiskTicks);
        settings.afternoonMinVolumeRatio = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("afternoonMinVolumeRatio"), settings.afternoonMinVolumeRatio);
        settings.afternoonMaxRiskTicks = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("afternoonMaxRiskTicks"), settings.afternoonMaxRiskTicks);
        settings.afternoonRewardRisk = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("afternoonRewardRisk"), settings.afternoonRewardRisk);
        settings.marketIntradayMomentumMinOpenMoveTicks = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("marketIntradayMomentumMinOpenMoveTicks"), settings.marketIntradayMomentumMinOpenMoveTicks);
        settings.marketIntradayMomentumMinLateMoveTicks = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("marketIntradayMomentumMinLateMoveTicks"), settings.marketIntradayMomentumMinLateMoveTicks);
        settings.marketIntradayMomentumMinVolumeRatio = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("marketIntradayMomentumMinVolumeRatio"), settings.marketIntradayMomentumMinVolumeRatio);
        settings.marketIntradayMomentumMaxRiskTicks = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("marketIntradayMomentumMaxRiskTicks"), settings.marketIntradayMomentumMaxRiskTicks);
        settings.marketIntradayMomentumRewardRisk = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("marketIntradayMomentumRewardRisk"), settings.marketIntradayMomentumRewardRisk);
        settings.allowKeltnerScalpLongs = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("allowKeltnerScalpLongs"), settings.allowKeltnerScalpLongs);
        settings.allowKeltnerScalpShorts = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("allowKeltnerScalpShorts"), settings.allowKeltnerScalpShorts);
        settings.keltnerAtrMultiplier = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("keltnerAtrMultiplier"), settings.keltnerAtrMultiplier);
        settings.keltnerMinVolumeRatio = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("keltnerMinVolumeRatio"), settings.keltnerMinVolumeRatio);
        settings.keltnerMaxRiskTicks = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("keltnerMaxRiskTicks"), settings.keltnerMaxRiskTicks);
        settings.keltnerRewardRisk = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("keltnerRewardRisk"), settings.keltnerRewardRisk);
        settings.keltnerMinBodyPct = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("keltnerMinBodyPct"), settings.keltnerMinBodyPct);
        settings.keltnerMinTrendSlopeTicks = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("keltnerMinTrendSlopeTicks"), settings.keltnerMinTrendSlopeTicks);
        settings.keltnerMinBandWidthTicks = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("keltnerMinBandWidthTicks"), settings.keltnerMinBandWidthTicks);
        settings.keltnerMaxHoldBars = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("keltnerMaxHoldBars"), settings.keltnerMaxHoldBars);
        settings.keltnerBucketMinutes = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("keltnerBucketMinutes"), settings.keltnerBucketMinutes);
        settings.microScalpMinVolumeRatio = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("microScalpMinVolumeRatio"), settings.microScalpMinVolumeRatio);
        settings.microScalpMaxRiskTicks = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("microScalpMaxRiskTicks"), settings.microScalpMaxRiskTicks);
        settings.microScalpRewardRisk = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("microScalpRewardRisk"), settings.microScalpRewardRisk);
        settings.microScalpMinBodyPct = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("microScalpMinBodyPct"), settings.microScalpMinBodyPct);
        settings.microScalpMinTrendSlopeTicks = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("microScalpMinTrendSlopeTicks"), settings.microScalpMinTrendSlopeTicks);
        settings.microScalpMaxHoldBars = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("microScalpMaxHoldBars"), settings.microScalpMaxHoldBars);
        settings.microScalpBucketMinutes = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("microScalpBucketMinutes"), settings.microScalpBucketMinutes);
        settings.allowMclEiaLongs = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("allowMclEiaLongs"), settings.allowMclEiaLongs);
        settings.allowMclEiaShorts = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("allowMclEiaShorts"), settings.allowMclEiaShorts);
        settings.mclEiaRangeStartMinute = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("mclEiaRangeStartMinute"), settings.mclEiaRangeStartMinute);
        settings.mclEiaRangeEndMinute = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("mclEiaRangeEndMinute"), settings.mclEiaRangeEndMinute);
        settings.mclEiaStartMinute = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("mclEiaStartMinute"), settings.mclEiaStartMinute);
        settings.mclEiaEndMinute = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("mclEiaEndMinute"), settings.mclEiaEndMinute);
        settings.mclEiaBreakoutBufferTicks = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("mclEiaBreakoutBufferTicks"), settings.mclEiaBreakoutBufferTicks);
        settings.mclEiaStopTicks = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("mclEiaStopTicks"), settings.mclEiaStopTicks);
        settings.mclEiaRewardRisk = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("mclEiaRewardRisk"), settings.mclEiaRewardRisk);
        settings.mclEiaMinVolumeRatio = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("mclEiaMinVolumeRatio"), settings.mclEiaMinVolumeRatio);
        settings.mclEiaMinBodyPct = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("mclEiaMinBodyPct"), settings.mclEiaMinBodyPct);
        settings.mclEiaMaxHoldBars = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("mclEiaMaxHoldBars"), settings.mclEiaMaxHoldBars);
        settings.allowMclCrudeOpenLongs = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("allowMclCrudeOpenLongs"), settings.allowMclCrudeOpenLongs);
        settings.allowMclCrudeOpenShorts = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("allowMclCrudeOpenShorts"), settings.allowMclCrudeOpenShorts);
        settings.mclCrudeOpenRangeStartMinute = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("mclCrudeOpenRangeStartMinute"), settings.mclCrudeOpenRangeStartMinute);
        settings.mclCrudeOpenRangeEndMinute = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("mclCrudeOpenRangeEndMinute"), settings.mclCrudeOpenRangeEndMinute);
        settings.mclCrudeOpenStartMinute = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("mclCrudeOpenStartMinute"), settings.mclCrudeOpenStartMinute);
        settings.mclCrudeOpenEndMinute = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("mclCrudeOpenEndMinute"), settings.mclCrudeOpenEndMinute);
        settings.mclCrudeOpenBreakoutBufferTicks = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("mclCrudeOpenBreakoutBufferTicks"), settings.mclCrudeOpenBreakoutBufferTicks);
        settings.mclCrudeOpenStopTicks = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("mclCrudeOpenStopTicks"), settings.mclCrudeOpenStopTicks);
        settings.mclCrudeOpenRewardRisk = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("mclCrudeOpenRewardRisk"), settings.mclCrudeOpenRewardRisk);
        settings.mclCrudeOpenMinVolumeRatio = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("mclCrudeOpenMinVolumeRatio"), settings.mclCrudeOpenMinVolumeRatio);
        settings.mclCrudeOpenMinBodyPct = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("mclCrudeOpenMinBodyPct"), settings.mclCrudeOpenMinBodyPct);
        settings.mclCrudeOpenMaxHoldBars = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("mclCrudeOpenMaxHoldBars"), settings.mclCrudeOpenMaxHoldBars);
        settings.allowMymIndexConfirmationLongs = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("allowMymIndexConfirmationLongs"), settings.allowMymIndexConfirmationLongs);
        settings.allowMymIndexConfirmationShorts = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("allowMymIndexConfirmationShorts"), settings.allowMymIndexConfirmationShorts);
        settings.mymIndexConfirmationStartMinute = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("mymIndexConfirmationStartMinute"), settings.mymIndexConfirmationStartMinute);
        settings.mymIndexConfirmationEndMinute = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("mymIndexConfirmationEndMinute"), settings.mymIndexConfirmationEndMinute);
        settings.mymIndexConfirmationBucketMinutes = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("mymIndexConfirmationBucketMinutes"), settings.mymIndexConfirmationBucketMinutes);
        settings.mymIndexConfirmationLookbackBars = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("mymIndexConfirmationLookbackBars"), settings.mymIndexConfirmationLookbackBars);
        settings.mymIndexConfirmationMaxRiskTicks = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("mymIndexConfirmationMaxRiskTicks"), settings.mymIndexConfirmationMaxRiskTicks);
        settings.mymIndexConfirmationRewardRisk = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("mymIndexConfirmationRewardRisk"), settings.mymIndexConfirmationRewardRisk);
        settings.mymIndexConfirmationMinVolumeRatio = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("mymIndexConfirmationMinVolumeRatio"), settings.mymIndexConfirmationMinVolumeRatio);
        settings.mymIndexConfirmationMinBodyPct = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("mymIndexConfirmationMinBodyPct"), settings.mymIndexConfirmationMinBodyPct);
        settings.mymIndexConfirmationMinTrendSlopeTicks = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("mymIndexConfirmationMinTrendSlopeTicks"), settings.mymIndexConfirmationMinTrendSlopeTicks);
        settings.mymIndexConfirmationMaxHoldBars = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("mymIndexConfirmationMaxHoldBars"), settings.mymIndexConfirmationMaxHoldBars);
        settings.allowMymOrbRetestLongs = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("allowMymOrbRetestLongs"), settings.allowMymOrbRetestLongs);
        settings.allowMymOrbRetestShorts = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("allowMymOrbRetestShorts"), settings.allowMymOrbRetestShorts);
        settings.mymOrbRetestStartMinute = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("mymOrbRetestStartMinute"), settings.mymOrbRetestStartMinute);
        settings.mymOrbRetestEndMinute = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("mymOrbRetestEndMinute"), settings.mymOrbRetestEndMinute);
        settings.mymOrbRetestBreakoutBufferTicks = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("mymOrbRetestBreakoutBufferTicks"), settings.mymOrbRetestBreakoutBufferTicks);
        settings.mymOrbRetestRetestTicks = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("mymOrbRetestRetestTicks"), settings.mymOrbRetestRetestTicks);
        settings.mymOrbRetestMaxRiskTicks = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("mymOrbRetestMaxRiskTicks"), settings.mymOrbRetestMaxRiskTicks);
        settings.mymOrbRetestRewardRisk = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("mymOrbRetestRewardRisk"), settings.mymOrbRetestRewardRisk);
        settings.mymOrbRetestMinVolumeRatio = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("mymOrbRetestMinVolumeRatio"), settings.mymOrbRetestMinVolumeRatio);
        settings.mymOrbRetestMinBodyPct = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("mymOrbRetestMinBodyPct"), settings.mymOrbRetestMinBodyPct);
        settings.mymOrbRetestMaxHoldBars = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("mymOrbRetestMaxHoldBars"), settings.mymOrbRetestMaxHoldBars);
        settings.maxInitialRiskTicks = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("maxInitialRiskTicks"), settings.maxInitialRiskTicks);
        settings.enableAdaptiveExits = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("enableAdaptiveExits"), settings.enableAdaptiveExits);
        settings.adaptiveMinVolumeRatio = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("adaptiveMinVolumeRatio"), settings.adaptiveMinVolumeRatio);
        settings.adaptiveMinBodyPct = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("adaptiveMinBodyPct"), settings.adaptiveMinBodyPct);
        settings.adaptiveTrendTargetBoost = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("adaptiveTrendTargetBoost"), settings.adaptiveTrendTargetBoost);
        settings.adaptiveVolumeTargetBoost = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("adaptiveVolumeTargetBoost"), settings.adaptiveVolumeTargetBoost);
        settings.adaptiveBodyTargetBoost = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("adaptiveBodyTargetBoost"), settings.adaptiveBodyTargetBoost);
        settings.adaptiveMaxRewardRisk = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("adaptiveMaxRewardRisk"), settings.adaptiveMaxRewardRisk);
        settings.enableEarlyLossCut = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("enableEarlyLossCut"), settings.enableEarlyLossCut);
        settings.earlyLossCutBars = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("earlyLossCutBars"), settings.earlyLossCutBars);
        settings.earlyLossCutR = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("earlyLossCutR"), settings.earlyLossCutR);
        settings.earlyLossCutMinFavorableR = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("earlyLossCutMinFavorableR"), settings.earlyLossCutMinFavorableR);
        settings.managedStopBreakevenTriggerR = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("managedStopBreakevenTriggerR"), settings.managedStopBreakevenTriggerR);
        settings.managedStopTrailTriggerR = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("managedStopTrailTriggerR"), settings.managedStopTrailTriggerR);
        settings.managedStopTrailDistanceR = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("managedStopTrailDistanceR"), settings.managedStopTrailDistanceR);
        settings.managedStopMinTrailTicks = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("managedStopMinTrailTicks"), settings.managedStopMinTrailTicks);
        settings.enableManagedGivebackExit = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("enableManagedGivebackExit"), settings.enableManagedGivebackExit);
        settings.managedGivebackTriggerR = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("managedGivebackTriggerR"), settings.managedGivebackTriggerR);
        settings.managedGivebackR = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("managedGivebackR"), settings.managedGivebackR);
        settings.managedGivebackMinBars = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("managedGivebackMinBars"), settings.managedGivebackMinBars);
        settings.openMaeRiskMultiplier = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("openMaeRiskMultiplier"), settings.openMaeRiskMultiplier);
    }
}
