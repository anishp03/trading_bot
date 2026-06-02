package com.tradingbot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

public class MainServerTest {
	@Test
	public void parseDoubleOrDefaultHandlesValidBlankAndInvalidValues() {
		assertEquals(12.5, MainServer.parseDoubleOrDefault("12.5", 1.0), 0.001);
		assertEquals(1.0, MainServer.parseDoubleOrDefault(null, 1.0), 0.001);
		assertEquals(1.0, MainServer.parseDoubleOrDefault("   ", 1.0), 0.001);
		assertEquals(1.0, MainServer.parseDoubleOrDefault("not-a-number", 1.0), 0.001);
	}

	@Test
	public void parseIntOrDefaultHandlesValidBlankAndInvalidValues() {
		assertEquals(7, MainServer.parseIntOrDefault("7", 1));
		assertEquals(1, MainServer.parseIntOrDefault(null, 1));
		assertEquals(1, MainServer.parseIntOrDefault("   ", 1));
		assertEquals(1, MainServer.parseIntOrDefault("seven", 1));
	}

	@Test
	public void parseBooleanOrDefaultAcceptsUiFriendlyBooleanValues() {
		assertTrue(MainServer.parseBooleanOrDefault("true", false));
		assertTrue(MainServer.parseBooleanOrDefault("1", false));
		assertTrue(MainServer.parseBooleanOrDefault("yes", false));
		assertTrue(MainServer.parseBooleanOrDefault("on", false));
		assertFalse(MainServer.parseBooleanOrDefault("false", true));
		assertFalse(MainServer.parseBooleanOrDefault("0", true));
		assertFalse(MainServer.parseBooleanOrDefault("no", true));
		assertFalse(MainServer.parseBooleanOrDefault("off", true));
		assertTrue(MainServer.parseBooleanOrDefault("maybe", true));
		assertFalse(MainServer.parseBooleanOrDefault("maybe", false));
	}

	@Test
	public void blankValueAndJsonHelpersMatchRouteValidationNeeds() {
		assertTrue(MainServer.isBlank(null));
		assertTrue(MainServer.isBlank("  "));
		assertFalse(MainServer.isBlank("value"));
		assertEquals("fallback", MainServer.valueOrDefault(" ", "fallback"));
		assertEquals("trimmed", MainServer.valueOrDefault(" trimmed ", "fallback"));
		assertEquals("\"line\\nquote\\\"slash\\\\carriage\\r\"", MainServer.jsonString("line\nquote\"slash\\carriage\r"));
		assertEquals("\"\"", MainServer.jsonString(null));
	}
}
