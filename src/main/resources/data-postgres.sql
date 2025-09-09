-- Usamos "INSERT ... ON CONFLICT DO NOTHING" para evitar erros se tentarmos rodar o script novamente.
-- Isso só funciona com PostgreSQL. Para H2, a lógica é um pouco diferente, mas o Spring Boot lida bem.

-- Inserindo Tópicos iniciais
INSERT INTO topics (name, description) VALUES ('Soberania de Deus', 'A doutrina do controle e autoridade suprema de Deus sobre toda a criação.') ON CONFLICT (name) DO NOTHING;
INSERT INTO topics (name, description) VALUES ('Predestinação', 'A doutrina de que Deus predestinou todos os eventos, incluindo a salvação eterna de almas individuais.') ON CONFLICT (name) DO NOTHING;
INSERT INTO topics (name, description) VALUES ('Decretos de Deus', 'Os decretos eternos de Deus pelos quais Ele preordenou tudo o que acontece.') ON CONFLICT (name) DO NOTHING;
INSERT INTO topics (name, description) VALUES ('Soteriologia', 'A doutrina da salvação.') ON CONFLICT (name) DO NOTHING;
INSERT INTO topics (name, description) VALUES ('Justificação pela Fé', 'A doutrina de como o pecador é declarado justo diante de Deus apenas pela fé em Cristo.') ON CONFLICT (name) DO NOTHING;
INSERT INTO topics (name, description) VALUES ('A Lei de Deus', 'Os mandamentos e estatutos divinos revelados nas Escrituras.') ON CONFLICT (name) DO NOTHING;


-- Inserindo Autores iniciais
INSERT INTO authors (name, era, birth_date, death_date) VALUES ('Assembleia de Westminster', 'Puritanos', '1643-01-01', '1653-01-01') ON CONFLICT (name) DO NOTHING;
INSERT INTO authors (name, era, birth_date, death_date) VALUES ('João Calvino', 'Reforma', '1509-07-10', '1564-05-27') ON CONFLICT (name) DO NOTHING;