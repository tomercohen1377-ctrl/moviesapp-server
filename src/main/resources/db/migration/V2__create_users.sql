CREATE TABLE users (
  user_id       VARCHAR(64)  NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  created_at    BIGINT       NOT NULL,
  PRIMARY KEY (user_id)
);
