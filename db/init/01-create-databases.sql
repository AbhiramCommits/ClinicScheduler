-- Creates both service databases on first MySQL container start.
-- MySQL runs this as root; grants are given to the compose application user.
CREATE DATABASE IF NOT EXISTS provider_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS appointment_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

GRANT ALL PRIVILEGES ON provider_db.* TO 'clinic'@'%';
GRANT ALL PRIVILEGES ON appointment_db.* TO 'clinic'@'%';
FLUSH PRIVILEGES;
