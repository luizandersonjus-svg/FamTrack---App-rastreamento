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

    // ----------------------------------------------------------------------
    // Detecção de movimento (TRACK-1, CORR-X): máquina de estados por membro.
    // O rastro só é desenhado quando há MOVIMENTO de verdade; tremor de GPS
    // de pessoa parada não entra no store (resolve o emaranhado de linhas no
    // mapa quando o usuário está parado em casa, sem sair).
    // ----------------------------------------------------------------------

    /**
     * Distância acumulada (metros) em relação ao ponto-âncora (o último local
     * estável onde a pessoa estava parada) para considerar que o movimento
     * COMEÇOU. Enquanto STATIONARY, cada fix é comparado com a âncora; só ao
     * cruzar este limiar o rastro passa a ser acumulado (a âncora vira o
     * primeiro ponto do traço, sem "buraco" visual no início do trecho).
     * Tremor de GPS parado fica muito abaixo disso; uma saída real a pé ou de
     * carro cruza 30 m rápido. 30 m ≈ meia quadra.
     */
    const val TRAIL_MOVEMENT_START_THRESHOLD_M = 30f

    /**
     * Raio (metros) usado para CONFIRMAR que a pessoa parou de se mover.
     * Enquanto MOVING, se os fixes permanecerem dentro deste raio do último
     * ponto aceito por [TRAIL_STATIONARY_CONFIRM_DURATION_MS], transitamos
     * para STATIONARY (a pessoa parou de verdade; tremor pós-parada fica bem
     * abaixo de 30 m). Mesmo valor do limiar de início de movimento para que
     * mover 30 m e parar 30 m sejam simétricos.
     */
    const val TRAIL_STATIONARY_CONFIRM_RADIUS_M = 30f

    /**
     * Duração (ms) que os fixes precisam ficar dentro de
     * [TRAIL_STATIONARY_CONFIRM_RADIUS_M] para transicionar MOVING ->
     * STATIONARY. 120 s (2 min) de intervalo sem deslocamento líquido
     * significativo = parou de verdade; uma pausa breve no trânsito não
     * destrói o traço (só reconfirma ao sair).
     */
    const val TRAIL_STATIONARY_CONFIRM_DURATION_MS = 120_000L

    /**
     * Tempo (ms) que o membro deve permanecer STATIONARY após ser confirmado
     * parado para que o rastro do trecho concluído seja OCULTADO do mapa.
     * 5 min parado = o deslocamento acabou; qualquer movimento futuro parte
     * de um novo ponto-âncora e monta um traço novo do zero.
     */
    const val TRAIL_AUTO_HIDE_AFTER_STATIONARY_MS = 5 * 60_000L

    /**
     * true quando o ponto merece entrar no rastro. Precisão desconhecida
     * (null ou <= 0) é aceita, mantendo a convenção do histórico de rota.
     */
    fun isAccurate(accuracy: Float?): Boolean =
        accuracy == null || accuracy <= 0f || accuracy <= TRAIL_MAX_ACCURACY_M
}