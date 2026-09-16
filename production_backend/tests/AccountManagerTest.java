package com.tradingbot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

public class AccountManagerTest {
	@TempDir
	Path tempDir;

	private AccountManager manager;

	@BeforeEach
	public void setUp() {
		TestDatabaseSupport.useTempDatabase(tempDir);
		manager = new AccountManager();
	}

	@AfterEach
	public void tearDown() {
		TestDatabaseSupport.clearTempDatabase();
	}
	
	@Test
	public void registerAndLoginWithProfileFields() {
		String email = "testuser@tradingbot.com";
		String password = "securepassword123";

		assertTrue(
			manager.registerAccount("Test User", email, "555-0100", "123 Market St", password),
			"Account should register successfully."
		);

		int accountId = manager.login(email, password);
		assertTrue(accountId > 0, "Login should return a valid user ID.");
		assertEquals(accountId, manager.getAccountId(email), "The account lookup should return the registered user ID.");
		assertEquals("Test User", manager.getAccountName(email));
		assertEquals("555-0100", manager.getPhoneNumber(email));
		assertEquals("123 Market St", manager.getAddress(email));
		assertNotEquals(password, manager.getPasswordHash(email), "Passwords must be stored as one-way hashes.");
		assertTrue(manager.getPasswordHash(email).startsWith("pbkdf2_sha256$"));
	}

	@Test
	public void duplicateEmailIsRejectedAndOriginalLoginStillWorks() {
		String email = "duplicate@tradingbot.com";

		assertTrue(manager.registerAccount("First User", email, "firstpass"));
		assertFalse(
			manager.registerAccount("Second User", email, "secondpass"),
			"The unique email constraint should reject a duplicate registration."
		);

		assertTrue(manager.login(email, "firstpass") > 0);
		assertEquals(-1, manager.login(email, "secondpass"), "The rejected duplicate password must not work.");
	}

	@Test
	public void passwordChangeRequiresTheCurrentPassword() {
		String email = "password@tradingbot.com";
		assertTrue(manager.registerAccount("Password User", email, "oldpass"));

		assertFalse(manager.changePassword(email, "wrongpass", "newpass"));
		assertEquals(-1, manager.login(email, "newpass"));

		assertTrue(manager.changePassword(email, "oldpass", "newpass"));
		assertEquals(-1, manager.login(email, "oldpass"));
		assertTrue(manager.login(email, "newpass") > 0);
	}

	@Test
	public void accountDetailsAndBrokerKeysCanBeUpdated() {
		String oldEmail = "settings-old@tradingbot.com";
		String newEmail = "settings-new@tradingbot.com";
		assertTrue(manager.registerAccount("Original Name", oldEmail, "555-0000", "Old Address", "secret"));

		assertTrue(manager.updateAccountDetails(oldEmail, "Updated Name", newEmail, "555-9999", "New Address"));
		assertEquals(-1, manager.getAccountId(oldEmail));
		assertTrue(manager.getAccountId(newEmail) > 0);
		assertEquals("Updated Name", manager.getAccountName(newEmail));
		assertEquals("555-9999", manager.getPhoneNumber(newEmail));
		assertEquals("New Address", manager.getAddress(newEmail));

		assertTrue(manager.updateBrokerKeys(newEmail, "paper-key", "paper-secret"));
		assertEquals("paper-key", manager.getBrokerApiKey(newEmail));
		assertEquals("paper-secret", manager.getBrokerSecretKey(newEmail));
	}

	@Test
	public void loginSessionCanBeCreatedValidatedAndRevoked() {
		String email = "session@tradingbot.com";
		assertTrue(manager.registerAccount("Session User", email, "secret"));

		AccountManager.AccountSession session = manager.createSession(email, "secret");
		assertNotNull(session);
		assertEquals(email, session.email);
		assertEquals("admin", session.role);
		assertTrue(session.token.length() > 30);

		AccountManager.AccountSession loaded = manager.getSession(session.token);
		assertNotNull(loaded);
		assertEquals(session.email, loaded.email);
		assertEquals(session.role, loaded.role);

		assertTrue(manager.revokeSession(session.token));
		assertEquals(null, manager.getSession(session.token));
	}
}
