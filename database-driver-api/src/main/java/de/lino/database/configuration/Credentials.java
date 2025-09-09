package de.lino.database.configuration;

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

import com.google.gson.JsonObject;
import de.lino.database.json.JsonDocument;
import de.lino.database.json.parser.DocumentJsonParser;
import lombok.Getter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Getter
public class Credentials {

    private static final Object UNKNOWN = "Unknown";

    private final Path configDestination;
    private String address, userName, password;
    private int port;
    private String database, fileRepository;

    /**
     * Credentials configuration with automatic save process in JSON file
     * @param configDestination: configuration file where the credentials will be saved
     * @param address: host address
     * @param userName: login username
     * @param password: verification password
     * @param port: database port
     * @param database: database name
     * @param fileRepository: repository where the file database shall save their data, only to use when JsonDatabaseProvider used
     */
    public Credentials(Path configDestination, String address, String userName, String password, int port, String database, Path fileRepository) {

        this.configDestination = configDestination;

        if (Files.notExists(configDestination)) {

            this.address = address;
            this.userName = userName;
            this.password = password;
            this.port = port;
            this.database = database;
            this.fileRepository = fileRepository.toString();

            new JsonDocument()
                    .append("address", address)
                    .append("userName", userName)
                    .append("password", password)
                    .append("port", port)
                    .append("database", database)
                    .append("fileRepository", fileRepository.toString())
                    .write(configDestination);

            return;
        }

        try (final InputStreamReader inputStreamReader = new InputStreamReader(Files.newInputStream(configDestination), StandardCharsets.UTF_8); final BufferedReader bufferedReader = new BufferedReader(inputStreamReader)) {

            final JsonObject jsonObject = DocumentJsonParser.parseReader(bufferedReader).getAsJsonObject();
            final JsonDocument jsonDocument = new JsonDocument(jsonObject);

            this.address = jsonDocument.getString("address");
            this.userName = jsonDocument.getString("userName");
            this.password = jsonDocument.getString("password");
            this.port = jsonDocument.getInteger("port");
            this.database = jsonDocument.getString("database");
            this.fileRepository = jsonDocument.getString("fileRepository");

        } catch (final Exception exception) {
            exception.printStackTrace();
        }

    }

    public Credentials(Path configDestination, String address, String userName, String password, int port, String database) {
        this(configDestination, address, userName, password, port, database, Paths.get(UNKNOWN.toString()));
    }

    public Credentials(Path configDestination, Path fileRepository) {
        this(configDestination, UNKNOWN.toString(), UNKNOWN.toString(), UNKNOWN.toString(), -1, UNKNOWN.toString(), fileRepository);
    }

}
