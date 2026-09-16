package com.tradingbot;

public class SyntheticLevel2BulkRebuildRunner {
	private static final String[] SYMBOLS = new String[] { "MES", "MNQ", "NQ", "MGC", "ES", "M2K", "MYM", "MCL" };

	public static void main(String[] args) {
		for (int index = 0; index < SYMBOLS.length; index++) {
			String symbol = SYMBOLS[index];
			String result = FuturesConnectionManager.rebuildDerivedFuturesData(symbol);
			System.out.println(symbol + "," + result);
		}
	}
}
