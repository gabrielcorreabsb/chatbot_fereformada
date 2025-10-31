package br.com.fereformada.api.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class JwtTokenProvider {

    private final SecretKey secretKey;

    public JwtTokenProvider(@Value("${supabase.jwt.secret}") String jwtSecret) {
        this.secretKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    // Valida o token e extrai os "claims" (dados)
    public Claims getClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(secretKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    // Pega o ID do usuário (no Supabase, o ID está no campo 'subject')
    public String getUserId(String token) {
        return getClaims(token).getSubject();
    }

    /**
     * NOVO MÉTODO ATUALIZADO
     * Pega as roles do token JWT.
     * Ele procura pelas claims 'roles' (lista) ou 'role' (singular)
     * que configuramos no Supabase.
     */
    @SuppressWarnings("unchecked")
    public List<String> getRoles(Claims claims) {
        // 1. Tenta ler a claim "roles" (plural, ex: ["ADMIN", "USER"])
        List<String> roles = claims.get("roles", List.class);
        if (roles != null && !roles.isEmpty()) {
            return roles.stream().map(String::toUpperCase).collect(Collectors.toList());
        }

        // 2. Se "roles" não existir, tenta ler "role" (singular, ex: "ADMIN")
        String role = claims.get("role", String.class);
        if (role != null && !role.isBlank()) {
            return List.of(role.toUpperCase());
        }

        // 3. Se nenhuma claim de role for encontrada, retorna "USER" como padrão
        return List.of("USER");
    }
}