CREATE TABLE P1 (
  ID INTEGER NOT NULL,
  TINY TINYINT NOT NULL,
  SMALL SMALLINT NOT NULL,
  BIG BIGINT NOT NULL,
  PRIMARY KEY (ID)
);
PARTITION TABLE P1 ON COLUMN ID;

CREATE TABLE R1 (
  ID INTEGER NOT NULL,
  TINY TINYINT NOT NULL,
  SMALL SMALLINT NOT NULL,
  BIG BIGINT NOT NULL,
  PRIMARY KEY (ID)
);

CREATE VIEW MATP1 (BIG, ID, NUM, IDCOUNT, TINYCOUNT, SMALLCOUNT, BIGCOUNT, TINYSUM, SMALLSUM) AS
  SELECT BIG, ID, COUNT(*), COUNT(ID), COUNT(TINY), COUNT(SMALL), COUNT(BIG), SUM(TINY), SUM(SMALL)
  FROM P1
  GROUP BY BIG, ID;

CREATE VIEW MATR1 (BIG, NUM, TINYSUM, SMALLSUM) AS
  SELECT BIG, COUNT(*), SUM(TINY), SUM(SMALL)
  FROM R1 WHERE ID > 5
  GROUP BY BIG;
  
--
CREATE TABLE P2 (
  ID INTEGER NOT NULL,
  WAGE SMALLINT,
  DEPT SMALLINT,
  AGE SMALLINT,
  RENT SMALLINT,
  PRIMARY KEY (ID)
);
PARTITION TABLE P2 ON COLUMN ID;
--
CREATE VIEW V_P2 (V_G1, V_G2, V_CNT, V_sum_age, V_sum_rent) AS 
	SELECT wage, dept, count(*), sum(age), sum(rent)  FROM P2
	GROUP BY wage, dept;

CREATE VIEW V_P2_ABS (V_G1, V_G2, V_CNT, V_sum_age, V_sum_rent) AS 
	SELECT wage, dept, count(*), sum(age), sum(rent)  FROM P2 
	GROUP BY wage, dept;


CREATE TABLE R2 (
  ID INTEGER NOT NULL,
  WAGE SMALLINT,
  DEPT SMALLINT,
  AGE SMALLINT,
  RENT SMALLINT,
  PRIMARY KEY (ID)
);

CREATE VIEW V_R2 (V_G1, V_G2, V_CNT, V_sum_age, V_sum_rent) AS 
	SELECT wage, dept, count(*), sum(age), sum(rent)  FROM R2 
	GROUP BY wage, dept;