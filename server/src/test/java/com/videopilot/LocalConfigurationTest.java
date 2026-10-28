package com.videopilot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "MYSQL_HOST=configured-mysql",
    "MYSQL_PORT=13306",
    "MYSQL_DATABASE=configured_database",
    "MYSQL_USER=configured_user",
    "MYSQL_PASSWORD=configured_test_value",
    "management.endpoint.health.validate-group-membership=false",
    "videopilot.health.mysql.enabled=false",
    "videopilot.health.external.enabled=false",
    "spring.autoconfigure.exclude="
            + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
})
@ActiveProfiles("local")
class LocalConfigurationTest {

    @Autowired
    private Environment environment;

    @Test
    void localProfileMapsEnvironmentVariablesToTheDatasource() {
        assertThat(environment.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:mysql://configured-mysql:13306/configured_database"
                        + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        assertThat(environment.getProperty("spring.datasource.username"))
                .isEqualTo("configured_user");
        assertThat(environment.getProperty("spring.datasource.password"))
                .isEqualTo("configured_test_value");
    }
}
