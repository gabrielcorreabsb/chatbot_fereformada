package br.com.fereformada.api.config;

import br.com.fereformada.api.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer; // Importante
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer; // Importante
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    // Removemos a injeção do CorsConfigurationSource manual
    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authz -> authz
                        // Swagger e Health Check (público)
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()

                        // Permite OPTIONS (Preflight CORS)
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // 🔒 ENDPOINTS PÚBLICOS (sem autenticação)
                        .requestMatchers("/api/auth/**").permitAll() // Login, registro, etc

                        // 🔐 ENDPOINTS DE USUÁRIO (requer autenticação)
                        .requestMatchers("/api/chat/**").authenticated()
                        .requestMatchers("/api/feedback/**").authenticated()
                        .requestMatchers("/api/query/**").authenticated()
                        .requestMatchers("/api/leitor/**").authenticated()

                        // 👑 ENDPOINTS DE ADMIN (requer role ADMIN ou MODERATOR)
                        .requestMatchers("/api/admin/**").hasAnyRole("ADMIN", "MODERATOR")

                        // Qualquer outra rota exige autenticação
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}