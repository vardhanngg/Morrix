-- Run this once in MySQL before deploying the WAR.
-- Adjust the password to match web.xml db.password.

CREATE DATABASE IF NOT EXISTS morix
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'morix_user'@'localhost' IDENTIFIED BY 'morix_pass';
GRANT ALL PRIVILEGES ON morix.* TO 'morix_user'@'localhost';
FLUSH PRIVILEGES;

-- Tables are created automatically by AppContextListener on first startup.
-- Nothing else needed here.
