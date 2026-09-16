package com.tradingbot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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
	public void blankValueAndJsonHelpersMatchRouteValidationNeeds() throws Exception {
		assertTrue(MainServer.isBlank(null));
		assertTrue(MainServer.isBlank("  "));
		assertFalse(MainServer.isBlank("value"));
		assertEquals("fallback", MainServer.valueOrDefault(" ", "fallback"));
		assertEquals("trimmed", MainServer.valueOrDefault(" trimmed ", "fallback"));
		assertEquals("\"line\\nquote\\\"slash\\\\carriage\\r\"", MainServer.jsonString("line\nquote\"slash\\carriage\r"));
		assertEquals("\"\"", MainServer.jsonString(null));

		List<String> routes = routeInventory();
		assertEquals(95, routes.size(), routes.toString());
		assertEquals(53, countRoutes(routes, "GET "));
		assertEquals(41, countRoutes(routes, "POST "));
		assertEquals(1, countRoutes(routes, "DELETE "));
		assertEquals(
			"bf5eac6f9d24bb530af568452193efc6cec27fd12c06a8f3b296d207c5864a88",
			sha256(String.join("\n", routes) + "\n"),
			"The 95-route METHOD/PATH inventory changed."
		);
	}

	private static List<String> routeInventory() throws Exception {
		Path sourceRoot = Path.of(System.getProperty("user.dir"), "src");
		if (!Files.isDirectory(sourceRoot)) {
			Path testClasses = Path.of(MainServerTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
			sourceRoot = testClasses.getParent().getParent().resolve("src");
		}
		Pattern routePattern = Pattern.compile("app\\.(get|post|put|delete|patch)\\(\"([^\"]+)\"");
		List<String> routes = new ArrayList<String>();
		try (Stream<Path> files = Files.list(sourceRoot)) {
			for (Path source : (Iterable<Path>) files.filter(path -> path.toString().endsWith(".java"))::iterator) {
				for (String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
					Matcher matcher = routePattern.matcher(line);
					while (matcher.find()) {
						routes.add(matcher.group(1).toUpperCase(Locale.US) + " " + matcher.group(2));
					}
				}
			}
		}
		Collections.sort(routes);
		return routes;
	}

	private static int countRoutes(List<String> routes, String prefix) {
		int count = 0;
		for (String route : routes) {
			if (route.startsWith(prefix)) {
				count++;
			}
		}
		return count;
	}

	private static String sha256(String value) throws Exception {
		byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
		StringBuilder hex = new StringBuilder();
		for (byte item : digest) {
			hex.append(String.format("%02x", Integer.valueOf(item & 0xff)));
		}
		return hex.toString();
	}
}
