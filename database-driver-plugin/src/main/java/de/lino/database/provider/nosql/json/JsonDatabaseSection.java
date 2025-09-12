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

import com.google.common.collect.Lists;
import de.lino.database.configuration.Credentials;
import de.lino.database.json.JsonDocument;
import de.lino.database.json.file.FileProvider;
import de.lino.database.provider.DatabaseSection;
import de.lino.database.provider.entity.DatabaseEntry;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Getter
public class JsonDatabaseSection implements DatabaseSection {

    private final String name;
    private final Credentials credentials;

    private final Path parent;
    private final List<DatabaseEntry> entries;

    public JsonDatabaseSection(@NotNull String name, @NotNull Credentials credentials) {

        this.name = name;
        this.credentials = credentials;
        this.entries = Lists.newCopyOnWriteArrayList();
        this.parent = Paths.get(credentials.getFileRepository(), name);

        FileProvider.getInstance().createDirectory(this.parent);
        Arrays.stream(Objects.requireNonNull(this.parent.toFile().listFiles())).forEach(path -> {

            final String id = path.getName().replace(".json", "");
            final JsonDocument document = JsonDocument.load(path.toPath());
            this.entries.add(new DatabaseEntry(id, document));

        });

    }

    @Override
    public void insert(@NotNull DatabaseEntry databaseEntry) {

        if (this.exists(databaseEntry.getId())) return;

        new JsonDocument().append("id", databaseEntry).append("data", databaseEntry.getMetaData()).write(Paths.get(this.parent.toString(), databaseEntry.getId()) + ".json");
        this.entries.add(databaseEntry);

    }

    @Override
    public void update(@NotNull DatabaseEntry databaseEntry) {

        if (!this.exists(databaseEntry.getId())) return;

        if (databaseEntry.getDocument().contains("id")) {

            this.delete(databaseEntry.getId());
            this.insert(databaseEntry);

            return;
        }

        final JsonDocument data = Objects.requireNonNull(this.findEntryById(databaseEntry.getId()).orElse(null)).getMetaData();

        databaseEntry.getMetaData().asMap().forEach((key, value) -> data.getJsonObject().add(key, value));
        Objects.requireNonNull(this.findEntryById(databaseEntry.getId()).orElse(null))
                .getDocument()
                .append("id", databaseEntry.getId())
                .append("data", data)
                .write(Paths.get(this.parent.toString(), databaseEntry.getId()) + ".json");

        this.entries.remove(databaseEntry);
        this.entries.add(databaseEntry);


    }

    @Override
    public void delete(@NotNull String id) {

        if (!this.exists(id)) return;

        FileProvider.getInstance().deleteFile(Paths.get(this.parent.toString(), id + ".json"));
        this.entries.removeIf(databaseEntity -> databaseEntity.getId().equalsIgnoreCase(id));

    }

    @Override
    public long count() {
        return this.entries.size();
    }

    @Override
    public void delete() {
        FileProvider.getInstance().deleteDirectory(this.parent);
        this.entries.clear();
    }

    @Override
    public boolean exists(@NotNull String id) {
        return this.entries.stream().anyMatch(databaseEntity -> databaseEntity.getId().equalsIgnoreCase(id));
    }

    @Override
    public Optional<DatabaseEntry> findEntryById(@NotNull String id) {
        return Optional.ofNullable(this.entries.stream().filter(databaseEntity -> databaseEntity.getId().equalsIgnoreCase(id)).findFirst().orElse(null));
    }

}
