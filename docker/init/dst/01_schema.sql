-- MyDog sync test fixture: TARGET database (drifted copy of shop)
CREATE DATABASE IF NOT EXISTS shop CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE shop;

-- t_users: schema identical to source, data drifted
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

-- data drift: bob modified, carol deleted, frank inserted (extra), eve missing
INSERT INTO t_users (id, username, email, is_active, balance) VALUES
  (1, 'alice', 'alice@example.com', 1, 100.50),
  (2, 'bob', 'bob@changed.example.com', 1, 55.00),
  (3, 'carol', 'carol@example.com', 0, 42.42),
  (6, 'frank', 'frank@example.com', 1, 1.00);

-- t_orders: schema drift — no `remark` column, no FK, no uk on order_no; data partly drifted
CREATE TABLE t_orders (
  order_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id BIGINT UNSIGNED NOT NULL,
  order_no VARCHAR(32) NOT NULL,
  amount DECIMAL(12,2) NOT NULL,
  status ENUM('pending','paid','shipped','closed') NOT NULL DEFAULT 'pending',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (order_id),
  KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='orders';

INSERT INTO t_orders (order_id, user_id, order_no, amount, status) VALUES
  (1001, 1, 'NO-0001', 10.00, 'paid'),
  (1002, 1, 'NO-0002', 25.50, 'shipped'),
  (1003, 2, 'NO-0003', 88.88, 'pending'),
  (1006, 5, 'NO-0006', 12.00, 'pending');

-- t_scratch: table EXTRA in target (candidate for DROP sync)
CREATE TABLE t_scratch (
  id INT NOT NULL AUTO_INCREMENT,
  note VARCHAR(200) DEFAULT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='legacy scratch (target only)';

-- t_nopk: table WITHOUT primary key (checksum/degraded path test)
CREATE TABLE t_nopk (
  col_a VARCHAR(50) NOT NULL,
  col_b INT NOT NULL,
  col_c DOUBLE DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO t_nopk VALUES ('a', 1, 1.5), ('b', 2, NULL), ('c', 3, 3.25);

-- v_user_stats: OLD version (no total_amount)
CREATE OR REPLACE VIEW v_user_stats AS
SELECT u.id, u.username, COUNT(o.order_id) AS order_count
FROM t_users u LEFT JOIN t_orders o ON o.user_id = u.id
GROUP BY u.id, u.username;

-- fn_get_user_balance: OLD version (rounds to integer)
DROP FUNCTION IF EXISTS fn_get_user_balance;
DELIMITER $$
CREATE FUNCTION fn_get_user_balance(p_user_id BIGINT UNSIGNED) RETURNS DECIMAL(12,2)
    DETERMINISTIC
    READS SQL DATA
BEGIN
    DECLARE v_balance DECIMAL(12,2);
    SELECT balance INTO v_balance FROM t_users WHERE id = p_user_id;
    RETURN ROUND(v_balance, 0);
END$$
DELIMITER ;
