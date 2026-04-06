package com.morix.websocket;

import com.morix.game.GameEngine;
import com.morix.model.GameStore;
import com.morix.model.Room;
import com.morix.util.DB;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.websocket.*;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Main WebSocket endpoint — /ws
 *
 * Mirrors Python ws_server.py handler() exactly:
 *   - login / register / auto_login
 *   - host / join / rejoin
 *   - social: friend search, add, remove, invite, accept/decline
 *   - game: placement, movement, win detection
 *   - leave_game, rematch flow
 */
@ServerEndpoint("/ws")
public class GameEndpoint {

    // Per-connection state
    private final GameStore store = GameStore.get();
    private String  username     = null;
    private String  symbol       = null;   // "X" or "O"
    private String  roomCode     = null;
    private String  sessionToken = null;
    private boolean inGameLoop   = false;

    // Shared scheduler for cleanup tasks
    private static final ScheduledExecutorService SCHEDULER =
            Executors.newScheduledThreadPool(2);

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @OnOpen
    public void onOpen(Session session) {
        // nothing until first message
    }

    @OnMessage
    public synchronized void onMessage(String raw, Session session) {
        try {
            // Placement phase sends a plain number string e.g. "5"
            // Handle it directly before trying to parse as JSON object
            String trimmed = raw.trim();
            if (inGameLoop && trimmed.matches("\\d+")) {
                Room room = store.getRoom(roomCode);
                if (room == null) return;
                synchronized (room) {
                    if (room.gameOver || !symbol.equals(room.turn)) return;
                    if (room.placed < 6) {
                        int pos = Integer.parseInt(trimmed);
                        if (room.game.placePiece(pos, symbol)) {
                            room.placed++;
                            room.totalMoves++;
                            if (room.game.checkWin(symbol)) {
                                room.gameOver = true;
                                String loser = room.opponentUsername(symbol);
                                broadcast(room, obj().put("type", "win").put("player", symbol));
                                saveGameAsync(username, loser, room.totalMoves, false, room);
                            } else {
                                room.turn = "X".equals(room.turn) ? "O" : "X";
                                broadcastBoard(room);
                            }
                        }
                    }
                }
                return;
            }

            JSONObject msg = new JSONObject(raw);
            String action  = msg.optString("action", "");

            // ── Not yet in game loop ─────────────────────────────────────────
            if (!inGameLoop) {
                handleLobbyMessage(action, msg, session);
                return;
            }

            // ── In game loop ─────────────────────────────────────────────────
            handleGameMessage(raw, msg, action, session);

        } catch (Exception e) {
            System.err.println("[Morix] onMessage error: " + e.getMessage());
        }
    }

    @OnClose
    public synchronized void onClose(Session session, CloseReason reason) {
        if (username != null && store.getOnline(username) == session) {
            store.removeOnline(username);
            notifyFriendsStatusChange(username);
        }

        if (roomCode != null && store.roomExists(roomCode)) {
            Room room = store.getRoom(roomCode);
            if (room != null) {
                synchronized (room) {
                    room.players[Room.slot(symbol)] = null;
                    room.usernames[Room.slot(symbol)] = null;
                    if (!room.gameOver) {
                        Session opp = room.opponentSession(symbol);
                        if (opp != null) safeSend(opp, obj()
                                .put("type", "opponent_disconnected")
                                .put("message", "Opponent disconnected. Waiting 60s for rejoin..."));
                    }
                    // schedule cleanup
                    int slot = Room.slot(symbol);
                    SCHEDULER.schedule(() -> cleanupRoom(roomCode, slot), 60, TimeUnit.SECONDS);
                }
            }
        }
    }

    @OnError
    public void onError(Session session, Throwable t) {
        System.err.println("[Morix] WS error: " + t.getMessage());
    }

    // ── Lobby message handler ────────────────────────────────────────────────

