package com.macstudio.backend.support;

import java.sql.DriverManager;
import java.util.Properties;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 사용자가 지정한 로컬 개발 mac에서 테스트 소유 데이터만 쓴다. DB/테이블 생성 SQL은 실행하지 않는다. */
@Tag("mysql-integration")
@ActiveProfiles("mysql-integration")
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.jdbc.time_zone=UTC",
        "spring.datasource.hikari.connection-init-sql=SET time_zone = '+00:00'",
        "app.storage.enabled=false",
        "logging.level.org.hibernate.engine.jdbc.spi.SqlExceptionHelper=OFF",
        // 공개 테스트 fixture이며 운영용 키가 아니다.
        "app.jwt.secret-base64=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE="
})
public abstract class LocalMySqlTestSupport {
    private static Properties settings;
    private static String testUrl;

    /** 로컬 설정을 비공개로 읽고 안전하게 준비한 테스트 DB만 Spring에 연결한다. */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        prepareDatabase();
        registry.add("spring.datasource.url", () -> testUrl);
        registry.add("spring.datasource.username", () -> settings.getProperty("spring.datasource.username"));
        registry.add("spring.datasource.password", () -> settings.getProperty("spring.datasource.password", ""));
    }

    /** 로컬 접속과 수동 준비된 테스트 DB의 존재를 확인한다. 스키마는 생성·변경하지 않는다. */
    private static synchronized void prepareDatabase() throws Exception {
        if (settings != null) return;
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource("src/main/resources/application-local.yml"));
        Properties local = yaml.getObject();
        if (local == null) throw new IllegalStateException("Local MySQL configuration is missing");
        String sourceUrl = local.getProperty("spring.datasource.url", "");
        // 호스트·DB 이름을 제한하고 URL의 자격 증명/추가 호스트 옵션을 전달하지 않는다.
        var matcher = java.util.regex.Pattern.compile(
                "^jdbc:mysql://(localhost|127\\.0\\.0\\.1):(\\d+)/mac(?:\\?.*)?$").matcher(sourceUrl);
        if (!matcher.matches()) throw new IllegalStateException("Expected local MySQL mac configuration");
        String base = "jdbc:mysql://" + matcher.group(1) + ":" + matcher.group(2) + "/";
        String user = local.getProperty("spring.datasource.username");
        String password = local.getProperty("spring.datasource.password", "");
        try (var connection = DriverManager.getConnection(base + "?connectionTimeZone=UTC", user, password);
             var statement = connection.createStatement()) {
            try (var result = statement.executeQuery(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = 'mac' ORDER BY table_name")) {
                var names = new java.util.ArrayList<String>();
                while (result.next()) names.add(result.getString(1));
                System.out.println("mac table inventory (read only): " + names);
            }
        }
        String targetUrl = base + "mac?connectionTimeZone=UTC";
        try (var connection = DriverManager.getConnection(targetUrl, user, password)) {
            if (!"mac".equals(connection.getCatalog())) {
                throw new IllegalStateException("Refusing tests outside local mac");
            }
            // 연결 실패 시 중단한다. DB와 테이블은 사용자가 직접 준비한다.
        }
        testUrl = targetUrl;
        settings = local;
    }
}

