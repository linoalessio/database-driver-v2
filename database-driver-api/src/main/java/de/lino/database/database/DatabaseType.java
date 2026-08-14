package de.lino.database.database;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Enumerates every database backend supported by the driver, together with the metadata
 * required to connect to it: its short type identifier (used e.g. to build JDBC URLs) and the
 * fully qualified class name of its driver.
 */
@Getter
@RequiredArgsConstructor
public enum DatabaseType {

    /**
     * MySQL, accessed through its JDBC driver.
     */
    MY_SQL("mysql", "com.mysql.cj.jdbc.Driver"),

    /**
     * PostgreSQL, accessed through its JDBC driver.
     */
    POSTGRES_SQL("postgresql", "org.postgresql.Driver"),

    /**
     * H2, accessed through its JDBC driver.
     */
    H2_DB("h2", "org.h2.Driver"),

    /**
     * MongoDB, accessed through the MongoDB Java driver.
     */
    MONGO_DB("mongo", "com.mongodb.MongoDriver"),

    /**
     * RethinkDB, accessed through the RethinkDB Java driver.
     */
    RETHINK_DB("rethink", "com.rethinkdb.Driver"),

    /**
     * A local, file-based JSON store; not backed by any JDBC driver.
     */
    JSON("json", "NULL"),

    CSV("csv", "NULL"),

    /**
     * MariaDB, accessed through its JDBC driver.
     */
    MARIA_DB("mariadb", "org.mariadb.jdbc.Driver"),

    /**
     * SQLite, accessed through its JDBC driver.
     */
    SQLITE("sqlite", "org.sqlite.JDBC"),

    /**
     * Oracle Database, accessed through its JDBC thin driver.
     */
    ORACLE("oracle:thin", "oracle.jdbc.OracleDriver"),

    /**
     * Microsoft SQL Server, accessed through its JDBC driver.
     */
    MICROSOFT_SQL_SERVER("sqlserver", "com.microsoft.sqlserver.jdbc.SQLServerDriver"),

    /**
     * Apache Derby, accessed through its embedded JDBC driver.
     */
    APACHE_DERBY("derby:memory", "org.apache.derby.jdbc.EmbeddedDriver"),

    /**
     * Redis, accessed through the Jedis client.
     */
    REDIS("redis", "com.redis.Driver");

    /**
     * The short identifier of this database type (e.g. used to compose JDBC URLs) and the fully
     * qualified class name of its driver.
     */
    private final String type, driverClass;

}
