package com.morix.util;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.util.*;

/**
 * Single class that owns the JDBC connection and every SQL operation.
 * PostgreSQL edition — compatible with Render's free Postgres tier.
 *
 * On Render, set the environment variable DATABASE_URL to the Internal
 * Database URL shown on your Render Postgres dashboard.
 */
public class DB {

    private static String URL;
    private static String USER;
    private static String PASS;

    public static void init(String fallbackUrl, String fallbackUser, String fallbackPass)
            throws Exception {
        Class.forName("org.postgresql.Driver");
        String envUrl = System.getenv("DATABASE_URL");
        if (envUrl != null && !envUrl.isEmpty()) {
            parsePostgresUrl(envUrl);
        } else {
            URL  = fallbackUrl;
            USER = fallbackUser;
            PASS = fallbackPass;
        }
        try (Connection c = getConn()) { createTables(c); }
        System.out.println("[Morix] Database ready: " + URL);
    }

    private static void parsePostgresUrl(String raw) throws Exception {
        URI uri = new URI(raw.replace("postgres://", "postgresql://"));
        String[] parts = uri.getUserInfo().split(":", 2);
        USER = parts[0];
        PASS = parts.length > 1 ? parts[1] : "";
        URL  = "jdbc:postgresql://" + uri.getHost() + ":" + uri.getPort()
               + uri.getPath() + "?sslmode=require";
    }

    static Connection getConn() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASS);
    }

    private static void createTables(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS users (" +
                "  username      TEXT PRIMARY KEY," +
                "  password_hash TEXT NOT NULL," +
                "  friends       TEXT NOT NULL DEFAULT '[]'," +
                "  created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                ")"
            );
            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS games (" +
                "  id        SERIAL PRIMARY KEY," +
                "  winner    TEXT," +
                "  loser     TEXT," +
                "  moves     INT," +
                "  abandoned SMALLINT DEFAULT 0," +
                "  ts        TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                ")"
            );
            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS players (" +
                "  username TEXT PRIMARY KEY," +
                "  wins     INT DEFAULT 0," +
                "  losses   INT DEFAULT 0" +
                ")"
            );
        }
    }

    // Password hashing
    public static String hashPassword(String password) {
        try {
            SecureRandom rng = new SecureRandom();
            byte[] salt = new byte[16];
            rng.nextBytes(salt);
            return hex(salt) + ":" + hex(pbkdf2(password, salt, 100_000));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public static boolean verifyPassword(String password, String stored) {
        try {
            String[] parts = stored.split(":", 2);
            if (parts.length != 2) return false;
            byte[] salt = fromHex(parts[0]);
            return MessageDigest.isEqual(pbkdf2(password, salt, 100_000), fromHex(parts[1]));
        } catch (Exception e) { return false; }
    }

    private static byte[] pbkdf2(String pwd, byte[] salt, int iters) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] result = md.digest((pwd + hex(salt)).getBytes());
        for (int i = 1; i < iters; i++) { md.reset(); result = md.digest(result); }
        return result;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte v : b) sb.append(String.format("%02x", v));
        return sb.toString();
    }

    private static byte[] fromHex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < s.length(); i += 2)
            out[i/2] = (byte)((Character.digit(s.charAt(i),16)<<4) + Character.digit(s.charAt(i+1),16));
        return out;
    }

    // User ops
    public static boolean userExists(String username) throws SQLException {
        try (Connection c = getConn();
             PreparedStatement p = c.prepareStatement("SELECT 1 FROM users WHERE username=?")) {
            p.setString(1, username);
            try (ResultSet r = p.executeQuery()) { return r.next(); }
        }
    }

    public static void createUser(String username, String passwordHash) throws SQLException {
        try (Connection c = getConn();
             PreparedStatement p = c.prepareStatement(
                     "INSERT INTO users(username,password_hash,friends) VALUES(?,?,?)")) {
            p.setString(1, username); p.setString(2, passwordHash); p.setString(3, "[]");
            p.executeUpdate();
        }
    }

    public static Map<String,String> getUser(String username) throws SQLException {
        try (Connection c = getConn();
             PreparedStatement p = c.prepareStatement(
                     "SELECT password_hash, friends FROM users WHERE username=?")) {
            p.setString(1, username);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) return null;
                Map<String,String> m = new HashMap<>();
                m.put("password_hash", r.getString(1));
                m.put("friends", r.getString(2));
                return m;
            }
        }
    }

    public static List<String> getFriends(String username) throws SQLException {
        try (Connection c = getConn();
             PreparedStatement p = c.prepareStatement(
                     "SELECT friends FROM users WHERE username=?")) {
            p.setString(1, username);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) return new ArrayList<>();
                JSONArray arr = new JSONArray(r.getString(1));
                List<String> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) list.add(arr.getString(i));
                return list;
            }
        }
    }

    public static void setFriends(String username, List<String> friends) throws SQLException {
        try (Connection c = getConn();
             PreparedStatement p = c.prepareStatement(
                     "UPDATE users SET friends=? WHERE username=?")) {
            p.setString(1, new JSONArray(friends).toString()); p.setString(2, username);
            p.executeUpdate();
        }
    }

    public static List<String> getAllUsernames() throws SQLException {
        List<String> list = new ArrayList<>();
        try (Connection c = getConn(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT username FROM users")) {
            while (r.next()) list.add(r.getString(1));
        }
        return list;
    }

    // Game ops
    public static void saveGame(String winner, String loser, int moves, boolean abandoned)
            throws SQLException {
        try (Connection c = getConn()) {
            try (PreparedStatement p = c.prepareStatement(
                    "INSERT INTO games(winner,loser,moves,abandoned) VALUES(?,?,?,?)")) {
                p.setString(1, winner); p.setString(2, loser);
                p.setInt(3, moves);    p.setInt(4, abandoned ? 1 : 0);
                p.executeUpdate();
            }
            if (winner != null && !abandoned) {
                try (PreparedStatement p = c.prepareStatement(
                        "INSERT INTO players(username,wins) VALUES(?,1) " +
                        "ON CONFLICT(username) DO UPDATE SET wins=players.wins+1")) {
                    p.setString(1, winner); p.executeUpdate();
                }
                if (loser != null) {
                    try (PreparedStatement p = c.prepareStatement(
                            "INSERT INTO players(username,losses) VALUES(?,1) " +
                            "ON CONFLICT(username) DO UPDATE SET losses=players.losses+1")) {
                        p.setString(1, loser); p.executeUpdate();
                    }
                }
            }
        }
    }

    public static JSONArray getLeaderboard() throws SQLException {
        JSONArray arr = new JSONArray();
        try (Connection c = getConn(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery(
                     "SELECT username, wins FROM players ORDER BY wins DESC LIMIT 10")) {
            while (r.next()) arr.put(new JSONObject().put("player", r.getString(1)).put("wins", r.getInt(2)));
        }
        return arr;
    }
}
