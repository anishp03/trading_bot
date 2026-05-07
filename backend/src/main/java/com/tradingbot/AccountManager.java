package com.tradingbot;

import java.time.Instant;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;

public class AccountManager {
	private static final SecureRandom SECURE_RANDOM = new SecureRandom();
	private static final long DEFAULT_SESSION_TTL_SECONDS = 12L * 60L * 60L;

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
			pstmt.setString(5, password);
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
		String sql = "SELECT accountID FROM Account WHERE email = ? AND passwordHash = ?";
		
		try(Connection conn = DatabaseManager.getConnection();
				PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, email);
			pstmt.setString(2, password);
			ResultSet rs = pstmt.executeQuery();
			
			if(rs.next()) {
				int accountId = rs.getInt("accountID");
				System.out.println("Login successful, your User ID: " + accountId);
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
		String sql = "SELECT accountID, email, role FROM Account WHERE email = ? AND passwordHash = ?";

		try(Connection conn = DatabaseManager.getConnection();
				PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, normalizeEmail(email));
			pstmt.setString(2, password);
			ResultSet rs = pstmt.executeQuery();

			if (!rs.next()) {
				System.out.println("Invalid email or password.");
				return null;
			}

			AccountSession session = new AccountSession();
			session.accountId = rs.getInt("accountID");
			session.email = valueOrDefault(rs.getString("email"), normalizeEmail(email));
			session.role = normalizeRole(rs.getString("role"));
			session.token = generateToken();
			session.expiresAt = Instant.now().plusSeconds(configuredSessionTtlSeconds()).toString();
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
		String sql = "UPDATE Account SET passwordHash = ? WHERE email = ? AND passwordHash = ?";

		try(Connection conn = DatabaseManager.getConnection();
				PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, newPassword);
			pstmt.setString(2, email);
			pstmt.setString(3, currentPassword);

			int rows = pstmt.executeUpdate();
			return rows > 0;

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
