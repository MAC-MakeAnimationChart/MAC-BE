package com.macstudio.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.SpringApplication;
import org.springframework.core.io.FileSystemResource;

@Tag("mysql")
class MySqlConnectionTests {
    @Test
    void createsMacDatabaseAndConnectsThroughSpringDataSource() throws Exception {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource("src/main/resources/application-local.yml"));
        var properties = yaml.getObject();
        assertThat(properties).isNotNull();
        String url = properties.getProperty("spring.datasource.url");
        // This opt-in task is deliberately limited to the requested mac database.
        assertThat(url).contains("/mac?");
        String serverUrl = url.replace("/mac?", "/?");
        String username = properties.getProperty("spring.datasource.username");
        String password = properties.getProperty("spring.datasource.password", "");
        try (var connection = DriverManager.getConnection(serverUrl, username, password);
             var statement = connection.createStatement()) {
            statement.execute(Files.readString(Path.of("sql/00_create_database.sql")));
        }

        try (var context = SpringApplication.run(MacStudioApplication.class,
                "--spring.profiles.active=local", "--server.port=0");
             var connection = context.getBean(DataSource.class).getConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT DATABASE(), CURRENT_USER(), VERSION(), 1")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("mac");
            assertThat(result.getString(2)).startsWith("root@");
            assertThat(result.getInt(4)).isEqualTo(1);
            System.out.println("MySQL connection verified: database=" + result.getString(1)
                    + ", user=" + result.getString(2) + ", version=" + result.getString(3));
        }
    }
}
