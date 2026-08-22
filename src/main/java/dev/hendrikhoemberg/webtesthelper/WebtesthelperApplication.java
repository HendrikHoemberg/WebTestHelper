package dev.hendrikhoemberg.webtesthelper;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class WebtesthelperApplication {

	public static void main(String[] args) {
		SpringApplication.run(WebtesthelperApplication.class, args);
	}

}
