package com.tradingbot;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class AlpacaManager {

	private static final String BASE_URL = "https://paper-api.alpaca.markets/v2";
	private static final String MARKET_DATA_URL = "https://data.alpaca.markets/v2";
	private static final String[] SUPPORTED_SYMBOLS = {"SPY", "QQQ", "AAPL", "NVDA", "TSLA"};
	private static final String[] SUPPORTED_TIMEFRAME_FOLDERS = {"1min", "5min", "30min", "1hour"};
	private static final String[] SUPPORTED_TIMEFRAME_VALUES = {"1Min", "5Min", "30Min", "1Hour"};
	private static final String DEFAULT_MARKET_DATA_FEED = "iex";
	private static final String DEFAULT_MARKET_DATA_ADJUSTMENT = "raw";
	private static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");
	private static final LocalTime MARKET_OPEN_TIME = LocalTime.of(9, 30);
	private static final LocalTime MARKET_CLOSE_TIME = LocalTime.of(16, 0);
	private static final DateTimeFormatter DISPLAY_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	private final String apiKey;
	private final String secretKey;

	public static class CachedBar {
		public final String displayTime;
		public final LocalDate marketDate;
		public final LocalTime marketTime;
		public final double open;
		public final double high;
		public final double low;
		public final double close;
		public final double volume;
		public final double vwap;
		public final int tradeCount;

		public CachedBar(String displayTime, LocalDate marketDate, LocalTime marketTime, double open, double high, double low, double close, double volume) {
			this(displayTime, marketDate, marketTime, open, high, low, close, volume, close, 0);
		}

		public CachedBar(String displayTime, LocalDate marketDate, LocalTime marketTime, double open, double high, double low, double close, double volume, double vwap, int tradeCount) {
			this.displayTime = displayTime;
			this.marketDate = marketDate;
			this.marketTime = marketTime;
			this.open = open;
			this.high = high;
			this.low = low;
			this.close = close;
			this.volume = volume;
			this.vwap = vwap > 0.0 ? vwap : close;
			this.tradeCount = Math.max(0, tradeCount);
		}
	}

	public static class AccountSnapshot {
		public boolean connected;
		public String accountName;
		public double cash;
		public double buyingPower;
		public double equity;
		public double portfolioValue;
	}

	public AlpacaManager(String apiKey, String secretKey) {
		this.apiKey = apiKey;
		this.secretKey = secretKey;
	}

	public String getAccountInfo() {
		try {
			URL url = new URL(BASE_URL + "/account");
			HttpURLConnection conn = (HttpURLConnection) url.openConnection();
			conn.setRequestMethod("GET");
			conn.setRequestProperty("accept", "application/json");
			conn.setRequestProperty("APCA-API-KEY-ID", apiKey);
			conn.setRequestProperty("APCA-API-SECRET-KEY", secretKey);

			int responseCode = conn.getResponseCode();
			BufferedReader in;

			if (responseCode == 200) {
				in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
			} else {
				in = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
			}

			String inputLine;
			StringBuilder response = new StringBuilder();
			while ((inputLine = in.readLine()) != null) {
				response.append(inputLine);
			}

			in.close();
			return response.toString();

		} catch (Exception e) {
			e.printStackTrace();
			return "Connection failed: " + e.getMessage();
		}
	}

	public String submitOrder(String symbol, int quantity, String side) {
		try {
			URL url = new URL(BASE_URL + "/orders");
			HttpURLConnection conn = (HttpURLConnection) url.openConnection();
			conn.setRequestMethod("POST");
			conn.setRequestProperty("APCA-API-KEY-ID", apiKey);
			conn.setRequestProperty("APCA-API-SECRET-KEY", secretKey);
			conn.setRequestProperty("Content-Type", "application/json");
			conn.setDoOutput(true);

			String jsonInputString = String.format(
				"{\"symbol\":\"%s\", \"qty\":\"%d\", \"side\":\"%s\", \"type\":\"market\", \"time_in_force\":\"day\"}",
				symbol, quantity, side
			);

			try (java.io.OutputStream os = conn.getOutputStream()) {
				byte[] input = jsonInputString.getBytes("utf-8");
				os.write(input, 0, input.length);
			}

			java.util.Scanner scanner = new java.util.Scanner(conn.getInputStream());
			String response = scanner.useDelimiter("\\A").next();
			scanner.close();
			return response;

		} catch (Exception e) {
			System.err.println("Trade Failed: " + e.getMessage());
			return "{\"error\": \"Trade failed\"}";
		}
	}

	public String getConnectedAccountName() {
		String accountInfo = getAccountInfo();

		if (accountInfo == null || !accountInfo.trim().startsWith("{")) {
			return "Unavailable";
		}

		String connectedAccountName = extractJsonValue(accountInfo, "account_name", "name", "account_number", "id");
		return connectedAccountName.isEmpty() ? "Unavailable" : connectedAccountName;
	}

	public AccountSnapshot getAccountSnapshot() {
		String accountInfo = getAccountInfo();
		AccountSnapshot snapshot = new AccountSnapshot();
		snapshot.accountName = extractJsonValue(accountInfo, "account_name", "name", "account_number", "id");
		snapshot.cash = parseJsonDouble(extractJsonValue(accountInfo, "cash"));
		snapshot.buyingPower = parseJsonDouble(extractJsonValue(accountInfo, "buying_power"));
		snapshot.equity = parseJsonDouble(extractJsonValue(accountInfo, "equity"));
		snapshot.portfolioValue = parseJsonDouble(extractJsonValue(accountInfo, "portfolio_value"));
		snapshot.connected = accountInfo != null
			&& accountInfo.trim().startsWith("{")
			&& (!snapshot.accountName.isEmpty()
				|| snapshot.cash > 0.0
				|| snapshot.buyingPower > 0.0
				|| snapshot.equity > 0.0
				|| snapshot.portfolioValue > 0.0);
		return snapshot;
	}

	public static String getBrokerName() {
		return "Alpaca";
	}

	public static String getBaseUrl() {
		return BASE_URL;
	}

	public static String[] getSupportedSymbols() {
		return SUPPORTED_SYMBOLS;
	}

	public static String normalizeSymbol(String symbol) {
		if (symbol == null || symbol.trim().isEmpty()) {
			return "SPY";
		}
		return symbol.trim().toUpperCase();
	}

	public boolean refreshHistoricalDataCache() {
		ensureMarketDataDirectory();

		LocalDate requestStart = LocalDate.now(ZoneOffset.UTC).minusYears(2);
		LocalDate requestEnd = LocalDate.now(ZoneOffset.UTC);
		String earliestDate = "";
		String latestDate = "";
		int totalBars = 0;
		String marketDataFeed = getConfiguredMarketDataFeed();
		String marketDataAdjustment = getConfiguredMarketDataAdjustment();
		Properties previousStatus = readMarketDataStatus();
		String previousFeed = previousStatus.getProperty("feed", "");
		String previousAdjustment = previousStatus.getProperty("adjustment", "");
		boolean rebuildCache = shouldRebuildMarketDataCache(previousFeed, marketDataFeed, DEFAULT_MARKET_DATA_FEED)
			|| shouldRebuildMarketDataCache(previousAdjustment, marketDataAdjustment, DEFAULT_MARKET_DATA_ADJUSTMENT);

		for (int timeframeIndex = 0; timeframeIndex < SUPPORTED_TIMEFRAME_FOLDERS.length; timeframeIndex++) {
				String timeframeFolder = SUPPORTED_TIMEFRAME_FOLDERS[timeframeIndex];
				String timeframeValue = SUPPORTED_TIMEFRAME_VALUES[timeframeIndex];

			for (String symbol : SUPPORTED_SYMBOLS) {
				List<String[]> existingBars = rebuildCache ? new ArrayList<String[]>() : readSymbolBars(timeframeFolder, symbol);
				List<String[]> mergedBars = existingBars;
				String existingStart = existingBars.isEmpty() ? "" : extractDate(existingBars.get(0)[0]);
				String existingEnd = existingBars.isEmpty() ? "" : extractDate(existingBars.get(existingBars.size() - 1)[0]);

				if (existingBars.isEmpty()) {
					List<String[]> fetchedBars = fetchHistoricalBars(symbol, requestStart.toString(), requestEnd.toString(), timeframeValue);
					if (fetchedBars.isEmpty()) {
						return false;
					}
					mergedBars = mergeBars(existingBars, fetchedBars);
				} else {
					if (!existingStart.isEmpty() && existingStart.compareTo(requestStart.toString()) > 0) {
						String backfillEnd = shiftDate(existingStart, -1);
						if (!backfillEnd.isEmpty() && requestStart.toString().compareTo(backfillEnd) <= 0) {
							List<String[]> fetchedBars = fetchHistoricalBars(symbol, requestStart.toString(), backfillEnd, timeframeValue);
							mergedBars = mergeBars(mergedBars, fetchedBars);
						}
					}

					if (!existingEnd.isEmpty() && existingEnd.compareTo(requestEnd.toString()) < 0) {
						String forwardStart = shiftDate(existingEnd, 1);
						if (!forwardStart.isEmpty() && forwardStart.compareTo(requestEnd.toString()) <= 0) {
							List<String[]> fetchedBars = fetchHistoricalBars(symbol, forwardStart, requestEnd.toString(), timeframeValue);
							mergedBars = mergeBars(mergedBars, fetchedBars);
						}
					}
				}

				if (mergedBars.isEmpty()) {
					return false;
				}

				writeSymbolBars(timeframeFolder, symbol, mergedBars);

				String symbolStartDate = extractDate(mergedBars.get(0)[0]);
				String symbolEndDate = extractDate(mergedBars.get(mergedBars.size() - 1)[0]);

				if (earliestDate.isEmpty() || symbolStartDate.compareTo(earliestDate) < 0) {
					earliestDate = symbolStartDate;
				}
				if (latestDate.isEmpty() || symbolEndDate.compareTo(latestDate) > 0) {
					latestDate = symbolEndDate;
				}
				totalBars += mergedBars.size();
			}
		}

		writeMarketDataStatus(earliestDate, latestDate, Instant.now().toString(), totalBars, marketDataFeed, marketDataAdjustment);
		return true;
	}

	public static String getMarketDataStatusJson() {
		ensureMarketDataDirectory();
		Properties status = readMarketDataStatus();
		String startDate = status.getProperty("startDate", "");
		String endDate = status.getProperty("endDate", "");
		String lastUpdatedAt = status.getProperty("lastUpdatedAt", "");
		int totalBars = parseInt(status.getProperty("totalBars", "0"));
		String feed = status.getProperty("feed", getConfiguredMarketDataFeed());
		String adjustment = status.getProperty("adjustment", getConfiguredMarketDataAdjustment());
		boolean hasData = !startDate.isEmpty() && !endDate.isEmpty();

		return "{"
			+ "\"symbols\":" + jsonArray(SUPPORTED_SYMBOLS) + ","
			+ "\"startDate\":" + jsonString(startDate) + ","
			+ "\"endDate\":" + jsonString(endDate) + ","
			+ "\"lastUpdatedAt\":" + jsonString(lastUpdatedAt) + ","
			+ "\"totalBars\":" + totalBars + ","
			+ "\"storagePath\":" + jsonString(marketDataDirectory().getPath()) + ","
			+ "\"fields\":" + jsonString("timestamp,open,high,low,close,volume,vwap,trade_count") + ","
			+ "\"feed\":" + jsonString(feed) + ","
			+ "\"adjustment\":" + jsonString(adjustment) + ","
			+ "\"hasData\":" + hasData
			+ "}";
	}

	public static List<CachedBar> loadCachedBars(String symbol, LocalDate startDate, LocalDate endDate) {
		return loadCachedBars(symbol, startDate, endDate, "1Min");
	}

	public static List<CachedBar> loadCachedBars(String symbol, LocalDate startDate, LocalDate endDate, String timeframe) {
		String normalizedSymbol = normalizeSymbol(symbol);
		LocalDate finalStartDate = startDate;
		LocalDate finalEndDate = endDate;
		if (finalStartDate != null && finalEndDate != null && finalEndDate.isBefore(finalStartDate)) {
			finalEndDate = finalStartDate;
		}
		return loadBars(normalizedSymbol, finalStartDate, finalEndDate, normalizeTimeframeFolder(timeframe));
	}

	public List<CachedBar> fetchBars(String symbol, ZonedDateTime startTime, ZonedDateTime endTime, String timeframe) {
		List<CachedBar> bars = new ArrayList<CachedBar>();
		if (startTime == null || endTime == null || endTime.isBefore(startTime)) {
			return bars;
		}

		String timeframeFolder = normalizeTimeframeFolder(timeframe);
		List<String[]> rawBars = fetchHistoricalBars(
			normalizeSymbol(symbol),
			formatApiDateTime(startTime),
			formatApiDateTime(endTime),
			normalizeTimeframeValue(timeframe)
		);
		LocalTime sessionStartTime = marketSessionStartTime(timeframeFolder);

		for (int index = 0; index < rawBars.size(); index++) {
			String[] rawBar = rawBars.get(index);
			if (rawBar == null || rawBar.length < 6) {
				continue;
			}

			CachedBar bar = toCachedBar(
				rawBar[0],
				rawBar[1],
				rawBar[2],
				rawBar[3],
				rawBar[4],
				rawBar[5],
				rawBar.length > 6 ? rawBar[6] : rawBar[4],
				rawBar.length > 7 ? rawBar[7] : "0"
			);
			if (bar == null || !isMarketSession(bar, sessionStartTime)) {
				continue;
			}
			bars.add(bar);
		}

		return bars;
	}

	private static List<CachedBar> loadBars(String symbol, LocalDate startDate, LocalDate endDate, String timeframeFolder) {
		List<CachedBar> bars = new ArrayList<CachedBar>();
		File csvFile = new File(timeframeDataDirectory(timeframeFolder), symbol + ".csv");

		if (!csvFile.exists()) {
			return bars;
		}

		LocalTime sessionStartTime = marketSessionStartTime(timeframeFolder);

		try (BufferedReader reader = new BufferedReader(new FileReader(csvFile))) {
			String line = reader.readLine();

			while ((line = reader.readLine()) != null) {
				CachedBar bar = parseCachedBar(line);
				if (bar == null) {
					continue;
				}
				if (startDate != null && bar.marketDate.isBefore(startDate)) {
					continue;
				}
				if (endDate != null && bar.marketDate.isAfter(endDate)) {
					break;
				}
				if (!isMarketSession(bar, sessionStartTime)) {
					continue;
				}
				bars.add(bar);
			}
		} catch (Exception e) {
			e.printStackTrace();
			return new ArrayList<CachedBar>();
		}

		return bars;
	}

	private List<String[]> readSymbolBars(String timeframeFolder, String symbol) {
		List<String[]> bars = new ArrayList<String[]>();
		File csvFile = new File(timeframeDataDirectory(timeframeFolder), symbol + ".csv");
		if (!csvFile.exists()) {
			return bars;
		}

		try (BufferedReader reader = new BufferedReader(new FileReader(csvFile))) {
			String line = reader.readLine();
			while ((line = reader.readLine()) != null) {
				String[] values = line.split(",");
				if (values.length < 6) {
					continue;
				}
				String timestamp = values[0] == null ? "" : values[0].trim();
				if (timestamp.isEmpty()) {
					continue;
				}
				String close = values[4].trim();
				bars.add(new String[] {
					timestamp,
					values[1].trim(),
					values[2].trim(),
					values[3].trim(),
					close,
					values[5].trim(),
					values.length > 6 ? values[6].trim() : close,
					values.length > 7 ? values[7].trim() : "0"
				});
			}
		} catch (Exception e) {
			e.printStackTrace();
			return new ArrayList<String[]>();
		}
		return bars;
	}

	private List<String[]> mergeBars(List<String[]> existingBars, List<String[]> incomingBars) {
		List<String[]> merged = new ArrayList<String[]>();
		int existingIndex = 0;
		int incomingIndex = 0;
		while (existingIndex < existingBars.size() || incomingIndex < incomingBars.size()) {
			String[] nextRow;
			if (existingIndex >= existingBars.size()) {
				nextRow = incomingBars.get(incomingIndex++);
			} else if (incomingIndex >= incomingBars.size()) {
				nextRow = existingBars.get(existingIndex++);
			} else {
				String existingTimestamp = existingBars.get(existingIndex)[0];
				String incomingTimestamp = incomingBars.get(incomingIndex)[0];
				int compare = existingTimestamp.compareTo(incomingTimestamp);
				if (compare < 0) {
					nextRow = existingBars.get(existingIndex++);
				} else if (compare > 0) {
					nextRow = incomingBars.get(incomingIndex++);
				} else {
					nextRow = incomingBars.get(incomingIndex++);
					existingIndex++;
				}
			}

			if (merged.isEmpty() || !merged.get(merged.size() - 1)[0].equals(nextRow[0])) {
				merged.add(nextRow);
			}
		}
		return merged;
	}

	private String shiftDate(String date, int days) {
		try {
			return LocalDate.parse(date).plusDays(days).toString();
		} catch (DateTimeParseException e) {
			return "";
		}
	}

	private static CachedBar parseCachedBar(String line) {
		String[] values = line.split(",");
		if (values.length < 6) {
			return null;
		}

		String close = values[4].trim();
		return toCachedBar(
			values[0].trim(),
			values[1],
			values[2],
			values[3],
			close,
			values[5],
			values.length > 6 ? values[6] : close,
			values.length > 7 ? values[7] : "0"
		);
	}

	private static CachedBar toCachedBar(String timestamp, String open, String high, String low, String close, String volume) {
		return toCachedBar(timestamp, open, high, low, close, volume, close, "0");
	}

	private static CachedBar toCachedBar(String timestamp, String open, String high, String low, String close, String volume, String vwap, String tradeCount) {
		try {
			ZonedDateTime utcTime = ZonedDateTime.parse(timestamp.trim());
			ZonedDateTime marketTime = utcTime.withZoneSameInstant(MARKET_ZONE);
			return new CachedBar(
				marketTime.format(DISPLAY_TIME_FORMAT),
				marketTime.toLocalDate(),
				marketTime.toLocalTime(),
				parseCsvDouble(open),
				parseCsvDouble(high),
				parseCsvDouble(low),
				parseCsvDouble(close),
				parseCsvDouble(volume),
				parseCsvDouble(vwap),
				parseInt(tradeCount)
			);
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	private static boolean isMarketSession(CachedBar bar, LocalTime sessionStartTime) {
		return !bar.marketTime.isBefore(sessionStartTime) && bar.marketTime.isBefore(MARKET_CLOSE_TIME);
	}

	private static LocalTime marketSessionStartTime(String timeframeFolder) {
		if ("1hour".equals(timeframeFolder)) {
			return LocalTime.of(9, 0);
		}
		return MARKET_OPEN_TIME;
	}

	private static String normalizeTimeframeFolder(String timeframe) {
		if (timeframe == null || timeframe.trim().isEmpty()) {
			return "1min";
		}

		String normalized = timeframe.trim().toLowerCase();
		if ("1min".equals(normalized)) {
			return "1min";
		}
		if ("5min".equals(normalized)) {
			return "5min";
		}
		if ("30min".equals(normalized)) {
			return "30min";
		}
		if ("1hour".equals(normalized)) {
			return "1hour";
		}
		return "1min";
	}

	private static String normalizeTimeframeValue(String timeframe) {
		if ("5Min".equalsIgnoreCase(timeframe)) {
			return "5Min";
		}
		if ("30Min".equalsIgnoreCase(timeframe)) {
			return "30Min";
		}
		if ("1Hour".equalsIgnoreCase(timeframe)) {
			return "1Hour";
		}
		return "1Min";
	}

	private static String formatApiDateTime(ZonedDateTime value) {
		return value.withZoneSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
	}

	private static double parseCsvDouble(String value) {
		try {
			return Double.parseDouble(value.trim());
		} catch (NumberFormatException e) {
			return 0.0;
		}
	}

	private static int parseInt(String value) {
		try {
			return Integer.parseInt(value.trim());
		} catch (Exception e) {
			return 0;
		}
	}

	private static String getConfiguredMarketDataFeed() {
		String configuredFeed = getConfigValue("TRADING_BOT_MARKET_DATA_FEED", "tradingbot.marketDataFeed", DEFAULT_MARKET_DATA_FEED);
		String normalizedFeed = configuredFeed.trim().toLowerCase();
		if ("sip".equals(normalizedFeed) || "iex".equals(normalizedFeed) || "delayed_sip".equals(normalizedFeed)) {
			return normalizedFeed;
		}
		return DEFAULT_MARKET_DATA_FEED;
	}

	private static String getConfiguredMarketDataAdjustment() {
		String configuredAdjustment = getConfigValue(
			"TRADING_BOT_MARKET_DATA_ADJUSTMENT",
			"tradingbot.marketDataAdjustment",
			DEFAULT_MARKET_DATA_ADJUSTMENT
		);
		String normalizedAdjustment = configuredAdjustment.trim().toLowerCase();
		if ("raw".equals(normalizedAdjustment)
			|| "split".equals(normalizedAdjustment)
			|| "dividend".equals(normalizedAdjustment)
			|| "all".equals(normalizedAdjustment)
			|| "spin-off".equals(normalizedAdjustment)) {
			return normalizedAdjustment;
		}
		return DEFAULT_MARKET_DATA_ADJUSTMENT;
	}

	private static String getConfigValue(String environmentKey, String propertyKey, String defaultValue) {
		String systemProperty = System.getProperty(propertyKey);
		if (systemProperty != null && !systemProperty.trim().isEmpty()) {
			return systemProperty;
		}

		String environmentValue = System.getenv(environmentKey);
		if (environmentValue != null && !environmentValue.trim().isEmpty()) {
			return environmentValue;
		}

		return defaultValue;
	}

	private static boolean shouldRebuildMarketDataCache(String storedValue, String configuredValue, String defaultValue) {
		String normalizedStored = storedValue == null ? "" : storedValue.trim().toLowerCase();
		if (normalizedStored.isEmpty()) {
			return !defaultValue.equals(configuredValue);
		}
		return !normalizedStored.equals(configuredValue);
	}

	private double parseJsonDouble(String value) {
		try {
			return Double.parseDouble(value.trim());
		} catch (Exception e) {
			return 0.0;
		}
	}

	private String extractJsonValue(String json, String... keys) {
		for (String key : keys) {
			String value = extractJsonValue(json, key);
			if (!value.isEmpty()) {
				return value;
			}
		}

		return "";
	}

	private String extractJsonValue(String json, String key) {
		String quotedPrefix = "\"" + key + "\":\"";
		int quotedStart = json.indexOf(quotedPrefix);

		if (quotedStart >= 0) {
			int valueStart = quotedStart + quotedPrefix.length();
			int valueEnd = json.indexOf("\"", valueStart);

			if (valueEnd > valueStart) {
				return json.substring(valueStart, valueEnd);
			}
		}

		String plainPrefix = "\"" + key + "\":";
		int plainStart = json.indexOf(plainPrefix);

		if (plainStart >= 0) {
			int valueStart = plainStart + plainPrefix.length();
			int valueEnd = json.indexOf(",", valueStart);

			if (valueEnd < 0) {
				valueEnd = json.indexOf("}", valueStart);
			}

			if (valueEnd > valueStart) {
				return json.substring(valueStart, valueEnd).replace("\"", "").trim();
			}
		}

		return "";
	}

	private List<String[]> fetchHistoricalBars(String symbol, String startDate, String endDate, String timeframeValue) {
		List<String[]> bars = new ArrayList<String[]>();
		String nextPageToken = "";

		try {
			while (true) {
				String urlString = MARKET_DATA_URL + "/stocks/" + symbol + "/bars"
					+ "?timeframe=" + URLEncoder.encode(timeframeValue, "UTF-8")
					+ "&start=" + URLEncoder.encode(startDate, "UTF-8")
					+ "&end=" + URLEncoder.encode(endDate, "UTF-8")
					+ "&adjustment=" + URLEncoder.encode(getConfiguredMarketDataAdjustment(), "UTF-8")
					+ "&feed=" + URLEncoder.encode(getConfiguredMarketDataFeed(), "UTF-8")
					+ "&sort=asc"
					+ "&limit=10000";

				if (!nextPageToken.isEmpty()) {
					urlString += "&page_token=" + URLEncoder.encode(nextPageToken, "UTF-8");
				}

				URL url = new URL(urlString);
				HttpURLConnection conn = (HttpURLConnection) url.openConnection();
				conn.setRequestMethod("GET");
				conn.setRequestProperty("accept", "application/json");
				conn.setRequestProperty("APCA-API-KEY-ID", apiKey);
				conn.setRequestProperty("APCA-API-SECRET-KEY", secretKey);

				int responseCode = conn.getResponseCode();
				BufferedReader in;

				if (responseCode == 200) {
					in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
				} else {
					in = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
				}

				String inputLine;
				StringBuilder response = new StringBuilder();
				while ((inputLine = in.readLine()) != null) {
					response.append(inputLine);
				}

				in.close();

				if (responseCode != 200) {
					System.out.println("Historical data request failed for " + symbol + ".");
					return new ArrayList<String[]>();
				}

				String barsJson = extractJsonArray(response.toString(), "bars");
				List<String> barObjects = splitJsonObjects(barsJson);

				for (String barObject : barObjects) {
					String timestamp = extractJsonValue(barObject, "t");
					String open = extractJsonValue(barObject, "o");
					String high = extractJsonValue(barObject, "h");
					String low = extractJsonValue(barObject, "l");
					String close = extractJsonValue(barObject, "c");
					String volume = extractJsonValue(barObject, "v");
					String vwap = extractJsonValue(barObject, "vw");
					String tradeCount = extractJsonValue(barObject, "n");

					if (!timestamp.isEmpty()) {
						bars.add(new String[] {timestamp, open, high, low, close, volume, vwap.isEmpty() ? close : vwap, tradeCount.isEmpty() ? "0" : tradeCount});
					}
				}

				nextPageToken = extractJsonValue(response.toString(), "next_page_token");
				if (nextPageToken.isEmpty() || "null".equals(nextPageToken)) {
					break;
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
			return new ArrayList<String[]>();
		}

		return bars;
	}

	private static void ensureMarketDataDirectory() {
		File baseDir = marketDataDirectory();
		if (!baseDir.exists()) {
			baseDir.mkdirs();
		}

		for (int i = 0; i < SUPPORTED_TIMEFRAME_FOLDERS.length; i++) {
			File timeframeDir = timeframeDataDirectory(SUPPORTED_TIMEFRAME_FOLDERS[i]);
			if (!timeframeDir.exists()) {
				timeframeDir.mkdirs();
			}
		}
	}

	private void writeSymbolBars(String timeframeFolder, String symbol, List<String[]> bars) {
		File outputFile = new File(timeframeDataDirectory(timeframeFolder), symbol + ".csv");

		try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
				writer.write("timestamp,open,high,low,close,volume,vwap,trade_count");
			writer.newLine();

			for (int i = 0; i < bars.size(); i++) {
				writer.write(joinCsvRow(bars.get(i)));
				writer.newLine();
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private static Properties readMarketDataStatus() {
		Properties status = new Properties();
		File statusFile = marketDataStatusFile();

		if (!statusFile.exists()) {
			return status;
		}

		try (FileInputStream input = new FileInputStream(statusFile)) {
			status.load(input);
		} catch (Exception e) {
			e.printStackTrace();
		}

		return status;
	}

	private static void writeMarketDataStatus(String startDate, String endDate, String lastUpdatedAt, int totalBars, String feed, String adjustment) {
		Properties status = new Properties();
		status.setProperty("startDate", startDate);
		status.setProperty("endDate", endDate);
		status.setProperty("lastUpdatedAt", lastUpdatedAt);
		status.setProperty("totalBars", Integer.toString(totalBars));
		status.setProperty("feed", feed);
		status.setProperty("adjustment", adjustment);

		try (FileOutputStream output = new FileOutputStream(marketDataStatusFile())) {
			status.store(output, null);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private static File marketDataDirectory() {
		return new File(RuntimePaths.equityMarketDataDir());
	}

	private static File timeframeDataDirectory(String timeframeFolder) {
		return new File(marketDataDirectory(), timeframeFolder);
	}

	private static File oneMinuteDataDirectory() {
		return timeframeDataDirectory("1min");
	}

	private static File marketDataStatusFile() {
		return new File(marketDataDirectory(), "status.properties");
	}

	private static String joinCsvRow(String[] values) {
		StringBuilder row = new StringBuilder();

		for (int i = 0; i < values.length; i++) {
			if (i > 0) {
				row.append(",");
			}
			row.append(values[i] == null ? "" : values[i]);
		}

		return row.toString();
	}

	private String extractJsonArray(String json, String key) {
		String prefix = "\"" + key + "\":[";
		int start = json.indexOf(prefix);

		if (start < 0) {
			return "";
		}

		int index = start + prefix.length();
		int depth = 1;

		while (index < json.length()) {
			char current = json.charAt(index);

			if (current == '[') {
				depth++;
			} else if (current == ']') {
				depth--;
				if (depth == 0) {
					return json.substring(start + prefix.length(), index);
				}
			}

			index++;
		}

		return "";
	}

	private List<String> splitJsonObjects(String jsonArray) {
		List<String> objects = new ArrayList<String>();
		int depth = 0;
		int objectStart = -1;

		for (int i = 0; i < jsonArray.length(); i++) {
			char current = jsonArray.charAt(i);

			if (current == '{') {
				if (depth == 0) {
					objectStart = i;
				}
				depth++;
			} else if (current == '}') {
				depth--;
				if (depth == 0 && objectStart >= 0) {
					objects.add(jsonArray.substring(objectStart, i + 1));
				}
			}
		}

		return objects;
	}

	private String extractDate(String timestamp) {
		if (timestamp == null || timestamp.length() < 10) {
			return "";
		}

		return timestamp.substring(0, 10);
	}

	private static String jsonArray(String[] values) {
		StringBuilder json = new StringBuilder("[");

		for (int i = 0; i < values.length; i++) {
			if (i > 0) {
				json.append(",");
			}
			json.append(jsonString(values[i]));
		}

		json.append("]");
		return json.toString();
	}

	private static String jsonString(String value) {
		String safeValue = value == null ? "" : value;
		safeValue = safeValue
			.replace("\\", "\\\\")
			.replace("\"", "\\\"")
			.replace("\n", "\\n")
			.replace("\r", "\\r");
		return "\"" + safeValue + "\"";
	}
}
