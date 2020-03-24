package io.tokern.dbaudit.core.Flyway.cli;

import io.dropwizard.Configuration;
import io.dropwizard.db.DatabaseConfiguration;
import io.tokern.dbaudit.core.Flyway.FlywayConfiguration;
import net.sourceforge.argparse4j.inf.Namespace;
import org.flywaydb.core.Flyway;

import static org.flywaydb.core.internal.info.MigrationInfoDumper.dumpToAsciiTable;

public class DbInfoCommand<T extends Configuration> extends AbstractFlywayCommand<T> {
    public DbInfoCommand(final DatabaseConfiguration<T> databaseConfiguration,
                         final FlywayConfiguration<T> flywayConfiguration,
                         final Class<T> configurationClass) {
        super("info", "Prints the details and status information about all the migrations.",
                databaseConfiguration, flywayConfiguration, configurationClass);
    }

    @Override
    public void run(final Namespace namespace, final Flyway flyway) throws Exception {
        System.out.println(dumpToAsciiTable(flyway.info().all()));
    }
}
