package de.lino.database.database.auth;

import com.google.gson.JsonObject;
import de.lino.database.json.JsonDocument;
import de.lino.database.json.parser.DocumentJsonParser;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Holds the connection details required to reach a database backend (host, credentials, port,
 * database name and, for file-based providers, a repository directory), and transparently
 * persists them to a JSON configuration file.
 * <p>
 * On construction, if {@link #configDestination} does not exist yet, the given values are
 * written to it as JSON; otherwise the existing file is read and its values are loaded instead,
 * ignoring the constructor arguments other than {@code configDestination}.
 */
@Getter
public class Credentials {

    /**
     * Placeholder value used for fields that are not applicable to a given database
     * (e.g. host/credentials for the file-based JSON database).
     */
    private static final Object UNKNOWN = "Unknown";

    /**
     * The file this configuration is persisted to and loaded from.
     */
    private final Path configDestination;

    /**
     * The host address, login username and password used to authenticate against the database.
     */
    private String address, userName, password;

    /**
     * The port the database is listening on.
     */
    private int port;

    /**
     * The name of the database to connect to, and, for file-based providers, the directory used
     * to store their data.
     */
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
    public Credentials(@NotNull Path configDestination, @NotNull String address, @NotNull String userName, @NotNull String password, int port, @NotNull String database, @NotNull Path fileRepository) {

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

    /**
     * Convenience constructor for network-based providers that do not require a dedicated file
     * repository; delegates to {@link #Credentials(Path, String, String, String, int, String, Path)}
     * with {@link #UNKNOWN} as the file repository.
     *
     * @param configDestination configuration file where the credentials will be saved
     * @param address           host address
     * @param userName          login username
     * @param password          verification password
     * @param port              database port
     * @param database          database name
     */
    public Credentials(@NotNull Path configDestination, @NotNull String address, @NotNull String userName, @NotNull String password, int port, @NotNull String database) {
        this(configDestination, address, userName, password, port, database, Paths.get(UNKNOWN.toString()));
    }

    /**
     * Convenience constructor for the file-based JSON database, which only requires a file
     * repository and no network connection details; delegates to
     * {@link #Credentials(Path, String, String, String, int, String, Path)} with
     * {@link #UNKNOWN} placeholders for every network-related field.
     *
     * @param configDestination configuration file where the credentials will be saved
     * @param fileRepository    repository where the file database shall save its data
     */
    public Credentials(@NotNull Path configDestination, @NotNull Path fileRepository) {
        this(configDestination, UNKNOWN.toString(), UNKNOWN.toString(), UNKNOWN.toString(), -1, UNKNOWN.toString(), fileRepository);
    }

}
