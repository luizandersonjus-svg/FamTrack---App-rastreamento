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
     * true quando o ponto merece entrar no rastro. Precisão desconhecida
     * (null ou <= 0) é aceita, mantendo a convenção do histórico de rota.
     */
    fun isAccurate(accuracy: Float?): Boolean =
        accuracy == null || accuracy <= 0f || accuracy <= TRAIL_MAX_ACCURACY_M
}