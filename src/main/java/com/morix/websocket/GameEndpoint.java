package com.morix.websocket;

import com.morix.game.BotEngine;
import com.morix.game.GameEngine;
import com.morix.model.GameStore;
import com.morix.model.Room;
import com.morix.util.DB;
import com.morix.util.MailUtil;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.websocket.*;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/**
 * Main WebSocket endpoint — /ws
 *
 * New features added on top of original:
 *   - Play vs Bot  (action: "host_bot")
 *   - Emoji reactions flying across the board (action: "emoji")
 *   - In-game chat (action: "chat")
 */
@ServerEndpoint("/ws")
public class GameEndpoint {

    private final GameStore store = GameStore.get();
    private String  username     = null;
    private String  symbol       = null;
    private String  roomCode     = null;
    private String  sessionToken = null;
    private boolean inGameLoop   = false;
    private boolean vsBot        = false;  // true when playing against the bot

    private static final ScheduledExecutorService SCHEDULER =
            Executors.newScheduledThreadPool(2);

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @OnOpen
    public void onOpen(Session session) { }

    @OnMessage
    public synchronized void onMessage(String raw, Session session) {
        try {
            String trimmed = raw.trim();

            // Placement phase — plain number string
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
                                broadcast(room, obj().put("type","win").put("player",symbol));
                                saveGameAsync(username, room.opponentUsername(symbol), room.totalMoves, false, room);
                            } else {
                                room.turn = "X".equals(room.turn) ? "O" : "X";
                                broadcastBoard(room);
                                if (vsBot && !room.gameOver) scheduleBotMove(room);
                            }
                        }
                    }
                }
                return;
            }

            JSONObject msg = new JSONObject(raw);
            String action  = msg.optString("action", "");

            if (!inGameLoop) {
                handleLobbyMessage(action, msg, session);
                return;
            }
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
                    if (!room.gameOver && !vsBot) {
                        Session opp = room.opponentSession(symbol);
                        if (opp != null) safeSend(opp, obj()
                                .put("type","opponent_disconnected")
                                .put("message","Opponent disconnected. Waiting 60s for rejoin..."));
                    }
                    if (!vsBot) {
                        int slot = Room.slot(symbol);
                        SCHEDULER.schedule(() -> cleanupRoom(roomCode, slot), 60, TimeUnit.SECONDS);
                    } else {
                        store.removeRoom(roomCode);
                    }
                }
            }
        }
    }

    @OnError
    public void onError(Session session, Throwable t) {
        System.err.println("[Morix] WS error: " + t.getMessage());
    }

    // ── Lobby handler ────────────────────────────────────────────────────────

    private void handleLobbyMessage(String action, JSONObject msg, Session session)
            throws Exception {

        switch (action) {

            case "register": {
                String u = msg.optString("username","").trim();
                String p = msg.optString("password","");
                String e = msg.optString("email","").trim();
                if (u.isEmpty()||p.isEmpty()) { send(session,err("Username and password required.")); return; }
                if (!u.matches("[A-Za-z0-9_]{3,32}")) { send(session,err("Username: 3-32 chars, letters/numbers/underscore.")); return; }
                if (p.length()<6) { send(session,err("Password must be at least 6 characters.")); return; }
                if (DB.userExists(u)) { send(session,err("Username already taken.")); return; }
                DB.createUser(u, DB.hashPassword(p), e.isEmpty() ? null : e);
                username = u;
                store.putOnline(username, session);
                String tok = store.createAuthToken(username);
                send(session, obj().put("type","registered").put("username",u)
                        .put("auth_token",tok).put("friends",new JSONArray()).put("statuses",new JSONObject()));
                notifyFriendsStatusChange(username);
                break;
            }

            case "login": {
                String u = msg.optString("username","").trim();
                String p = msg.optString("password","");
                Map<String,String> udata = DB.getUser(u);
                if (udata==null||!DB.verifyPassword(p,udata.get("password_hash")))
                    { send(session,err("Invalid username or password.")); return; }
                username = u;
                store.putOnline(username, session);
                String tok = store.createAuthToken(username);
                List<String> friends = DB.getFriends(u);
                JSONObject statuses  = buildStatuses(friends);
                send(session, obj().put("type","logged_in").put("username",u)
                        .put("auth_token",tok).put("friends",new JSONArray(friends)).put("statuses",statuses));
                notifyFriendsStatusChange(username);
                break;
            }

            case "auto_login": {
                String tok = msg.optString("auth_token","");
                String u   = store.validateAuthToken(tok);
                if (u==null||!DB.userExists(u)) { send(session,err("Session expired. Please log in again.")); return; }
                username = u;
                store.putOnline(username, session);
                List<String> friends = DB.getFriends(u);
                JSONObject statuses  = buildStatuses(friends);
                String[] ag = store.findActiveGameForUser(username);
                JSONObject activeGame = null;
                if (ag!=null) activeGame = new JSONObject()
                        .put("session_token",ag[0]).put("symbol",ag[1]).put("code",ag[2]);
                JSONObject resp = obj().put("type","logged_in").put("username",u)
                        .put("auth_token",tok).put("friends",new JSONArray(friends)).put("statuses",statuses);
                if (activeGame!=null) resp.put("active_game",activeGame);
                send(session, resp);
                notifyFriendsStatusChange(username);
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
                vsBot        = false;
                send(session, obj().put("type","hosted").put("code",roomCode)
                        .put("symbol","X").put("session_token",sessionToken));
                notifyFriendsStatusChange(username);
                break;
            }

            // ── NEW: Host a bot game ─────────────────────────────────────────
            case "host_bot": {
                requireLogin(session);
                roomCode     = store.generateCode();
                symbol       = "X";
                String first = randomFirst();
                Room room    = new Room(roomCode, session, username, first);
                // Slot 1 = Bot (O)
                room.usernames[1] = "🤖 Bot";
                store.putRoom(roomCode, room);
                sessionToken = store.createGameSession("X", roomCode, username);
                inGameLoop   = true;
                vsBot        = true;
                send(session, obj().put("type","game_ready")
                        .put("code",roomCode).put("symbol","X")
                        .put("session_token",sessionToken)
                        .put("opponent","🤖 Bot")
                        .put("first_turn",first)
                        .put("vs_bot",true));
                // Broadcast start + board
                broadcast(room, obj().put("type","start").put("first_turn",first));
                broadcastBoard(room);
                // If bot goes first, schedule its move
                if ("O".equals(first)) scheduleBotMove(room);
                notifyFriendsStatusChange(username);
                break;
            }

            case "join": {
                requireLogin(session);
                String jcode = msg.optString("code","").trim();
                Room room    = store.getRoom(jcode);
                if (room==null) { send(session,err("Room not found.")); return; }
                synchronized(room) {
                    if (room.players[1]!=null) { send(session,err("Room already full.")); return; }
                    room.players[1]  = session;
                    room.usernames[1] = username;
                }
                roomCode     = jcode;
                symbol       = "O";
                sessionToken = store.createGameSession("O", roomCode, username);
                inGameLoop   = true;
                vsBot        = false;
                send(session, obj().put("type","joined").put("symbol","O").put("session_token",sessionToken));
                broadcastStart(room);
                broadcastBoard(room);
                notifyFriendsStatusChange(username);
                break;
            }

            case "rejoin": {
                String tok  = msg.optString("token","");
                String[] s  = store.validateGameSession(tok);
                if (s==null) { send(session,err("Session expired.")); return; }
                symbol       = s[0]; roomCode = s[1]; username = s[2];
                Room room    = store.getRoom(roomCode);
                if (room==null) { send(session,err("Room no longer exists.")); return; }
                synchronized(room) {
                    room.players[Room.slot(symbol)]  = session;
                    room.usernames[Room.slot(symbol)] = username;
                }
                if (username!=null) store.putOnline(username, session);
                sessionToken = tok;
                inGameLoop   = true;
                send(session, obj().put("type","rejoined").put("symbol",symbol).put("code",roomCode));
                broadcastBoard(room);
                break;
            }

            default:
                handleSocial(action, msg, session);
                break;
        }
    }

    // ── Game loop handler ────────────────────────────────────────────────────

    private void handleGameMessage(String raw, JSONObject msg, String action, Session session)
            throws Exception {

        Room room = store.getRoom(roomCode);
        if (room==null) return;

        if (handleSocial(action, msg, session)) return;

        // ── Chat message ─────────────────────────────────────────────────────
        if ("chat".equals(action)) {
            String text = msg.optString("text","").trim();
            if (text.isEmpty() || text.length() > 200) return;
            broadcast(room, obj().put("type","chat")
                    .put("from", username)
                    .put("symbol", symbol)
                    .put("text", text));
            return;
        }

        // ── Emoji reaction ───────────────────────────────────────────────────
        if ("emoji".equals(action)) {
            String emoji = msg.optString("emoji","");
            if (emoji.isEmpty()) return;
            // Broadcast to both players — frontend animates it flying
            broadcast(room, obj().put("type","emoji")
                    .put("from", username)
                    .put("symbol", symbol)
                    .put("emoji", emoji));
            return;
        }

        if ("leave_game".equals(action)) {
            destroyRoom(roomCode, symbol, username);
            inGameLoop = false; roomCode = null;
            return;
        }

        if ("rematch_request".equals(action)) {
            synchronized(room) {
                Session opp = room.opponentSession(symbol);
                if (opp!=null) safeSend(opp, obj().put("type","rematch_request").put("from",symbol));
                room.rematchVotes.put(symbol,"pending");
            }
            return;
        }

        if ("rematch_accept".equals(action)) {
            synchronized(room) {
                String first = randomFirst();
                room.game.reset(); room.placed=0; room.totalMoves=0;
                room.turn=first; room.firstTurn=first;
                room.rematchVotes.clear(); room.gameOver=false;
            }
            broadcast(room, obj().put("type","rematch_start").put("first_turn",room.firstTurn));
            broadcastBoard(room);
            if (vsBot && "O".equals(room.firstTurn)) scheduleBotMove(room);
            return;
        }

        if ("rematch_decline".equals(action)) {
            synchronized(room) {
                Session opp = room.opponentSession(symbol);
                if (opp!=null) safeSend(opp, obj().put("type","rematch_declined").put("by",symbol));
                room.rematchVotes.clear();
            }
            store.removeRoom(roomCode);
            inGameLoop=false; roomCode=null;
            return;
        }

        synchronized(room) { if (room.gameOver||!symbol.equals(room.turn)) return; }
        synchronized(room) { if (room.placed<6) return; }

        // Movement phase
        synchronized(room) {
            try {
                int from = msg.getInt("from");
                int to   = msg.getInt("to");
                if (room.game.movePiece(from, to, symbol)) {
                    room.totalMoves++;
                    if (room.game.checkWin(symbol)) {
                        room.gameOver = true;
                        broadcast(room, obj().put("type","win").put("player",symbol));
                        saveGameAsync(username, room.opponentUsername(symbol), room.totalMoves, false, room);
                    } else {
                        room.turn = "X".equals(room.turn) ? "O" : "X";
                        broadcastBoard(room);
                        if (vsBot && !room.gameOver) scheduleBotMove(room);
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    // ── Bot move scheduling ──────────────────────────────────────────────────

    /**
     * Schedules the bot to make its move with a short delay (feels more natural).
     */
    private void scheduleBotMove(Room room) {
        SCHEDULER.schedule(() -> executeBotMove(room), 700, TimeUnit.MILLISECONDS);
    }

    private synchronized void executeBotMove(Room room) {
        if (room == null || room.gameOver) return;
        synchronized (room) {
            if (!"O".equals(room.turn)) return;
            String[] board = room.game.getBoard();

            if (room.placed < 6) {
                // Placement phase
                int botPiecesPlaced = 0;
                for (String v : board) if ("O".equals(v)) botPiecesPlaced++;
                if (botPiecesPlaced < 3) {
                    int pos = BotEngine.choosePlacement(board, "O");
                    if (pos != -1 && room.game.placePiece(pos, "O")) {
                        room.placed++;
                        room.totalMoves++;
                        if (room.game.checkWin("O")) {
                            room.gameOver = true;
                            broadcast(room, obj().put("type","win").put("player","O"));
                            saveGameAsync(null, username, room.totalMoves, false, room);
                        } else {
                            room.turn = "X";
                            broadcastBoard(room);
                        }
                    }
                }
            } else {
                // Movement phase
                int[] move = BotEngine.chooseMove(board, "O");
                if (move != null && room.game.movePiece(move[0], move[1], "O")) {
                    room.totalMoves++;
                    if (room.game.checkWin("O")) {
                        room.gameOver = true;
                        broadcast(room, obj().put("type","win").put("player","O"));
                        saveGameAsync(null, username, room.totalMoves, false, room);
                    } else {
                        room.turn = "X";
                        broadcastBoard(room);
                    }
                }
            }
        }
    }

    // ── Social handler ───────────────────────────────────────────────────────

    private boolean handleSocial(String action, JSONObject msg, Session session)
            throws Exception {
        switch (action) {

            case "search_user": {
                String q = msg.optString("username","").trim();
                boolean found = !q.isEmpty()&&username!=null&&DB.userExists(q)&&!q.equals(username);
                send(session, obj().put("type","search_user_result")
                        .put("found",found).put("username",found?q:""));
                return true;
            }

            case "add_friend": {
                if (username==null) { send(session,obj().put("type","add_friend_result").put("success",false).put("message","Not logged in.")); return true; }
                String fn = msg.optString("username","").trim();
                if (fn.isEmpty()||fn.equals(username)||!DB.userExists(fn)) {
                    send(session,obj().put("type","add_friend_result").put("success",false).put("message","User not found."));
                    return true;
                }
                List<String> friends = DB.getFriends(username);
                if (friends.contains(fn)) {
                    send(session,obj().put("type","add_friend_result").put("success",false).put("message","Already friends."));
                    return true;
                }
                friends.add(fn);
                DB.setFriends(username, friends);
                send(session,obj().put("type","add_friend_result").put("success",true)
                        .put("friend",fn).put("status",store.getStatus(fn)));
                return true;
            }

            case "remove_friend": {
                if (username==null) return true;
                String fn = msg.optString("username","").trim();
                List<String> friends = DB.getFriends(username);
                friends.remove(fn);
                DB.setFriends(username, friends);
                send(session,obj().put("type","remove_friend_result").put("success",true).put("friend",fn));
                return true;
            }

            case "send_invite": {
                if (username==null) return true;
                String target    = msg.optString("to","");
                Session targetWs = store.getOnline(target);
                String host      = System.getenv("RENDER_EXTERNAL_HOSTNAME");
                String gameLink  = (host != null && !host.isEmpty()) ? "https://" + host : "http://localhost:8080";
                boolean emailSent = false;
                // Always try email — works online or offline
                try {
                    String targetEmail = DB.getEmail(target);
                    if (targetEmail != null && !targetEmail.isEmpty()) {
                        MailUtil.sendGameInvite(targetEmail, username, gameLink);
                        emailSent = true;
                        System.out.println("[Morix] Invite email sent to " + target + " (" + targetEmail + ")");
                    } else {
                        System.out.println("[Morix] No email for user: " + target);
                    }
                } catch (Exception mailEx) {
                    System.err.println("[Morix] Invite email failed: " + mailEx.getMessage());
                }
                if (targetWs == null) {
                    String offlineMsg = emailSent
                        ? target + " is offline — email notification sent!"
                        : target + " is offline and has no email set.";
                    send(session, obj().put("type","invite_result").put("success",false).put("message", offlineMsg));
                    return true;
                }
                // Online: send WS invite + also email
                safeSend(targetWs, obj().put("type","incoming_invite").put("from",username));
                String onlineMsg = emailSent ? "Notified! Email also sent to " + target : "Notified " + target + "!";
                send(session, obj().put("type","invite_result").put("success",true).put("to",target).put("message", onlineMsg));
                return true;
            }

            case "accept_invite": {
                if (username==null) return true;
                String inviter    = msg.optString("from","");
                Session inviterWs = store.getOnline(inviter);
                if (inviterWs==null) { send(session,err(inviter+" went offline.")); return true; }
                String code  = store.generateCode();
                String first = randomFirst();
                Room room    = new Room(code, inviterWs, inviter, first);
                room.players[1]  = session;
                room.usernames[1] = username;
                store.putRoom(code, room);
                String tokX = store.createGameSession("X", code, inviter);
                String tokO = store.createGameSession("O", code, username);
                store.setPendingGame(inviter, new String[]{ code, "X", tokX, username });
                safeSend(inviterWs, obj().put("type","game_ready")
                        .put("code",code).put("symbol","X")
                        .put("session_token",tokX).put("opponent",username)
                        .put("first_turn",first));
                this.roomCode=code; this.symbol="O"; this.sessionToken=tokO; this.inGameLoop=true;
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
                if (inviterWs!=null)
                    safeSend(inviterWs, obj().put("type","invite_declined").put("by",username));
                return true;
            }

            default: return false;
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void broadcastBoard(Room room) {
        String[] board = room.game.getBoard();
        JSONArray arr  = new JSONArray();
        for (String v : board) arr.put(v==null ? JSONObject.NULL : v);
        broadcast(room, obj().put("type","board").put("board",arr).put("turn",room.turn));
    }

    private void broadcastStart(Room room) {
        broadcast(room, obj().put("type","start").put("first_turn",room.turn));
    }

    private void broadcast(Room room, JSONObject payload) {
        String text = payload.toString();
        for (Session s : room.players) { if (s!=null) safeSend(s, text); }
    }

    private void safeSend(Session s, JSONObject p) { safeSend(s, p.toString()); }
    private void safeSend(Session s, String text) {
        if (s!=null&&s.isOpen()) {
            try { s.getBasicRemote().sendText(text); }
            catch (IOException e) { /* gone */ }
        }
    }
    private void send(Session s, JSONObject p) { safeSend(s, p); }
    private JSONObject obj()         { return new JSONObject(); }
    private JSONObject err(String m) { return obj().put("type","error").put("message",m); }
    private String randomFirst()     { return Math.random()<0.5?"X":"O"; }
    private void requireLogin(Session s) throws IOException {
        if (username==null) send(s, err("Not logged in."));
    }

    private JSONObject buildStatuses(List<String> friends) {
        JSONObject o = new JSONObject();
        for (String f : friends) o.put(f, store.getStatus(f));
        return o;
    }

    private void notifyFriendsStatusChange(String changedUser) {
        try {
            for (String other : DB.getAllUsernames()) {
                if (other.equals(changedUser)||!store.isOnline(other)) continue;
                List<String> theirFriends = DB.getFriends(other);
                if (!theirFriends.contains(changedUser)) continue;
                safeSend(store.getOnline(other),
                         obj().put("type","friend_statuses").put("statuses",buildStatuses(theirFriends)));
            }
        } catch (Exception e) {
            System.err.println("[Morix] notifyFriends error: "+e.getMessage());
        }
    }

    private void destroyRoom(String code, String leavingSymbol, String leavingUsername) {
        store.invalidateSessionsForRoom(code);
        Room room = store.removeRoom(code);
        if (room==null) return;
        synchronized(room) {
            if (!room.gameOver) {
                Session opp = room.opponentSession(leavingSymbol);
                if (opp!=null) safeSend(opp, obj().put("type","opponent_left")
                        .put("message",(leavingUsername!=null?leavingUsername:"Opponent")+" left the game."));
            }
        }
        if (leavingUsername!=null) notifyFriendsStatusChange(leavingUsername);
        String oppUser = room.opponentUsername(leavingSymbol);
        if (oppUser!=null) notifyFriendsStatusChange(oppUser);
    }

    private void cleanupRoom(String code, int slot) {
        Room room = store.getRoom(code);
        if (room==null) return;
        synchronized(room) {
            if (room.players[slot]==null) {
                store.removeRoom(code);
                try { DB.saveGame(null, null, room.totalMoves, true); }
                catch (Exception e) { System.err.println("[Morix] saveGame error: "+e.getMessage()); }
            }
        }
    }

    private void saveGameAsync(String winner, String loser, int moves, boolean abandoned, Room room) {
        String w=winner, l=loser;
        SCHEDULER.submit(() -> {
            try {
                DB.saveGame(w, l, moves, abandoned);
                if (w!=null) notifyFriendsStatusChange(w);
                if (l!=null) notifyFriendsStatusChange(l);
            } catch (Exception e) {
                System.err.println("[Morix] saveGame error: "+e.getMessage());
            }
        });
    }
}
