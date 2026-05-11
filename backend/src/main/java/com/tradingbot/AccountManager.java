package com.tradingbot;

import java.time.Instant;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class AccountManager {
	private static final SecureRandom SECURE_RANDOM = new SecureRandom();
	private static final long DEFAULT_SESSION_TTL_SECONDS = 12L * 60L * 60L;
	private static final int PASSWORD_HASH_ITERATIONS = 120000;
	private static final int PASSWORD_HASH_BITS = 256;
	private static final int PASSWORD_SALT_BYTES = 16;
	private static final String PASSWORD_HASH_PREFIX = "pbkdf2_sha256";

	public static class AccountSession {
		public int accountId;
		public String email;
		public String role;
		public String token;
		public String expiresAt;
	}

	public boolean registerAccount(String name, String email, String password) {
		return registerAccount(name, email, "", "", password);
	}

	public boolean registerAccount(String name, String email, String phoneNumber, String address, String password) {
		String sql = "INSERT INTO Account(accountName, email, phoneNumber, address, passwordHash, createdAt) VALUES(?,?,?,?,?,?)";
		
		try(Connection conn = DatabaseManager.getConnection();
				PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, name);
			pstmt.setString(2, email);
			pstmt.setString(3, phoneNumber);
			pstmt.setString(4, address);
			pstmt.setString(5, hashPassword(password));
			pstmt.setString(6, Instant.now().toString());
			
			int rows = pstmt.executeUpdate();
			System.out.println("Account registered successfully.");
			return rows > 0;
			
		} catch(SQLException e) {
			System.out.println("Registration failed, email might already exist.");
			return false;
		}
		
	}
	
	public int login(String email, String password) {
		String sql = "SELECT accountID, passwordHash FROM Account WHERE email = ?";
		
		try(Connection conn = DatabaseManager.getConnection();
				PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, normalizeEmail(email));
			int accountId = 0;
			String storedHash = null;
			try (ResultSet rs = pstmt.executeQuery()) {
				if(rs.next() && verifyPassword(password, rs.getString("passwordHash"))) {
					accountId = rs.getInt("accountID");
					storedHash = rs.getString("passwordHash");
				}
			}

			if(accountId > 0) {
				migrateLegacyPasswordIfNeeded(conn, normalizeEmail(email), password, storedHash);
				return accountId;
			} else {
				System.out.println("Invalid email or password.");
				return -1;
			}
			
		} catch(SQLException e) {
			e.printStackTrace();
			return -1;
		}
		
	}

	public AccountSession createSession(String email, String password) {
		String sql = "SELECT accountID, email, role, passwordHash FROM Account WHERE email = ?";

		try(Connection conn = DatabaseManager.getConnection();
				PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, normalizeEmail(email));
			AccountSession session;
			String storedHash;

			try (ResultSet rs = pstmt.executeQuery()) {
				if (!rs.next() || !verifyPassword(password, rs.getString("passwordHash"))) {
					System.out.println("Invalid email or password.");
					return null;
				}

				session = new AccountSession();
				session.accountId = rs.getInt("accountID");
				session.email = valueOrDefault(rs.getString("email"), normalizeEmail(email));
				session.role = normalizeRole(rs.getString("role"));
				session.token = generateToken();
				session.expiresAt = Instant.now().plusSeconds(configuredSessionTtlSeconds()).toString();
				storedHash = rs.getString("passwordHash");
			}

			migrateLegacyPasswordIfNeeded(conn, session.email, password, storedHash);
			insertSession(conn, session);
			return session;
		} catch(SQLException e) {
			e.printStackTrace();
			return null;
		}
	}

	public AccountSession getSession(String token) {
		if (isBlank(token)) {
			return null;
		}

		AccountSession session = null;
		String sql = "SELECT s.accountID, s.sessionToken, s.role, s.expiresAt, a.email "
			+ "FROM AccountSession s "
			+ "JOIN Account a ON a.accountID = s.accountID "
			+ "WHERE s.sessionToken = ? AND s.revokedAt IS NULL";

		try(Connection conn = DatabaseManager.getConnection();
				PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, token.trim());
			ResultSet rs = pstmt.executeQuery();

			if (!rs.next()) {
				return null;
			}

			String expiresAt = rs.getString("expiresAt");
			if (isExpired(expiresAt)) {
				revokeSession(token);
				return null;
			}

			session = new AccountSession();
			session.accountId = rs.getInt("accountID");
			session.email = valueOrDefault(rs.getString("email"), "");
			session.role = normalizeRole(rs.getString("role"));
			session.token = rs.getString("sessionToken");
			session.expiresAt = expiresAt;
		} catch(SQLException e) {
			e.printStackTrace();
			return null;
		}

		touchSession(token);
		return session;
	}

	public boolean revokeSession(String token) {
		if (isBlank(token)) {
			return false;
		}

		String sql = "UPDATE AccountSession SET revokedAt = ? WHERE sessionToken = ? AND revokedAt IS NULL";

		try(Connection conn = DatabaseManager.getConnection();
				PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, Instant.now().toString());
			pstmt.setString(2, token.trim());
			return pstmt.executeUpdate() > 0;
		} catch(SQLException e) {
			e.printStackTrace();
			return false;
		}
	}

	public int getAccountId(String email) {
		String sql = "SELECT accountID FROM Account WHERE email = ?";

		try(Connection conn = DatabaseManager.getConnection();
				PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, email);
			ResultSet rs = pstmt.executeQuery();

			if (rs.next()) {
				return rs.getInt("accountID");
			}

			return -1;
		} catch(SQLException e) {
			e.printStackTrace();
			return -1;
		}
	}

	public boolean changePassword(String email, String currentPassword, String newPassword) {
		String selectSql = "SELECT passwordHash FROM Account WHERE email = ?";
		String updateSql = "UPDATE Account SET passwordHash = ? WHERE email = ?";

		try(Connection conn = DatabaseManager.getConnection();
				PreparedStatement select = conn.prepareStatement(selectSql)) {
			select.setString(1, normalizeEmail(email));

			try (ResultSet rs = select.executeQuery()) {
				if (!rs.next() || !verifyPassword(currentPassword, rs.getString("passwordHash"))) {
					return false;
				}
			}

			try (PreparedStatement update = conn.prepareStatement(updateSql)) {
				update.setString(1, hashPassword(newPassword));
				update.setString(2, normalizeEmail(email));
				return update.executeUpdate() > 0;
			}

		} catch(SQLException e) {
			e.printStackTrace();
			return false;
		}
	}

	public String getPasswordHash(String email) {
		return getAccountField(email, "passwordHash");
	}

	public String getAccountName(String email) {
		return getAccountField(email, "accountName");
	}

	public String getPhoneNumber(String email) {
		return getAccountField(email, "phoneNumber");
	}

	public String getAddress(String email) {
		return getAccountField(email, "address");
	}

	public String getRole(String email) {
		return normalizeRole(getAccountField(email, "role"));
	}

	public String getBrokerApiKey(String email) {
		return getAccountField(email, "brokerApiKey");
	}

	public String getBrokerSecretKey(String email) {
		return getAccountField(email, "brokerSecretKey");
	}

	public boolean updateBrokerKeys(String email, String apiKey, String secretKey) {
		String sql = "UPDATE Account SET brokerApiKey = ?, brokerSecretKey = ? WHERE email = ?";

		try(Connection conn = DatabaseManager.getConnection();
				PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, apiKey);
			pstmt.setString(2, secretKey);
			pstmt.setString(3, email);

			int rows = pstmt.executeUpdate();
			return rows > 0;

		} catch(SQLException e) {
			e.printStackTrace();
			return false;
		}
	}

	public boolean updateAccountDetails(String currentEmail, String accountName, String newEmail, String phoneNumber, String address) {
		String sql = "UPDATE Account SET accountName = ?, email = ?, phoneNumber = ?, address = ? WHERE email = ?";

		try(Connection conn = DatabaseManager.getConnection();
				PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, accountName);
			pstmt.setString(2, newEmail);
			pstmt.setString(3, phoneNumber);
			pstmt.setString(4, address);
			pstmt.setString(5, currentEmail);

			int rows = pstmt.executeUpdate();
			return rows > 0;

		} catch(SQLException e) {
			e.printStackTrace();
			return false;
		}
	}

	private String getAccountField(String email, String fieldName) {
		String sql = "SELECT " + fieldName + " FROM Account WHERE email = ?";

		try(Connection conn = DatabaseManager.getConnection();
				PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, email);
			ResultSet rs = pstmt.executeQuery();

			if (rs.next()) {
				String value = rs.getString(fieldName);
				return value == null ? "" : value;
			}

			return "";
		} catch(SQLException e) {
			e.printStackTrace();
			return "";
		}
	}

	private void insertSession(Connection conn, AccountSession session) throws SQLException {
		String sql = "INSERT INTO AccountSession(sessionToken, accountID, role, createdAt, lastSeenAt, expiresAt) VALUES(?,?,?,?,?,?)";
		String now = Instant.now().toString();

		try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, session.token);
			pstmt.setInt(2, session.accountId);
			pstmt.setString(3, session.role);
			pstmt.setString(4, now);
			pstmt.setString(5, now);
			pstmt.setString(6, session.expiresAt);
			pstmt.executeUpdate();
		}
	}

	private void touchSession(String token) {
		String sql = "UPDATE AccountSession SET lastSeenAt = ? WHERE sessionToken = ?";

		try(Connection conn = DatabaseManager.getConnection();
				PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, Instant.now().toString());
			pstmt.setString(2, token.trim());
			pstmt.executeUpdate();
		} catch(SQLException e) {
			e.printStackTrace();
		}
	}

	private static String generateToken() {
		byte[] bytes = new byte[32];
		SECURE_RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	static String hashPassword(String password) {
		byte[] salt = new byte[PASSWORD_SALT_BYTES];
		SECURE_RANDOM.nextBytes(salt);
		byte[] hash = pbkdf2(password, salt, PASSWORD_HASH_ITERATIONS);
		return PASSWORD_HASH_PREFIX
			+ "$" + PASSWORD_HASH_ITERATIONS
			+ "$" + Base64.getUrlEncoder().withoutPadding().encodeToString(salt)
			+ "$" + Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
	}

	private static boolean verifyPassword(String password, String storedHash) {
		if (isBlank(password) || isBlank(storedHash)) {
			return false;
		}

		if (!storedHash.startsWith(PASSWORD_HASH_PREFIX + "$")) {
			return MessageDigest.isEqual(
				storedHash.getBytes(java.nio.charset.StandardCharsets.UTF_8),
				password.getBytes(java.nio.charset.StandardCharsets.UTF_8)
			);
		}

		String[] parts = storedHash.split("\\$");
		if (parts.length != 4) {
			return false;
		}

		try {
			int iterations = Integer.parseInt(parts[1]);
			byte[] salt = Base64.getUrlDecoder().decode(parts[2]);
			byte[] expected = Base64.getUrlDecoder().decode(parts[3]);
			byte[] actual = pbkdf2(password, salt, iterations);
			return MessageDigest.isEqual(expected, actual);
		} catch (RuntimeException e) {
			return false;
		}
	}

	private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
		char[] passwordChars = password == null ? new char[0] : password.toCharArray();
		try {
			PBEKeySpec spec = new PBEKeySpec(passwordChars, salt, iterations, PASSWORD_HASH_BITS);
			SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
			return factory.generateSecret(spec).getEncoded();
		} catch (Exception e) {
			throw new IllegalStateException("Password hashing failed.", e);
		} finally {
			java.util.Arrays.fill(passwordChars, '\0');
		}
	}

	private void migrateLegacyPasswordIfNeeded(Connection conn, String email, String password, String storedHash) throws SQLException {
		if (isBlank(email) || isBlank(password) || isBlank(storedHash) || storedHash.startsWith(PASSWORD_HASH_PREFIX + "$")) {
			return;
		}

		String sql = "UPDATE Account SET passwordHash = ? WHERE email = ? AND passwordHash = ?";
		try(PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, hashPassword(password));
			pstmt.setString(2, normalizeEmail(email));
			pstmt.setString(3, storedHash);
			pstmt.executeUpdate();
		}
	}

	private static boolean isExpired(String expiresAt) {
		if (isBlank(expiresAt)) {
			return true;
		}

		try {
			return !Instant.parse(expiresAt).isAfter(Instant.now());
		} catch (RuntimeException e) {
			return true;
		}
	}

	private static long configuredSessionTtlSeconds() {
		String configured = System.getProperty("tradingbot.sessionTtlSeconds");
		if (isBlank(configured)) {
			configured = System.getenv("TRADINGBOT_SESSION_TTL_SECONDS");
		}

		try {
			return Math.max(300L, Long.parseLong(configured));
		} catch (RuntimeException e) {
			return DEFAULT_SESSION_TTL_SECONDS;
		}
	}

	private static String normalizeEmail(String email) {
		return email == null ? "" : email.trim();
	}

	private static String normalizeRole(String role) {
		String normalized = role == null ? "" : role.trim().toLowerCase();
		if ("operator".equals(normalized) || "viewer".equals(normalized)) {
			return normalized;
		}
		return "admin";
	}

	private static String valueOrDefault(String value, String defaultValue) {
		return isBlank(value) ? defaultValue : value.trim();
	}

	private static boolean isBlank(String value) {
		return value == null || value.trim().isEmpty();
	}

}
