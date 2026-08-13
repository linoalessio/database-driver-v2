package de.lino.database.provider.nosql.json;

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

import com.google.common.collect.Maps;
import de.lino.database.configuration.Credentials;
import de.lino.database.json.file.FileProvider;
import de.lino.database.provider.DatabaseProvider;
import de.lino.database.provider.DatabaseSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.UnmodifiableView;

import java.nio.file.Paths;
import java.util.*;

/**
 * The file-based {@link DatabaseProvider}: every {@link DatabaseSection} is a subdirectory of
 * {@link Credentials}'s {@code getFileRepository()}, holding one JSON file per entry, via
 * {@link JsonDatabaseSection}.
 */
public class JsonDatabaseProvider implements DatabaseProvider {

    /**
     * The login credentials this provider was constructed with, needed to resolve every new
     * {@link JsonDatabaseSection}'s subdirectory under {@link Credentials}'s {@code getFileRepository()}.
     */
    private final Credentials credentials;

    /**
     * Every registered section, keyed by name.
     */
    private final Map<String, DatabaseSection> databaseSections;

    /**
     * Loads every existing subdirectory of {@code credentials}' file repository as a
     * {@link JsonDatabaseSection}.
     *
     * @param credentials the login credentials, providing the file repository root this
     *                    provider's sections live under
     */
    public JsonDatabaseProvider(@NotNull Credentials credentials) {

        this.credentials = credentials;
        this.databaseSections = Maps.newConcurrentMap();

        FileProvider.getInstance().createDirectory(Paths.get(credentials.getFileRepository()));
        Arrays.stream(Objects.requireNonNull(Paths.get(credentials.getFileRepository()).toFile().listFiles())).forEach(path -> {

            final String name = path.getName();
            final DatabaseSection databaseSection = new JsonDatabaseSection(name, credentials);
            this.databaseSections.put(name, databaseSection);

        });

    }

    @Override
    public void shutdown() {
    }

    @Override
    public DatabaseSection createSection(@NotNull String name) {
        return this.databaseSections.computeIfAbsent(name, key -> new JsonDatabaseSection(key, this.credentials));
    }

    @Override
    public void deleteSection(@NotNull String name) {
        FileProvider.getInstance().deleteDirectory(Paths.get(this.credentials.getFileRepository(), name));
        this.databaseSections.remove(name);
    }

    @Override
    public boolean existsSection(@NotNull String name) {
        return this.databaseSections.containsKey(name);
    }

    @Override
    public @UnmodifiableView List<DatabaseSection> getSections() {
        return List.copyOf(this.databaseSections.values());
    }

    @Override
    public Optional<DatabaseSection> getSection(@NotNull String name) {
        return Optional.ofNullable(this.databaseSections.get(name));
    }

    @Override
    public void clear() {
        for (DatabaseSection databaseSection : this.getSections()) databaseSection.clear();
        this.databaseSections.clear();
    }

}
