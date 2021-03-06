package io.tokern.dbaudit;

import io.dropwizard.db.ManagedDataSource;
import io.dropwizard.setup.Environment;
import io.dropwizard.testing.junit5.DropwizardAppExtension;
import io.tokern.dbaudit.api.Database;
import io.tokern.dbaudit.api.Organization;
import io.tokern.dbaudit.api.Query;
import io.tokern.dbaudit.api.User;
import io.tokern.dbaudit.core.auth.PasswordDigest;
import io.tokern.dbaudit.db.DatabaseDAO;
import io.tokern.dbaudit.db.OrganizationDAO;
import io.tokern.dbaudit.db.QueryDAO;
import io.tokern.dbaudit.db.UserDAO;
import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ApplicationDatabaseSetup extends DropwizardAppExtension.ServiceListener<DbAuditConfiguration> {
  private static Logger logger = LoggerFactory.getLogger(ApplicationDatabaseSetup.class);
  private Flyway flyway;

  public void onRun(DbAuditConfiguration configuration,
                    Environment environment, DropwizardAppExtension<DbAuditConfiguration> rule) throws Exception {
    logger.info("In DatabaseSetup OnRun");
    ManagedDataSource dataSource = configuration.getDataSourceFactory()
        .build(environment.metrics(), "flyway");
    flyway = configuration.getFlywayFactory().build(dataSource);
    flyway.migrate();

    Class.forName("org.postgresql.Driver");

    Jdbi jdbi = Jdbi.create(dataSource);
    jdbi.installPlugin(new SqlObjectPlugin());
    Long orgId = jdbi.withExtension(OrganizationDAO.class, dao -> dao.insert(new Organization("Tokern", "http://tokern.io")));
    Long adminId = jdbi.withExtension(UserDAO.class, dao -> dao.insert(new User(
        "tokern_root", "root@tokern.io",
        PasswordDigest.generateFromPassword("passw0rd").getDigest(),
        User.SystemRoles.ADMIN,
        orgId.intValue()
    )));

    Long dbAdminId = jdbi.withExtension(UserDAO.class, dao -> dao.insert(new User(
        "tokern_db", "db@tokern.io",
        PasswordDigest.generateFromPassword("passw0rd").getDigest(),
        User.SystemRoles.DBADMIN,
        orgId.intValue()
    )));

    Long userId = jdbi.withExtension(UserDAO.class, dao -> dao.insert(new User(
        "tokern_user", "user@tokern.io",
        PasswordDigest.generateFromPassword("passw0rd").getDigest(),
        User.SystemRoles.USER,
        orgId.intValue()
    )));

    jdbi.useExtension(UserDAO.class, dao -> dao.insert(new User(
        "tokern_put", "put@tokern.io",
        PasswordDigest.generateFromPassword("putw0rd").getDigest(),
        User.SystemRoles.USER,
        orgId.intValue()
    )));

    jdbi.useExtension(UserDAO.class, dao -> dao.insert(new User(
        "tokern_logout", "logout@tokern.io",
        PasswordDigest.generateFromPassword("l0g0ut").getDigest(),
        User.SystemRoles.USER,
        orgId.intValue()
    )));

    // Insert a few databases
    jdbi.useExtension(DatabaseDAO.class, dao -> dao.insert(new Database(
        "BastionDb",
        "jdbc:postgresql://localhost/bastiondb?currentSchema=bastion_app",
        "bastion",
        Database.encryptPassword("passw0rd", configuration.getEncryptionSecret()),
        "POSTGRESQL",
        orgId.intValue()
    )));

    jdbi.useExtension(DatabaseDAO.class, dao -> dao.insert(new Database(
        "Bastion2",
        "jdbc://localhost/bastion2",
        "bastion_user",
        Database.encryptPassword("bastion_password", configuration.getEncryptionSecret()),
        "MYSQL",
        orgId.intValue()
    )));

    // Insert a few databases
    jdbi.useExtension(DatabaseDAO.class, dao -> dao.insert(new Database(
        "BastionDb3",
        "jdbc:postgresql://localhost/bastiondb?currentSchema=bastion_app",
        "bad_name",
        Database.encryptPassword("bad_passw0rd", configuration.getEncryptionSecret()),
        "POSTGRESQL",
        orgId.intValue()
    )));

    //Insert a few queries
    jdbi.useExtension(QueryDAO.class, dao -> dao.insert(new Query(
        "select 1",
        userId,
        1,
        orgId,
        "WAITING"
    )));
    jdbi.useExtension(QueryDAO.class, dao -> dao.insert(new Query(
        "select 2",
        dbAdminId,
        1,
        orgId,
        "WAITING"
    )));
  }

  public void onStop(DropwizardAppExtension<DbAuditConfiguration> rule) throws Exception {
    logger.info("In DatabaseSetup OnStop");
    flyway.clean();
  }
}