    private void handleLobbyMessage(String action, JSONObject msg, Session session)
            throws Exception {

        switch (action) {

            case "register": {
                String u = msg.optString("username", "").trim();
                String p = msg.optString("password", "");
                if (u.isEmpty() || p.isEmpty())
                    { send(session, err("Username and password required.")); return; }
                if (!u.matches("[A-Za-z0-9_]{3,32}"))
                    { send(session, err("Username: 3-32 chars, letters/numbers/underscore.")); return; }
                if (p.length() < 6)
                    { send(session, err("Password must be at least 6 characters.")); return; }
                if (DB.userExists(u))
                    { send(session, err("Username already taken.")); return; }
                DB.createUser(u, DB.hashPassword(p));
                username = u;
                store.putOnline(username, session);
                String tok = store.createAuthToken(username);
                send(session, obj().put("type","registered").put("username",u)
                        .put("auth_token",tok).put("friends",new JSONArray()).put("statuses",new JSONObject()));
                notifyFriendsStatusChange(username);
                System.out.println("[Morix] Registered: " + u);
                break;
            }

            case "login": {
                String u = msg.optString("username","").trim();
                String p = msg.optString("password","");
                Map<String,String> udata = DB.getUser(u);
                if (udata == null || !DB.verifyPassword(p, udata.get("password_hash")))
                    { send(session, err("Invalid username or password.")); return; }
                username = u;
                store.putOnline(username, session);
                String tok = store.createAuthToken(username);
                List<String> friends = DB.getFriends(u);
                JSONObject statuses  = buildStatuses(friends);
                send(session, obj().put("type","logged_in").put("username",u)
                        .put("auth_token",tok).put("friends",new JSONArray(friends))
                        .put("statuses",statuses));
                notifyFriendsStatusChange(username);
                System.out.println("[Morix] Login: " + u);
                break;
            }

            case "auto_login": {
                String tok = msg.optString("auth_token","");
                String u   = store.validateAuthToken(tok);
                if (u == null || !DB.userExists(u))
                    { send(session, err("Session expired. Please log in again.")); return; }
                username = u;
                store.putOnline(username, session);
                List<String> friends = DB.getFriends(u);
                JSONObject statuses  = buildStatuses(friends);
                // Check for active game
                String[] ag = store.findActiveGameForUser(username);
                JSONObject activeGame = null;
                if (ag != null) {
                    activeGame = new JSONObject()
                            .put("session_token", ag[0])
                            .put("symbol",        ag[1])
                            .put("code",          ag[2]);
                }
                JSONObject resp = obj().put("type","logged_in").put("username",u)
                        .put("auth_token",tok).put("friends",new JSONArray(friends))
                        .put("statuses",statuses);
                if (activeGame != null) resp.put("active_game", activeGame);
                send(session, resp);
                notifyFriendsStatusChange(username);
                System.out.println("[Morix] Auto-login: " + u);
                break;
            }

            case "host": {
                requireLogin(session);
                roomCode     = store.generateCode();
                symbol       = "X";
                String first = randomFirst();
                Room room    = new Room(roomCode, session, username, first);
                store.putRoom(roomCode, room);
                sessionToken = store.createGameSession("X", roomCode, username);
                inGameLoop   = true;
                send(session, obj().put("type","hosted").put("code",roomCode)
                        .put("symbol","X").put("session_token",sessionToken));
                notifyFriendsStatusChange(username);
                System.out.println("[Morix] Hosted " + roomCode + " by " + username);
                break;
            }

            case "join": {
                requireLogin(session);
                String jcode = msg.optString("code","").trim();
                Room room    = store.getRoom(jcode);
                if (room == null)  { send(session, err("Room not found.")); return; }
                synchronized (room) {
                    if (room.players[1] != null) { send(session, err("Room already full.")); return; }
                    room.players[1]  = session;
                    room.usernames[1] = username;
                }
                roomCode     = jcode;
                symbol       = "O";
                sessionToken = store.createGameSession("O", roomCode, username);
                inGameLoop   = true;
                send(session, obj().put("type","joined").put("symbol","O")
                        .put("session_token",sessionToken));
                // broadcast start + board
                broadcastStart(room);
                broadcastBoard(room);
                notifyFriendsStatusChange(username);
                System.out.println("[Morix] Joined " + roomCode + " by " + username);
                break;
            }

            case "rejoin": {
                String tok  = msg.optString("token","");
                String[] s  = store.validateGameSession(tok);
                if (s == null) { send(session, err("Session expired.")); return; }
                symbol       = s[0];
                roomCode     = s[1];
                username     = s[2];
                Room room    = store.getRoom(roomCode);
                if (room == null) { send(session, err("Room no longer exists.")); return; }
                synchronized (room) {
                    room.players[Room.slot(symbol)]  = session;
                    room.usernames[Room.slot(symbol)] = username;
                }
                if (username != null) store.putOnline(username, session);
                sessionToken = tok;
                inGameLoop   = true;
                send(session, obj().put("type","rejoined").put("symbol",symbol).put("code",roomCode));
                broadcastBoard(room);
                System.out.println("[Morix] Rejoined " + roomCode + " as " + symbol + " (" + username + ")");
                break;
            }

            default:
                // Social actions (friends, invites) handled while in lobby too
                handleSocial(action, msg, session);
                break;
        }
    }

