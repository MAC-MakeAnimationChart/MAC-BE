package com.macstudio.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

@Tag("mysql")
class MySqlConnectionTests {
    /** 사용자가 준비한 mac DB에 SELECT만 수행하며 DB/테이블 생성은 하지 않는다. */
    @Test
    void connectsToExistingMacDatabaseWithoutSchemaChanges() throws Exception {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource("src/main/resources/application-local.yml"));
        var properties = yaml.getObject();
        assertThat(properties).isNotNull();
        String url = properties.getProperty("spring.datasource.url");
        // 이 명시적 테스트는 사용자가 생성한 mac DB 연결만 확인한다.
        assertThat(url).contains("/mac?");
        String username = properties.getProperty("spring.datasource.username");
        String password = properties.getProperty("spring.datasource.password", "");
        try (var connection = DriverManager.getConnection(url, username, password);
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
