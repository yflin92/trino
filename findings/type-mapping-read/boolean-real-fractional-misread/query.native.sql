SELECT flag, typeof(flag), CASE WHEN flag THEN 'true' ELSE 'false' END AS sqlite_truth FROM t;
