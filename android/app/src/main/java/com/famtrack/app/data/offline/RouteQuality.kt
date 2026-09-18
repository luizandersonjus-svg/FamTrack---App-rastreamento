package com.famtrack.app.data.offline

/**
 * Limiares de qualidade do rastreamento (TRACK-1) e, futuramente, alvo dos
 * filtros centralizados de qualidade (CORR-1..4).
 */
internal object RouteQuality {

    /**
     * Precisão horizontal máxima aceita (metros) para um fix entrar no rastro
     * ao vivo. Fixes com accuracy acima deste valor são descartados.
     *
     * Justificativa (qualidade GPS observada nos testes de campo com o Galaxy
     * A36): com o contrato HIGH_ACCURACY do FusedLocationProvider, a precisão
     * típica ao ar livre ficou entre ~3 m e ~40 m; valores acima surgem no
     * lock "frio" logo após iniciar o rastreio ou em ambientes fechados/ruas
     * estreitas. 60 m corta esse ruído com folga e, ao mesmo tempo, fica bem
     * abaixo do teto de 150 m do histórico de rota (route_history), que aceita
     * pontos mais frouxos por ser sincronização em lote — o rastro ao vivo vira
     * polígono no mapa e precisa de pontos mais confiáveis.
     */
    const val TRAIL_MAX_ACCURACY_M = 60f

    /**
     * Distância mínima (metros) entre dois pontos consecutivos do MESMO
     * usuário para o segundo ser aceito no rastro. Decima pontos redundantes
     * quando o usuário está parado (o jitter de GPS parado fica abaixo disso
     * na prática) e evita acumular amostras repetidas de um mesmo local.
     */
    const val TRAIL_MIN_DISTANCE_M = 5f

    /**
     * Teto de pontos por membro por dia. Ao atingir o limite, o ponto mais
     * antigo é descartado (janela deslizante, preservando o trajeto recente).
     * 2000 pontos ≈ 10 km de caminhada com decimação de 5 m, com folga para
     * trajetos de carro.
     */
    const val TRAIL_MAX_POINTS_PER_MEMBER = 2000

    /**
     * Velocidade implícita máxima (km/h) tolerada entre dois pontos
     * consecutivos do rastro. Acima disso a linha NÃO é desenhada entre eles
     * (quebra em segmento), evitando retas que atravessam o mapa em saltos
     * implausíveis de GPS. Mesmo critério `GAP_MAX_IMPLIED_KMH` do histórico
     * de rota (CORR-2 / RouteMatchingModels).
     */
    const val TRAIL_MAX_IMPLIED_KMH = 180.0

    /**
     * Intervalo mínimo (ms) entre amostras para aplicar o teste de velocidade
     * implícita. Abaixo disso o delta de tempo é ruído (dois fixes quase
     * simultâneos) e a razão distância/tempo explode sem significado.
     */
    const val TRAIL_MIN_DT_FOR_SPEED_MS = 1_000L

    /**
     * Lacuna temporal (ms) a partir da qual se considera que a coleta ficou
     * cega (app/serviço parado). Só quebra o traço se, ALÉM do tempo, o salto
     * de distância também for grande (ver [TRAIL_GAP_MAX_DIST_METERS]); assim,
     * paradas reais (gaps longos com deriva de poucos metros) NÃO fragmentam o
     * traço — só movimentos feitos durante a cegueira.
     */
    const val TRAIL_GAP_MAX_DT_MILLIS = 3 * 60 * 1000L

    /**
     * Distância (metros) entre dois pontos consecutivos a partir da qual, em
     * conjunto com [TRAIL_GAP_MAX_DT_MILLIS], o traço é quebrado. Se passou
     * tempo suficiente E o usuário se deslocou além disso durante a lacuna,
     * não há como reconstruir o caminho pelas ruas e uma reta cruzaria o mapa.
     */
    const val TRAIL_GAP_MAX_DIST_METERS = 500f

    /**
     * true quando o ponto merece entrar no rastro. Precisão desconhecida
     * (null ou <= 0) é aceita, mantendo a convenção do histórico de rota.
     */
    fun isAccurate(accuracy: Float?): Boolean =
        accuracy == null || accuracy <= 0f || accuracy <= TRAIL_MAX_ACCURACY_M
}