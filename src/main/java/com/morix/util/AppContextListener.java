package com.morix.util;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

/**
 * Fires once when Tomcat deploys the WAR.
 * Reads DB credentials from web.xml context-params and initialises the schema.
 */
@WebListener
public class AppContextListener implements ServletContextListener {

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        ServletContext ctx = sce.getServletContext();
        String url  = ctx.getInitParameter("db.url");
        String user = ctx.getInitParameter("db.user");
        String pass = ctx.getInitParameter("db.password");
        try {
            DB.init(url, user, pass);
            System.out.println("[Morix] App started OK");
        } catch (Exception e) {
            System.err.println("[Morix] DB init failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        System.out.println("[Morix] App stopped");
    }
}
