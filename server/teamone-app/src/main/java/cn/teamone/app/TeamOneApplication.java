package cn.teamone.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = "cn.teamone")
@EnableJpaRepositories(basePackages = "cn.teamone")
@EntityScan(basePackages = "cn.teamone")
@ConfigurationPropertiesScan
public class TeamOneApplication {
    public static void main(String[] args) {
        SpringApplication.run(TeamOneApplication.class, args);
    }
}
