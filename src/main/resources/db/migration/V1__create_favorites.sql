CREATE TABLE favorites (
  user_id  VARCHAR(64) NOT NULL,
  movie_id INTEGER     NOT NULL,
  saved_at BIGINT      NOT NULL,
  PRIMARY KEY (user_id, movie_id)
);
