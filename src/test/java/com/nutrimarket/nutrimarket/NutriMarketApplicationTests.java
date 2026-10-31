package com.nutrimarket.nutrimarket;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.nutrimarket.nutrimarket.dao.auth.UserMapper;
import static org.assertj.core.api.Assertions.assertThat;
@org.springframework.test.context.ActiveProfiles("test")
@SpringBootTest(properties={"spring.config.import=", "debug=false", "spring.datasource.url=jdbc:h2:mem:context-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.sql.init.schema-locations=classpath:schema-h2.sql"})
class NutriMarketApplicationTests {
    @Autowired UserMapper users;
    @Autowired PasswordEncoder passwords;
    @Test void contextLoadsWithDefaultUser() {
        var admin=users.findByUsername("admin");
        assertThat(admin).isNotNull();
        assertThat(admin.enabled()).isTrue();
        assertThat(passwords.matches("elec5620",admin.passwordHash())).isTrue();
    }
}
