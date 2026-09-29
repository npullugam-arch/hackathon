package com.iare.hackathon.database;

import java.util.TreeMap;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import static org.junit.jupiter.api.Assertions.*;

class MigrationResourceTests {
    @Test void packagedMigrationsHaveUniqueVersions() throws Exception {
        var versions = new TreeMap<MigrationVersion, String>();
        var resources = new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/V*__*.sql");
        assertTrue(resources.length > 0, "Database migrations must be packaged");
        for (var resource : resources) {
            String name = resource.getFilename();
            assertNotNull(name);
            var version = MigrationVersion.fromVersion(name.substring(1, name.indexOf("__")));
            var previous = versions.putIfAbsent(version, name);
            assertNull(previous, () -> "Duplicate Flyway version " + version + ": " + previous + " and " + name);
        }
    }
}
