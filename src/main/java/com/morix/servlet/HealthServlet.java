package com.morix.servlet;

import com.morix.model.GameStore;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * GET /api/health → {"status":"ok","rooms":N,"users_online":M}
 */
public class HealthServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        GameStore store = GameStore.get();
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().printf(
            "{\"status\":\"ok\",\"rooms\":%d,\"users_online\":%d}",
            store.roomCount(), store.onlineCount()
        );
    }
}
