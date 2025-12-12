package br.com.fereformada.api.config;

import br.com.fereformada.api.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CorsConfigurationSource corsConfigurationSource;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          CorsConfigurationSource corsConfigurationSource) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.corsConfigurationSource = corsConfigurationSource;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // ✅ IMPORTANTE: Habilita CORS usando a configuração do WebConfig
                .cors(cors -> cors.configurationSource(corsConfigurationSource))

                // Desabilita CSRF (não necessário para APIs stateless)
                .csrf(csrf -> csrf.disable())

                // Define a política de sessão como STATELESS
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // Define as regras de autorização
                .authorizeHttpRequests(authz -> authz
                        // ✅ CRÍTICO: Permite OPTIONS sem autenticação (requisições preflight)
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // Protege sua API v1
                        .requestMatchers("/api/v1/**").authenticated()

                        // Exige autenticação para todos os endpoints de admin
                        .requestMatchers("/api/admin/**").authenticated()

                        // Permite todo o resto (ajuste conforme necessário)
                        .anyRequest().permitAll()
                )

                // Adiciona seu filtro JWT antes do filtro padrão do Spring
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}