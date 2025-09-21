package br.com.fereformada.api.service;

import br.com.fereformada.api.model.Topic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class TaggingService {

    private static final Logger logger = LoggerFactory.getLogger(TaggingService.class);

    private final Map<Topic, List<String>> taggingRules = new HashMap<>();

    /**
     * Inicializa o "dicionário" de regras de etiquetagem.
     * Este é o centro da curadoria: mapeamos Tópicos a palavras-chave.
     */
    public void initializeRules(List<Topic> allTopics) {
        taggingRules.clear();

        findTopic("Sagradas Escrituras", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("escrituras", "palavra de deus", "cânon", "testamento", "inspiração", "regra de fé", "autoridade da escritura", "bíblia", "escritura sagrada", "revelação divina", "infalibilidade", "suficiência das escrituras", "antigo testamento", "novo testamento", "interpretação da escritura", "provas racionais", "credibilidade das escrituras", "conhecimento de deus pelas escrituras", "testemunho do espírito santo", "autoridade suprema da escritura", "tradição humana", "escrituras como juiz", "perspicuidade das escrituras", "luz da escritura", "regra infalível", "escrituras autênticas", "cânon das escrituras", "livros apócrifos", "versões das escrituras"))
        );
        findTopic("Deus e a Santíssima Trindade", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("trindade", "um só deus", "pai, o filho e o espírito santo", "três pessoas", "deus e da santíssima trindade", "atributos de deus", "essência", "substância", "divindade", "eternidade", "imutabilidade", "onisciência", "onipotência", "onipresença", "santidade", "justiça divina", "misericórdia", "pessoas divinas", "consubstanciais", "conhecimento de deus", "deus criador", "atributos incomunicáveis", "atributos comunicáveis", "simplicidade divina", "incompreensibilidade de deus", "deus como espírito", "deus vivo", "deus imenso", "deus bom", "deus sábio", "deus poderoso", "deus bem-aventurado", "deus uno", "trindade de pessoas", "filiação eterna", "geração eterna", "processão do espírito"))
        );
        findTopic("Decretos de Deus", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("decretos de deus", "eternos decretos", "vontade de deus", "conselho de deus", "propósito eterno", "preordenação", "decretos eternos", "decreto divino", "plano de deus", "soberania de deus", "predeterminação", "fim supremo", "decretos imutáveis", "decretos sábios", "decretos livres", "decretos absolutos", "decretos condicionais", "vontade secreta", "vontade revelada", "deus decreta tudo", "fim da criação", "glória de deus nos decretos", "execução dos decretos"))
        );
        findTopic("Criação", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("da criação", "criar o mundo", "criou o homem", "imagem e semelhança", "criação do mundo", "criação do homem", "anjos", "criaturas", "ex nihilo", "seis dias", "obra da criação", "gênesis", "estado de inocência", "alma imortal", "criação dos anjos", "criação do céu e da terra", "criação da luz", "criação dos animais", "criação em seis dias", "repouso no sétimo dia", "imagem de deus no homem", "semelhança de deus", "governo do homem sobre a criação", "criação boa", "ordem na criação", "beleza da criação", "anjos bons e maus"))
        );
        findTopic("Providência", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("da providência", "governa todas as criaturas", "sustenta e governa", "presciência", "providência divina", "governo de deus", "preservação", "concurso", "governo das coisas", "causas secundárias", "acaso", "fortuna", "soberana providência", "providência geral", "providência especial", "providência sobre os eleitos", "providência sobre os ímpios", "deus governa o mal", "providência e livre arbítrio", "providência e responsabilidade humana", "conforto na providência", "providência nas aflições", "deus sustenta o mundo", "deus dirige os eventos"))
        );
        findTopic("A Queda e o Pecado", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("queda do homem", "do pecado", "pecado original", "corrupção de nossa natureza", "punição", "castigo", "culpa", "queda no pecado", "depravação total", "pecado hereditário", "transgressão", "adão e eva", "morte espiritual", "incapacidade moral", "ira de deus", "maldição", "pecado de adão", "imputação do pecado", "corrupção hereditária", "perda da imagem de deus", "escravidão ao pecado", "pecado como rebelião", "consequências da queda", "morte temporal", "morte eterna", "pecado atual", "pecado habitual", "pecado venial e mortal"))
        );
        findTopic("Pacto de Deus", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("pacto de deus", "pacto de obras", "pacto da graça", "aliança", "aliança de deus", "pacto com adão", "pacto com abraão", "nova aliança", "antiga aliança", "promessas do pacto", "condições do pacto", "mediador do pacto", "aliança eterna", "pacto da redenção", "pacto entre o pai e o filho", "aliança mosaica", "aliança davídica", "aliança no éden", "sinal do pacto", "circuncisão", "batismo como sinal", "aliança incondicional", "aliança condicional", "fidelidade no pacto", "quebra do pacto"))
        );
        findTopic("Cristo, o Mediador", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("cristo, o mediador", "operação secreta do espirito", "mediador entre deus", "duas naturezas", "profeta, sacerdote e rei", "redenção", "sacrifício de cristo", "jesus cristo", "filho de deus", "encarnação", "propiciação", "reconciliação", "resgate", "morte na cruz", "ressurreição de cristo", "ascensão", "intercessão", "cristo como profeta", "cristo como sacerdote", "cristo como rei", "unidade de pessoa em cristo", "natureza divina de cristo", "natureza humana de cristo", "virgem maria", "nascimento de cristo", "sofrimentos de cristo", "obediência de cristo", "vitória sobre a morte", "reino de cristo", "segunda vinda de cristo"))
        );
        findTopic("Fé", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("da fé", "fé salvadora", "fé implícita", "certeza da fé", "confiança", "fé em cristo", "conhecimento", "assentimento", "confiança", "certeza da graça", "dúvidas na fé", "graus da fé", "fruto do espírito", "definição de fé", "fé como conhecimento", "fé como confiança", "fé implícita vs explícita", "fé temporária", "fé histórica", "fé milagrosa", "certeza infalível", "luta da fé", "fé e obras", "fé e arrependimento", "fé como dom de deus", "fé operada pelo espírito"))
        );
        findTopic("Regeneração e Arrependimento", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("regenerados mediante a fé", "arrependimento", "penitência", "contrição", "confissão auricular", "satisfação", "mortificação", "vivificação", "novo nascimento", "regeneração", "arrependimento para vida", "conversão", "ódio ao pecado", "volta para deus", "tristeza segundo deus", "confissão de pecados", "arrependimento evangélico", "penitência papista", "satisfações humanas", "mortificação da carne", "vivificação do espírito", "regeneração pelo espírito", "nova criatura", "renovação da mente", "arrependimento como dom", "frutos do arrependimento", "confissão privada", "confissão pública"))
        );
        findTopic("Vida Cristã", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("vida do homem cristão", "renúncia pessoal", "tomar a cruz", "meditação da vida futura", "uso da presente vida", "boas obras", "negação de si mesmo", "paciência nas aflições", "esperança eterna", "bens terrenos", "santificação diária", "obediência", "perseverança", "vida eterna como foco", "desprezo do mundo", "uso legítimo das coisas criadas", "moderação nos bens", "cruz como disciplina", "paciência cristã", "consolação na cruz", "progresso na santificação", "imperfeição dos crentes", "boas obras como frutos", "mérito das boas obras", "recompensa das obras"))
        );
        findTopic("Justificação pela Fé", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("justificação pela fé", "méritos das obras", "promessas da lei", "galardão", "justiça das obras", "perdão dos pecados", "aceitá-los como justos", "justiça de cristo", "imputação", "remissão dos pecados", "fé como instrumento", "justiça imputada", "mérito de cristo", "harmonia lei e graça", "justificação gratuita", "justiça própria", "obras antes da justificação", "obras depois da justificação", "fé que justifica", "justificação e santificação", "perdão contínuo", "imputação da justiça", "aceitação em cristo", "justiça pela fé somente", "sola fide", "obras como evidência"))
        );
        findTopic("Oração", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("da oração", "oração do senhor", "invocação", "súplica", "regras da oração", "pai nosso", "oração em nome de cristo", "intercessão do espírito", "oração com fé", "perseverança na oração", "oração secreta", "oração pública", "petições", "ação de graças", "oração como meio de graça", "oração vocal", "oração mental", "oração no espírito", "oração pelos outros", "oração pelos inimigos", "oração e jejum", "horas de oração", "postura na oração", "oração em línguas conhecidas", "invocação de santos", "oração como culto"))
        );
        findTopic("Eleição e Predestinação", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("eleição", "predestinação", "reprovação", "vocação divina", "decretos eternos", "escolheu em cristo", "eleição eterna", "predestinação para vida", "reprovação eterna", "escolhidos", "pré-conhecimento", "propósito da eleição", "certeza da eleição", "soberana eleição", "eleição incondicional", "predestinação dupla", "reprovação como justiça", "eleição como graça", "sinais da eleição", "frutos da eleição", "eleição e vocação", "predestinação e livre arbítrio", "consolo na eleição", "humildade pela eleição", "predestinação em cristo"))
        );
        findTopic("Ressurreição Final", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("ressurreição final", "juízo final", "estado do homem depois da morte", "vida eterna", "ressurreição dos mortos", "juízo último", "paraíso", "inferno", "alma imortal", "corpo glorificado", "sentença final", "recompensa eterna", "punição eterna", "estado intermediário", "almas dos justos", "almas dos ímpios", "ressurreição corporal", "juízo universal", "livro da vida", "obras como testemunho", "fogo eterno", "reino dos céus", "nova terra", "visão beatífica", "condenação eterna"))
        );
        findTopic("A Igreja", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("da igreja", "comunhão dos santos", "censuras eclesiásticas", "sínodos e concílios", "poder eclesiástico", "ordens eclesiásticas", "disciplina", "igreja visível", "igreja invisível", "cabeça da igreja", "ministros", "governo da igreja", "unidade da igreja", "pureza da igreja", "igreja como mãe", "notas da igreja", "igreja verdadeira vs falsa", "poder das chaves", "excomunhão", "disciplina eclesiástica", "presbíteros", "diáconos", "governo presbiteriano", "igreja militante", "igreja triunfante", "comunhão na igreja", "sucessão apostólica"))
        );
        findTopic("Sacramentos", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("sacramentos", "batismo", "ceia do senhor", "indulgências", "purgatório", "extrema-unção", "sinais e selos", "batismo infantil", "eucaristia", "transubstanciação", "selos da graça", "meios de graça", "confirmação", "penitência sacramental", "ordens sagradas", "número dos sacramentos", "sacramentos do antigo testamento", "sacramentos do novo testamento", "eficácia dos sacramentos", "administrador dos sacramentos", "batismo por imersão", "batismo por aspersão", "ceia como memorial", "presença real de cristo", "missa como sacrifício", "sacramentos e fé", "indulgências papistas", "purgatório como erro"))
        );
        findTopic("Liberdade Cristã", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("liberdade cristã", "administração política", "magistrado civil", "governo civil", "liberdade de consciência", "liberdade em cristo", "jugo da lei", "consciência livre", "tradições humanas", "autoridade civil", "obediência às autoridades", "resistência à tirania", "liberdade da servidão", "liberdade para servir", "coisas indiferentes", "escândalo dos fracos", "poder civil e eclesiástico", "deveres do magistrado", "leis civis", "guerra justa", "juramento", "voto de celibato", "liberdade e lei moral"))
        );
        findTopic("Livre-Arbítrio", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("do livre-arbítrio", "vontade do homem", "livre arbítrio", "escravidão da vontade", "vontade cativa", "liberdade natural", "incapacidade espiritual", "vontade regenerada", "escolha moral", "livre arbítrio no estado de inocência", "livre arbítrio após a queda", "livre arbítrio na regeneração", "livre arbítrio na glória", "vontade serva do pecado", "vontade livre para o mal", "incapacidade para o bem", "graça irresistível", "livre arbítrio e predestinação", "vontade e razão", "vontade e apetites", "pelagianismo", "semi-pelagianismo"))
        );
        findTopic("Vocação Eficaz", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("da vocação eficaz", "adoção", "santificação", "chamado eficaz", "vocação divina", "chamado interno", "iluminação", "convicção de pecado", "renovação da vontade", "união com cristo", "espírito de adoção", "vocação externa", "vocação geral", "vocação especial", "efeito da vocação", "frutos da vocação", "adoção como filhos", "herança eterna", "santificação imperfeita", "progresso na santificação", "perseverança dos santos", "certeza da salvação", "testemunho interno", "selos do espírito"))
        );
        findTopic("A Lei de Deus", allTopics).ifPresent(topic ->
                taggingRules.put(topic, List.of("da lei", "explanação do decálogo", "mandamentos", "ofício e dos usos da lei", "lei moral", "dez mandamentos", "lei cerimonial", "lei judicial", "uso da lei", "preâmbulo da lei", "amor a deus", "amor ao próximo", "ab-rogação da lei", "cumprimento da lei", "lei como espelho", "lei como freio", "lei como guia", "primeira tábua da lei", "segunda tábua da lei", "idolatria", "nome de deus em vão", "dia do senhor", "honrar pai e mãe", "não matar", "não adulterar", "não furtar", "não falso testemunho", "não cobiçar", "lei e evangelho", "lei abrogada em cristo"))
        );
    }

    /**
     * Aplica uma lógica de pontuação para encontrar os tópicos mais relevantes.
     * @param texts Os textos a serem analisados (ex: título do capítulo, conteúdo da seção).
     * @return Um Set com os Tópicos que passaram no critério de relevância.
     */
    public Set<Topic> getTagsFor(String... texts) {
        String combinedText = Arrays.stream(texts)
                .filter(Objects::nonNull)
                .collect(Collectors.joining(" "))
                .toLowerCase();

        if (combinedText.isBlank()) {
            return new HashSet<>();
        }

        Map<Topic, Integer> topicScores = new HashMap<>();
        for (Map.Entry<Topic, List<String>> rule : taggingRules.entrySet()) {
            Topic currentTopic = rule.getKey();
            int score = 0;
            for (String keyword : rule.getValue()) {
                Pattern pattern = Pattern.compile("\\b" + Pattern.quote(keyword) + "\\b");
                Matcher matcher = pattern.matcher(combinedText);
                if (matcher.find()) {
                    score++;
                }
            }
            if (score > 0) {
                topicScores.put(currentTopic, score);
            }
        }

        if (topicScores.isEmpty()) {
            return new HashSet<>();
        }

        // Logs alterados para DEBUG. Ficarão ocultos por padrão.
        logger.debug("--- Tagging Scores for Chunk ---");
        logger.debug("Text Snippet: '{}...'", combinedText.substring(0, Math.min(combinedText.length(), 100)));
        topicScores.entrySet().stream()
                .sorted(Map.Entry.<Topic, Integer>comparingByValue().reversed())
                .forEach(entry -> logger.debug("  - Topic: '{}', Score: {}", entry.getKey().getName(), entry.getValue()));
        logger.debug("------------------------------");

        int maxScore = Collections.max(topicScores.values());
        double scoreThreshold = maxScore * 0.1;
        int absoluteMinimumKeywords = 1;

        Set<Topic> foundTopics = topicScores.entrySet().stream()
                .filter(entry -> entry.getValue() >= scoreThreshold && entry.getValue() >= absoluteMinimumKeywords)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        logger.debug("Topics Applied: {}", foundTopics.stream().map(Topic::getName).collect(Collectors.toList()));

        return foundTopics;
    }

    private Optional<Topic> findTopic(String name, List<Topic> topics) {
        return topics.stream().filter(t -> t.getName().equals(name)).findFirst();
    }
}