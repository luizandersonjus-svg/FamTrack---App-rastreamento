# ETAPA 10D-4A — Fonte de dados de clima para a camada Clima

> Estudo de viabilidade. **Passo B (código) só acontece após aprovação literal da fonte.**

## Objetivo
Camada "Clima" no mapa principal: condições do tempo (temperatura, chuva,
vento) nas posições da família + alertas meteorológicos oficiais quando
houver aviso no Brasil.

## Fontes avaliadas

### 1. Open-Meteo (recomendada para CONDIÇÕES)
- URL: `https://api.open-meteo.com/v1/forecast`
- Parâmetros: `latitude`, `longitude`, `current=temperature_2m,weather_code,wind_speed_10m,precipitation`
- **Sem chave/API key, gratuito para uso não comercial e de baixo volume.**
- Banco europeu (optimized points), cobertura global; dados a cada ~1 min por ponto.
- Retorna JSON: `current` (unidades configuráveis `temperature_unit=celsius`).
- Limites: ~10.000 chamadas/dia por IP em uso livre; para 2–4 visitas/refereço
  e 1 chamada por membro por ciclo de 10 min fica em poucas centenas/dia.
- Privacidade: a coordenada é enviada no query string HTTP — mitigação com
  HTTPS + sem armazenar resposta; latência piora ~200–400 ms por ponto.
- `weather_code` mapeia para WMO 4677: 0 limpo, 1–3 nublado parcial, 45/48
  nevoeiro, 51–67 chuvisco, 61–67 chuva, 71–77 neve, 80–82 aguaceiro, 95–99
  tempestade.

### 2. INMET (recomendada para ALERTAS oficiais BR)
- Ferramenta: Desastres / Avisos meteorológicos (CPTEC e Centros).
- Integração ideal via **CAT/API de avisos** do INMET (pública, sem chave;
  necessidade confirmada na hora da integração — se indisponível, usar scrape
  controlado ou deixar fora da v1).
- Cobre apenas Brasil; granularidade por estado/município, não por ponto.
- Usada **apenas** como ingestão de "aviso em vigor" para o estado do membro;
  não substitui a leitura de condições pontuais.

### 3. OpenWeatherMap / WeatherAPI / AccuWeather (não escolhidas na v1)
- OWM atual: paga ou com chave e limites rígidos; WeatherAPI free tem cotas
  baixas e cobertura regional pior no BR; AccuWeather com contrato.
- Todas exigem cadastro/chave no app — ponto de manutenção e vazamento de key.

## Decisões propostas
- **v1 (Passo B):** Open-Meteo para as condições no ponto de cada membro.
- **Alertas:** INMET como segunda fonte curta; se a API de avisos não estiver
  disponível de forma estável, os alertas ficam para depois (não bloqueia a
  camada de condições).
- **Disparo:** carga inicial ao ligar a camada + refresh dentro do loop de 60 s
  do Home (mesmo padrão das camadas de Eventos/Trajeto).
- **Estado exibido:** 1 chamada por posição de membro (no máximo 6 a 7
  posições); cache em memória por ciclo; `weather_code` → ícone + temperatura
  + vento; se membro estiver com `sharing_paused`, não consulta o ponto.

## Dados enviados a terceiros (privacidade)
- Open-Meteo: apenas a coordenada do membro (lat/lon), uma vez por ciclo.
- Não enviamos id, nome, família, histórico nem qualquer tabela local.
- Sem cookies/rastreamento na nossa camada; HTTPS obrigatório.
- Nada é armazenado no servidor — o payload do clima só existe em memória.

## Esforço
- Passo B: ~1 arquivo novo (`ui/home/WeatherLayer.kt`) + strings + carga no
  HomeScreen + cache em memória. Sem migração SQL, sem serverless, sem nova
  dependência (usa ktor do app).
- Passo C (alerta INMET opcional): estudos à parte, após v1 validada.

## Aprovação
Responda com a fonte escolhida (Open-Meteo para condições é a recomendada) e
se os alertas INMET entram na mesma v1 ou ficam para depois.