    // ── Game loop message handler ────────────────────────────────────────────

    private void handleGameMessage(String raw, JSONObject msg, String action, Session session)
            throws Exception {

        Room room = store.getRoom(roomCode);
        if (room == null) return;

        // ── Social pass-through ──────────────────────────────────────────────
        if (handleSocial(action, msg, session)) return;

        // ── Explicit leave ───────────────────────────────────────────────────
        if ("leave_game".equals(action)) {
            destroyRoom(roomCode, symbol, username);
            inGameLoop = false;
            roomCode   = null;
            return;
        }

        // ── Rematch request ──────────────────────────────────────────────────
        if ("rematch_request".equals(action)) {
            synchronized (room) {
                Session opp = room.opponentSession(symbol);
                if (opp != null) safeSend(opp, obj().put("type","rematch_request").put("from",symbol));
                room.rematchVotes.put(symbol, "pending");
            }
            return;
        }

        // ── Rematch accept ───────────────────────────────────────────────────
        if ("rematch_accept".equals(action)) {
            synchronized (room) {
                String first = randomFirst();
                room.game.reset();
                room.placed      = 0;
                room.totalMoves  = 0;
                room.turn        = first;
                room.firstTurn   = first;
                room.rematchVotes.clear();
                room.gameOver    = false;
            }
            broadcast(room, obj().put("type","rematch_start").put("first_turn",room.firstTurn));
            broadcastBoard(room);
            return;
        }

        // ── Rematch decline ──────────────────────────────────────────────────
        if ("rematch_decline".equals(action)) {
            synchronized (room) {
                Session opp = room.opponentSession(symbol);
                if (opp != null) safeSend(opp, obj().put("type","rematch_declined").put("by",symbol));
                room.rematchVotes.clear();
            }
            store.removeRoom(roomCode);
            inGameLoop = false;
            roomCode   = null;
            return;
        }

        // ── Skip moves if game over or not this player's turn ────────────────
        synchronized (room) {
            if (room.gameOver || !symbol.equals(room.turn)) return;
        }

        // ── Placement phase ─ handled in onMessage for plain number strings ───
        synchronized (room) {
            if (room.placed < 6) return; // plain number already handled above
        }

        // ── Movement phase ───────────────────────────────────────────────────
        synchronized (room) {
            try {
                JSONObject move = new JSONObject(raw);
                int from = move.getInt("from");
                int to   = move.getInt("to");
                if (room.game.movePiece(from, to, symbol)) {
                    room.totalMoves++;
                    if (room.game.checkWin(symbol)) {
                        room.gameOver = true;
                        String loser  = room.opponentUsername(symbol);
                        broadcast(room, obj().put("type","win").put("player",symbol));
                        saveGameAsync(username, loser, room.totalMoves, false, room);
                    } else {
                        room.turn = "X".equals(room.turn) ? "O" : "X";
                        broadcastBoard(room);
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    // ── Social handler (friends, invites) ────────────────────────────────────

    /** Returns true if the action was consumed. */
    private boolean handleSocial(String action, JSONObject msg, Session session)
            throws Exception {
        switch (action) {

            case "search_user": {
                String q = msg.optString("username","").trim();
                boolean found = !q.isEmpty() && username != null
                        && DB.userExists(q) && !q.equals(username);
                send(session, obj().put("type","search_user_result")
                        .put("found",found).put("username", found ? q : ""));
                return true;
            }

            case "add_friend": {
                if (username == null) { send(session, obj().put("type","add_friend_result")
                        .put("success",false).put("message","Not logged in.")); return true; }
                String fn = msg.optString("username","").trim();
                if (fn.isEmpty() || fn.equals(username) || !DB.userExists(fn)) {
                    send(session, obj().put("type","add_friend_result")
                            .put("success",false).put("message","User not found."));
                    return true;
                }
                List<String> friends = DB.getFriends(username);
                if (friends.contains(fn)) {
                    send(session, obj().put("type","add_friend_result")
                            .put("success",false).put("message","Already friends."));
                    return true;
                }
                friends.add(fn);
                DB.setFriends(username, friends);
                send(session, obj().put("type","add_friend_result").put("success",true)
                        .put("friend",fn).put("status",store.getStatus(fn)));
                return true;
            }

            case "remove_friend": {
                if (username == null) return true;
                String fn = msg.optString("username","").trim();
                List<String> friends = DB.getFriends(username);
                friends.remove(fn);
                DB.setFriends(username, friends);
                send(session, obj().put("type","remove_friend_result").put("success",true).put("friend",fn));
                return true;
            }

            case "send_invite": {
                if (username == null) return true;
                String target   = msg.optString("to","");
                Session targetWs = store.getOnline(target);
                if (targetWs == null) {
                    send(session, obj().put("type","invite_result").put("success",false)
                            .put("message",target + " is offline."));
                    return true;
                }
                safeSend(targetWs, obj().put("type","incoming_invite").put("from",username));
                send(session, obj().put("type","invite_result").put("success",true).put("to",target));
                return true;
            }

            case "accept_invite": {
                if (username == null) return true;
                String inviter   = msg.optString("from","");
                Session inviterWs = store.getOnline(inviter);
                if (inviterWs == null) {
                    send(session, err(inviter + " went offline."));
                    return true;
                }
                // Create the room
                String code   = store.generateCode();
                String first  = randomFirst();
                Room room     = new Room(code, inviterWs, inviter, first);
                room.players[1]  = session;
                room.usernames[1] = username;
                store.putRoom(code, room);

                String tokX = store.createGameSession("X", code, inviter);
                String tokO = store.createGameSession("O", code, username);

                // Signal inviter's endpoint (it's in lobby, waiting)
                // We set the pending game and call a synthetic "accept" flow.
                // Since each WS connection runs in its own GameEndpoint,
                // we find the inviter's endpoint via a shared pending-game signal.
                store.setPendingGame(inviter, new String[]{ code, "X", tokX, username });

                // Send game_ready to inviter
                safeSend(inviterWs, obj().put("type","game_ready")
                        .put("code",code).put("symbol","X")
                        .put("session_token",tokX).put("opponent",username)
                        .put("first_turn",first));

                // Set up joiner (this endpoint)
                this.roomCode     = code;
                this.symbol       = "O";
                this.sessionToken = tokO;
                this.inGameLoop   = true;

                // Send game_ready + start + board to joiner
                send(session, obj().put("type","game_ready")
                        .put("code",code).put("symbol","O")
                        .put("session_token",tokO).put("opponent",inviter)
                        .put("first_turn",first));
                broadcast(room, obj().put("type","start").put("first_turn",first));
                broadcastBoard(room);

                notifyFriendsStatusChange(inviter);
                notifyFriendsStatusChange(username);
                return true;
            }

            case "decline_invite": {
                String inviter    = msg.optString("from","");
                Session inviterWs = store.getOnline(inviter);
                if (inviterWs != null)
                    safeSend(inviterWs, obj().put("type","invite_declined").put("by",username));
                return true;
            }

            default:
                return false;
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void broadcastBoard(Room room) {
        String[] board = room.game.getBoard();
        JSONArray arr  = new JSONArray();
        for (String v : board) arr.put(v == null ? JSONObject.NULL : v);
        JSONObject msg = obj().put("type","board").put("board",arr).put("turn",room.turn);
        broadcast(room, msg);
    }

    private void broadcastStart(Room room) {
        broadcast(room, obj().put("type","start").put("first_turn",room.turn));
    }

    private void broadcast(Room room, JSONObject payload) {
        String text = payload.toString();
        for (Session s : room.players) {
            if (s != null) safeSend(s, text);
        }
    }

    private void safeSend(Session s, JSONObject payload) { safeSend(s, payload.toString()); }
    private void safeSend(Session s, String text) {
        if (s != null && s.isOpen()) {
            try { s.getBasicRemote().sendText(text); }
            catch (IOException e) { /* connection gone */ }
        }
    }

    private void send(Session s, JSONObject payload) { safeSend(s, payload); }

    private JSONObject obj()                { return new JSONObject(); }
    private JSONObject err(String m)        { return obj().put("type","error").put("message",m); }
    private String     randomFirst()        { return Math.random() < 0.5 ? "X" : "O"; }
    private void       requireLogin(Session s) throws IOException {
        if (username == null) { send(s, err("Not logged in.")); }
    }

    private JSONObject buildStatuses(List<String> friends) {
        JSONObject o = new JSONObject();
        for (String f : friends) o.put(f, store.getStatus(f));
        return o;
    }

    private void notifyFriendsStatusChange(String changedUser) {
        try {
            for (String other : DB.getAllUsernames()) {
                if (other.equals(changedUser) || !store.isOnline(other)) continue;
                List<String> theirFriends = DB.getFriends(other);
                if (!theirFriends.contains(changedUser)) continue;
                JSONObject statuses = buildStatuses(theirFriends);
                safeSend(store.getOnline(other),
                         obj().put("type","friend_statuses").put("statuses",statuses));
            }
        } catch (Exception e) {
            System.err.println("[Morix] notifyFriends error: " + e.getMessage());
        }
    }

    private void destroyRoom(String code, String leavingSymbol, String leavingUsername) {
        store.invalidateSessionsForRoom(code);
        Room room = store.removeRoom(code);
        if (room == null) return;
        synchronized (room) {
            if (!room.gameOver) {
                Session opp = room.opponentSession(leavingSymbol);
                if (opp != null) safeSend(opp, obj()
                        .put("type","opponent_left")
                        .put("message",(leavingUsername != null ? leavingUsername : "Opponent") + " left the game."));
            }
        }
        if (leavingUsername != null) notifyFriendsStatusChange(leavingUsername);
        String oppUser = room.opponentUsername(leavingSymbol);
        if (oppUser != null) notifyFriendsStatusChange(oppUser);
        System.out.println("[Morix] Room " + code + " destroyed by " + leavingSymbol + " (" + leavingUsername + ")");
    }

    private void cleanupRoom(String code, int slot) {
        Room room = store.getRoom(code);
        if (room == null) return;
        synchronized (room) {
            if (room.players[slot] == null) {
                store.removeRoom(code);
                try { DB.saveGame(null, null, room.totalMoves, true); }
                catch (Exception e) { System.err.println("[Morix] saveGame error: " + e.getMessage()); }
                System.out.println("[Morix] Room " + code + " cleaned up after 60 s timeout");
            }
        }
    }

    private void saveGameAsync(String winner, String loser, int moves, boolean abandoned, Room room) {
        String w = winner, l = loser;
        SCHEDULER.submit(() -> {
            try {
                DB.saveGame(w, l, moves, abandoned);
                notifyFriendsStatusChange(w);
                if (l != null) notifyFriendsStatusChange(l);
            } catch (Exception e) {
                System.err.println("[Morix] saveGame error: " + e.getMessage());
            }
        });
    }
}