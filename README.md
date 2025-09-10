# Multiselect Database Management System

## Supported SQL and NoSQL Databases

The DatabaseDriver is a management system for multiple database types, which can be managed via one interface.

| Database   | Usage                                                                                                        |
|------------|--------------------------------------------------------------------------------------------------------------|
| MySQL      | Widely used for web applications, content management systems (e.g., WordPress), and general relational data storage.|
| MariaDB    | Drop-in replacement for MySQL with improved performance, security features, and enterprise support; used in web and cloud applications.|
| PostgreSQL | Advanced relational database for complex queries, analytics, GIS (geospatial data), and enterprise applications needing strong standards compliance.|
| SQLite     | File-based, serverless database often used in mobile apps, embedded systems, small desktop tools, and prototyping. |
| H2DB       | Lightweight, in-memory or embedded database mainly for development, testing, or small applications where fast setup is needed. |
| MongoDB    | Document-oriented NoSQL database, great for handling flexible, semi-structured data (e.g., JSON), often used in scalable web and cloud apps. |
| RethinkDB  | Real-time NoSQL database optimized for apps requiring live updates and push notifications (e.g., chat apps, dashboards). |
| JsonFileDB | Very simple storage solution using JSON files; suitable for small projects, configs, or prototyping without the overhead of a full database server. |

To add the DatabaseDriver dependency using maven (replace `%version%` with the latest version shown in the badge above):

```xml
<!-- optional - you can also specify versions directly -->
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>de.lino.database</groupId>
      <artifactId>database-driver</artifactId>
      <version>%version%</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>de.lino.database</groupId>
    <artifactId>database-driver-api</artifactId>
    <version>%version%</version> <!-- only needed when bom is not used -->
    <scope>provided</scope>
  </dependency>
</dependencies>
```
### GitHub Repository
Get the Repository via git: `git clone https://github.com/linoalessio/database-driver-v2.git`

--- ---
## DatabaseDriver API
Before working with the driver, you need to make sure that the Repository Instance is initialized in your *main class*.
All methods can be **executed asynchronously**, just add the suffix ***'Async'*** and a ***[CompletableFuture](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/CompletableFuture.html)*** will be returned 
``` java
// Initialize Repository instance
new DefaultDatabaseRepository(); 
```

*Working with the DatabaseRepository*
``` java
/* 
* Credentials class automatically creates a config file if it doesn't exist, otherwise the data will be cached from the existing file
*
* Register a DatabaseProvider with an id (int), databaseType and the given credentials
* The method returns a DatabaseProvider.class
*
* If you use the JsonDatabaseProvider (DatabaseType.JSON), than you need the adjust your credentials to the following
* new Credentials(Paths.get("CONFIG_FILE_PATH"), Paths.get("JSON_DATABASE_REPOSITORY_PATH"));
*
*/
final Credentials credentials = new Credentials(Paths.get("config/sample-database.json"), "localhost" , "userName" , "password", port , "database");
final DatabaseProvider databaseProvider = DatabaseRepository.getInstance().registerDatabaseProvider(id, databaseType, credentials);

/*
* Get a DatabaseProvider from the cache with the registered id
* The method returns an Optional<DatabaseProvider> for Error-Handling options
*/
final DatabaseProvider cachedDatabaseProvider = DatabaseRepository.getInstance().findDatabaseProviderById(id).orElse(null);

/*
* Unregister an existing DatabaseProvider with a given id
* The method will automatically shutdown the connection to the database
*/
DatabaseRepository.getInstance().unregisterDatabaseProvider(id);

// Shutting down all registered DatabaseProvider
DatabaseRepository.getInstance().shutdown();

// Get all registered databases
final List<DatabaseProvider> providerPool = DatabaseRepository.getInstance().getDatabaseProviderPool();

/*
* Convert all entries of a specific DatabaseProvider (sourceId) to another one (targetId)
* Process will only succeed if both providers are running and the sections are created/cached
*/
DatabaseRepository.getInstance().convert(sourceId, targetId);
```

*Working with a DatabaseProvider*
``` java
/*
* Create a section/table with a specific name, otherwise the cached section will be returned
*/
final DatabaseSection databaseSection = databaseProvider.createSection(name);

// Delete a section/table if it exists
databaseProvider.deleteSection(name);

// Check whether a section exists
final boolean running = databaseProvider.exists(name);

// Get a specific section from the cache
final DatabaseSection cachedSection = databaseProvider.getSection(name);

// Get all registered sections
final List<DatabaseSection> sectionPool = databaseProvider.getSections();

// Shutting down a database provider
databaseProvider.shutdown();
```

*Working with a DatabaseSection*
``` java
/*
* Insert a new DatabaseEntry. First parameter in object is the id, second new document
*/
final DatabaseEntry entry = new DatabaseEntry("Lino", new JsonDocument("name", "lino").append("age", 23));
databaseSection.insert(entry)

/*
* Update the MetaData of an existing Entry
* The method returns an Optional<DatabaseEntry> for Error-Handling
*/
final DatabaseEntry existingEntry = databaseSection.findEntryById("Lino").orElse(null);
existingEntry.getMetaData().remove("age").append("country", "germany");
databaseSection.update(existingEntry);

/*
* If you want to add another document to the existing MetaData, just create a new Entry
* But use the same id to modify the wanted entry
*/
final Pet dog = new Pet("Fluffy", "Golden Retriever");
final DatabaseEntry modifiedMetaDataEntry = new DatabaseEntry("Lino", new JsonDocument().append("pet", dog));
databaseSection.update(modifiedMetaDataEntry);

// Delete an existing entry with the id
databaseSection.delete(id);

// Check whether the entry with the given id exists
final boolean isEntry = databaseSection.exists(id);

// Delete this DatabaseSection
databaseSection.delete();

// Count all entries
final long count = databaseSection.count();

// Get all existing entries
final List<DatabaseEntry> entries = databaseSection.getEntries();

// Example DatabaseEntry with id='Lino' and document='data'
{
  "id": "Lino",
  "data": {
    "name": "Lino",
    "age": 23, // age has been removed
    "country": "germany",
    "pet": {
      "name": "Fluffy",
      "kind": "Golden Retriever"
    }
  }
}
```
