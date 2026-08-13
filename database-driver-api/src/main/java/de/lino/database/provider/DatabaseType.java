package de.lino.database.provider;

/*
 * MIT License
 *
 * Copyright (c) lino, 08.09.2025
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

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
