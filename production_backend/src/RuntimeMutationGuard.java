package com.tradingbot;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

final class RuntimeMutationGuard {
	private RuntimeMutationGuard() {
	}

	static Decision marketDataMutationAllowed(String operation) {
		if ("dev".equals(RuntimePaths.runtimeRole()) && RuntimePaths.usingSharedRuntime()) {
			return Decision.blocked("Dev runtime cannot run market-data imports against centralized shared runtime storage. Run imports from the live runtime only after the bot is stopped.");
		}
		return noActiveLiveTradingDecision(operation);
	}

	static Decision backtestMutationAllowed(String operation) {
		return noActiveLiveTradingDecision(operation);
	}

	static String blockedJson(Decision decision) {
		Decision safe = decision == null ? Decision.blocked("Runtime mutation blocked.") : decision;
		return "{"
			+ "\"success\":false,"
			+ "\"blocked\":true,"
			+ "\"message\":" + ApiRequestUtils.jsonString(safe.message)
			+ "}";
	}

	private static Decision noActiveLiveTradingDecision(String operation) {
		ActiveLiveState state = activeLiveState();
		if (state.active) {
			return Decision.blocked(
				"Blocked " + cleanOperation(operation) + " because live trading is active"
					+ " (runningSessions=" + state.runningSessions
					+ ", pendingOrders=" + state.pendingOrders + ")."
					+ " Stop the bot and verify it is flat before mutating centralized runtime data."
			);
		}
		return Decision.allowed();
	}

	private static ActiveLiveState activeLiveState() {
		ActiveLiveState state = new ActiveLiveState();
		try (Connection conn = DatabaseManager.getReadOnlyConnection();
			 Statement stmt = conn.createStatement()) {
			state.runningSessions = count(stmt,
				"SELECT COUNT(*) FROM ("
					+ "SELECT sessionID, status FROM FuturesLiveEngineSessions ORDER BY sessionID DESC LIMIT 1"
					+ ") latest WHERE UPPER(COALESCE(status, '')) = 'RUNNING' "
					+ "AND NOT EXISTS ("
					+ "SELECT 1 FROM FuturesLiveThinkingLog "
					+ "WHERE sessionID = latest.sessionID "
					+ "AND UPPER(COALESCE(eventType, '')) = 'BOT_STOPPED'"
					+ ")"
			);
			state.pendingOrders = count(stmt,
				"SELECT COUNT(*) FROM FuturesLiveOrderLedger "
					+ "WHERE UPPER(COALESCE(status, '')) = 'PENDING_BROKER_RECONCILE'"
			);
			state.active = state.runningSessions > 0 || state.pendingOrders > 0;
		} catch (SQLException ignored) {
			state.active = false;
		}
		return state;
	}

	private static int count(Statement stmt, String sql) throws SQLException {
		try (ResultSet rs = stmt.executeQuery(sql)) {
			return rs.next() ? rs.getInt(1) : 0;
		}
	}

	private static String cleanOperation(String operation) {
		String clean = operation == null ? "" : operation.trim();
		return clean.length() == 0 ? "runtime mutation" : clean;
	}

	static final class Decision {
		final boolean allowed;
		final String message;

		private Decision(boolean allowed, String message) {
			this.allowed = allowed;
			this.message = message == null ? "" : message;
		}

		static Decision allowed() {
			return new Decision(true, "");
		}

		static Decision blocked(String message) {
			return new Decision(false, message);
		}
	}

	private static final class ActiveLiveState {
		private boolean active;
		private int runningSessions;
		private int pendingOrders;
	}
}
