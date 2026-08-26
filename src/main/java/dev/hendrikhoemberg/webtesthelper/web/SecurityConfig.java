package dev.hendrikhoemberg.webtesthelper.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableWebSecurity
public class SecurityConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/anmelden").setViewName("anmelden");
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/anmelden", "/vendor/**", "/css/**", "/favicon.ico").permitAll()
                .requestMatchers("/einstellungen/**",
                        "/postausgang", "/actuator/**",
                        "/websites/neu", "/websites/*/bearbeiten").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/websites", "/websites/*", "/websites/*/zeitplaene",
                        "/websites/*/empfaenger", "/websites/*/empfaenger/**").hasRole("ADMIN")
                .requestMatchers("/stummschaltungen/**", "/stummschaltungen").authenticated()
                .requestMatchers(HttpMethod.GET, "/websites/*/einrichtung", "/websites/*/einrichtung/stand")
                        .authenticated()
                .requestMatchers(HttpMethod.POST, "/websites/*/einrichtung", "/websites/*/einrichtung/neu")
                        .authenticated()
                .anyRequest().authenticated())
            .formLogin(login -> login.loginPage("/anmelden").defaultSuccessUrl("/", false).permitAll())
            .logout(out -> out.logoutUrl("/abmelden").logoutSuccessUrl("/anmelden?abgemeldet"));

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public org.springframework.security.web.firewall.RequestRejectedHandler requestRejectedHandler() {
        return new org.springframework.security.web.firewall.HttpStatusRequestRejectedHandler(org.springframework.http.HttpStatus.NOT_FOUND.value());
    }
}

