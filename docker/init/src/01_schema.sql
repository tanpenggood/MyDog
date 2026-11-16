-- MyDog sync test fixture: SOURCE database (authoritative)
CREATE DATABASE IF NOT EXISTS shop CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE shop;

-- t_users: PK table with data
CREATE TABLE t_users (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  username VARCHAR(64) NOT NULL,
  email VARCHAR(128) DEFAULT NULL,
  is_active TINYINT(1) NOT NULL DEFAULT 1,
  balance DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_username (username),
  KEY idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='users';

INSERT INTO t_users (id, username, email, is_active, balance) VALUES
  (1, 'alice', 'alice@example.com', 1, 100.50),
  (2, 'bob', NULL, 1, 0.00),
  (3, 'carol', 'carol@example.com', 0, 42.42),
  (4, 'dave', 'dave@example.com', 1, 999.99),
  (5, 'eve', 'eve@example.com', 1, 7.30);

-- t_orders: composite PK + FK, differs in target
CREATE TABLE t_orders (
  order_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id BIGINT UNSIGNED NOT NULL,
  order_no VARCHAR(32) NOT NULL,
  amount DECIMAL(12,2) NOT NULL,
  status ENUM('pending','paid','shipped','closed') NOT NULL DEFAULT 'pending',
  remark VARCHAR(255) DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (order_id),
  UNIQUE KEY uk_order_no (order_no),
  KEY idx_user (user_id),
  CONSTRAINT fk_orders_user FOREIGN KEY (user_id) REFERENCES t_users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='orders';

INSERT INTO t_orders (order_id, user_id, order_no, amount, status) VALUES
  (1001, 1, 'NO-0001', 10.00, 'paid'),
  (1002, 1, 'NO-0002', 25.50, 'shipped'),
  (1003, 2, 'NO-0003', 99.99, 'pending'),
  (1004, 3, 'NO-0004', 3.14, 'closed'),
  (1005, 5, 'NO-0005', 200.00, 'paid');

-- t_logs: exists ONLY in source (target missing)
CREATE TABLE t_logs (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT UNSIGNED NOT NULL,
  action VARCHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='audit logs (source only)';

INSERT INTO t_logs (user_id, action) VALUES (1, 'login'), (1, 'view_order'), (2, 'login');

-- v_user_stats: view (target has older version)
CREATE OR REPLACE VIEW v_user_stats AS
SELECT u.id, u.username, COUNT(o.order_id) AS order_count, COALESCE(SUM(o.amount),0) AS total_amount
FROM t_users u LEFT JOIN t_orders o ON o.user_id = u.id
GROUP BY u.id, u.username;

-- fn_get_user_balance: function (target has older version)
DROP FUNCTION IF EXISTS fn_get_user_balance;
DELIMITER $$
CREATE FUNCTION fn_get_user_balance(p_user_id BIGINT UNSIGNED) RETURNS DECIMAL(12,2)
    DETERMINISTIC
    READS SQL DATA
BEGIN
    DECLARE v_balance DECIMAL(12,2);
    SELECT balance INTO v_balance FROM t_users WHERE id = p_user_id;
    RETURN v_balance;
END$$
DELIMITER ;

-- sp_archive_old_orders: procedure (missing in target)
DROP PROCEDURE IF EXISTS sp_archive_old_orders;
DELIMITER $$
CREATE PROCEDURE sp_archive_old_orders(IN p_days INT)
BEGIN
    DELETE FROM t_orders
    WHERE status = 'closed' AND created_at < NOW() - INTERVAL p_days DAY;
END$$
DELIMITER ;

-- trg_users_audit: trigger (missing in target)
DROP TRIGGER IF EXISTS trg_users_audit;
DELIMITER $$
CREATE TRIGGER trg_users_audit AFTER UPDATE ON t_users FOR EACH ROW
BEGIN
    INSERT INTO t_logs (user_id, action) VALUES (NEW.id, CONCAT('update:', OLD.username));
END$$
DELIMITER ;
