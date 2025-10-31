package br.com.fereformada.api.security;

import io.jsonwebtoken.Claims; // Importar Claims
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider tokenProvider;

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String token = getTokenFromRequest(request);

            if (token != null) {
                // 1. Pega todos os "claims" de uma vez
                Claims claims = tokenProvider.getClaims(token);

                // 2. Extrai o ID do usuário (Subject)
                String userId = claims.getSubject();

                // 3. LÓGICA ATUALIZADA: Extrai as roles dos claims
                List<String> roles = tokenProvider.getRoles(claims);

                // 4. Converte as strings (ex: "ADMIN") para o formato do Spring (ex: "ROLE_ADMIN")
                List<SimpleGrantedAuthority> authorities = roles.stream()
                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                        .collect(Collectors.toList());

                // 5. Cria o token de autenticação do Spring com as roles corretas
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        userId, // O "principal" é o ID do usuário
                        null,
                        authorities // As permissões (ex: [ROLE_ADMIN, ROLE_USER])
                );

                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        } catch (Exception ex) {
            // Logar falha, mas não quebrar a requisição
            logger.warn("Não foi possível autenticar o usuário: " + ex.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    private String getTokenFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}