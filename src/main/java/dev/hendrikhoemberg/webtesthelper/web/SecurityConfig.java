package dev.hendrikhoemberg.webtesthelper.web;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
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
        LoginUrlAuthenticationEntryPoint loginEntryPoint = new LoginUrlAuthenticationEntryPoint("/anmelden");
        AuthenticationEntryPoint htmxAwareEntryPoint = (request, response, authException) -> {
            if ("true".equalsIgnoreCase(request.getHeader("HX-Request"))) {
                response.setHeader("HX-Redirect", "/anmelden");
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            } else {
                loginEntryPoint.commence(request, response, authException);
            }
        };

        http.headers(headers -> headers
                .contentSecurityPolicy(csp -> csp
                        .policyDirectives("default-src 'self'; script-src 'self' 'unsafe-inline' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self' ws: wss:; frame-ancestors 'self';")
                )
            )
            .requestCache(rc -> rc.requestCache(new SkipErrorDispatchRequestCache()))
            .exceptionHandling(ex -> ex.authenticationEntryPoint(htmxAwareEntryPoint))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/anmelden", "/vendor/**", "/css/**", "/favicon.ico",
                        "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                .requestMatchers("/einstellungen/**",
                        "/postausgang", "/postausgang/**", "/actuator/**",
                        "/websites/neu", "/websites/*/bearbeiten",
                        "/websites/*/einrichtung", "/websites/*/einrichtung/**",
                        "/websites/*/aufzeichnen", "/websites/*/recorder",
                        "/websites/*/journeys/*/bearbeiten", "/websites/*/journeys/*/bearbeiten/**",
                        "/recorder/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/websites", "/websites/*", "/websites/*/loeschen",
                        "/websites/*/zeitplaene",
                        "/websites/*/empfaenger", "/websites/*/empfaenger/**",
                        "/websites/*/zugangsdaten", "/websites/*/zugangsdaten/**",
                        "/websites/*/pruefungen", "/websites/*/pruefungen/**",
                        "/websites/*/journeys/*/loeschen",
                        "/websites/*/journeys/*/jetzt-ausfuehren",
                        "/laeufe/*/ausgangsbestand", "/laeufe/*/ausgangsbestand/**").hasRole("ADMIN")
                .requestMatchers("/stummschaltungen/**", "/stummschaltungen").authenticated()
                .requestMatchers("/tutorial/**").authenticated()
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

