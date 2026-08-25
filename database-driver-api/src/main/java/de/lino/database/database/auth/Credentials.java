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
import java.util.Objects;
import java.util.Optional;

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
     * The full constructor every other constructor in this class delegates to. If
     * {@code configDestination} does not exist yet, every argument is written to it as JSON and
     * also assigned directly to this instance's fields; otherwise the arguments other than
     * {@code configDestination} are discarded and the existing file's values are loaded instead,
     * so the config file - not the caller - is the source of truth after the first run. Any
     * failure while reading an existing file is caught and printed rather than thrown, leaving
     * this instance with unset fields.
     *
     * @param configDestination configuration file where the credentials will be saved, or read
     *                          back from if it already exists
     * @param address           host address
     * @param userName          login username
     * @param password          verification password
     * @param port              database port
     * @param database          database name
     * @param fileRepository    repository where the file database shall save its data; only
     *                          meaningful for file-based providers such as
     *                          {@code JsonDatabaseProvider}
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

    /**
     * Reads an already-persisted {@code Credentials} configuration back from disk without
     * writing anything, unlike the constructors above which create {@code configDestination} if
     * it is missing. Returns {@link Optional#empty()} both when {@code configDestination} does
     * not exist and when reading or parsing it fails - the failure case is logged via
     * {@link Exception#printStackTrace()} rather than propagated, matching the constructors'
     * error handling.
     *
     * @param configDestination the configuration file to read
     * @return the parsed {@code Credentials}, or {@link Optional#empty()} if the file is absent
     *         or unreadable
     */
    public static Optional<Credentials> of(@NotNull Path configDestination) {

        Objects.requireNonNull(configDestination, "@Credentials.of: configDestination must not be null");
        if (Files.notExists(configDestination)) return Optional.empty();

        try (final InputStreamReader inputStreamReader = new InputStreamReader(Files.newInputStream(configDestination), StandardCharsets.UTF_8); final BufferedReader bufferedReader = new BufferedReader(inputStreamReader)) {

            final JsonObject jsonObject = DocumentJsonParser.parseReader(bufferedReader).getAsJsonObject();
            final Credentials credentials = getCredentials(configDestination, jsonObject);

            return Optional.of(credentials);

        } catch (final Exception exception) {
            exception.printStackTrace();
        }

        return Optional.empty();
    }

    /**
     * Builds a {@code Credentials} from an already-parsed {@code jsonObject}, by feeding its
     * fields into the full constructor. {@code configDestination} is known to already exist at
     * this point (checked by {@link #of}), so this always takes that constructor's
     * read-existing-file branch - the field values extracted here are what get discarded in
     * favor of a second, redundant read of the same file, rather than passed through directly.
     *
     * @param configDestination the configuration file {@code jsonObject} was parsed from
     * @param jsonObject        the already-parsed contents of {@code configDestination}
     * @return the resulting {@code Credentials}
     */
    private static @NotNull Credentials getCredentials(@NotNull Path configDestination, JsonObject jsonObject) {
        final JsonDocument jsonDocument = new JsonDocument(jsonObject);
        return new Credentials(
                configDestination
                , jsonDocument.getString("address")
                , jsonDocument.getString("userName")
                , jsonDocument.getString("password")
                , jsonDocument.getInteger("port")
                , jsonDocument.getString("database")
                , Path.of(jsonDocument.getString("fileRepository"))
        );
    }

}
