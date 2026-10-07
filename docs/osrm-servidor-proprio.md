# Servidor OSRM próprio na Oracle Cloud (gratuito)

Este guia coloca no ar um servidor OSRM só do FamTrack, para encaixar o rastro
e o histórico nas ruas sem depender do servidor de demonstração público.

> **Por que não usar o servidor de demonstração?** O `router.project-osrm.org`
> só permite uso não comercial, no máximo 1 requisição por segundo, e não tem
> garantia de disponibilidade. O app respeita esse limite automaticamente
> enquanto `OSRM_BASE_URL` estiver vazio, mas o encaixe fica lento e cai
> quando o servidor está fora do ar.

> **Limites deste guia.** O plano *Always Free* da Oracle é adequado para
> desenvolvimento e uso da própria família, sem monetização. Não há garantia
> de disponibilidade e as regras do plano já mudaram sem aviso em 2026
> (cota ARM reduzida para 2 OCPUs e 12 GB). Antes de cobrar pelo app, migre
> para um servidor pago (o mesmo `docker-compose` funciona em qualquer VPS).

## Visão geral

```
App (celular) ──HTTPS + X-FamTrack-Key──▶ Caddy (porta 443) ──▶ OSRM (porta 5000, interna)
```

- **OSRM** calcula `/match` e `/route` com o mapa da região Sudeste
  (OpenStreetMap, via Geofabrik).
- **Caddy** obtém o certificado HTTPS de graça (Let's Encrypt) e recusa
  chamadas sem a chave `X-FamTrack-Key`.
- **DuckDNS** fornece um endereço gratuito (`seu-nome.duckdns.org`).

Tempo estimado: 1 a 2 horas, a maior parte esperando o mapa ser processado.

---

## Atalho: instalação automática

Em vez dos passos 3b, 5, 6 e 7 pelo terminal, cole o script
[`oracle-cloud-init.sh`](oracle-cloud-init.sh) (com as 3 linhas do topo
preenchidas) em **Opções avançadas → Gerenciamento → script cloud-init** ao
criar a instância. A máquina se configura sozinha em cerca de 1 hora. Ainda é
preciso fazer o passo 3a (Security List) e criar o subdomínio no DuckDNS
(passo 4, sem precisar colocar o IP: o script faz isso).

## 1. Criar a conta na Oracle Cloud

1. Acesse <https://www.oracle.com/cloud/free/> e crie a conta.
   É pedido cartão de crédito apenas para verificação.
2. **Região inicial (home region):** escolha **Brazil East (São Paulo)**.
   Essa escolha é permanente e os recursos gratuitos só existem nela.

## 2. Criar a máquina virtual

1. No console: **Compute → Instances → Create instance**.
2. **Image:** *Canonical Ubuntu 24.04* (versão **aarch64**).
3. **Shape:** *Ampere* → `VM.Standard.A1.Flex` com **2 OCPUs e 12 GB** de
   memória (limite atual do plano gratuito; confira na tela, pois pode mudar).
4. **Networking:** mantenha a VCN padrão e marque *Assign a public IPv4 address*.
5. **SSH keys:** gere ou envie sua chave pública. Guarde a chave privada.
6. **Boot volume:** 50 GB é suficiente (o plano gratuito cobre até 200 GB no total).
7. Clique em **Create**.

> Se aparecer *"Out of capacity"*, tente outro *Availability Domain* ou
> repita mais tarde. É comum nas regiões mais procuradas.

Anote o **IP público** da instância.

## 3. Liberar as portas 80 e 443

São **dois** lugares (esquecer o segundo é o erro mais comum):

**a) Na Oracle (Security List):**
*Networking → Virtual Cloud Networks → sua VCN → Security Lists → Default* →
**Add Ingress Rules**:

| Source CIDR | Protocolo | Porta de destino |
|-------------|-----------|------------------|
| `0.0.0.0/0` | TCP       | 80               |
| `0.0.0.0/0` | TCP       | 443              |

**b) No firewall do próprio Ubuntu** (a imagem da Oracle vem com regras
iptables restritivas). Conecte por SSH:

```bash
ssh -i sua-chave-privada ubuntu@IP_PUBLICO
```

E rode:

```bash
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
sudo netfilter-persistent save
```

## 4. Criar o endereço gratuito (DuckDNS)

1. Acesse <https://www.duckdns.org> e entre com sua conta Google ou GitHub.
2. Crie um subdomínio, por exemplo `famtrack-osrm`.
3. No campo *current ip*, coloque o **IP público** da instância e clique em
   *update ip*.

Seu endereço será `https://famtrack-osrm.duckdns.org`.

## 5. Instalar o Docker e preparar a memória

Ainda no SSH:

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker ubuntu
# Swap de 4 GB: segurança extra durante o processamento do mapa
sudo fallocate -l 4G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
exit
```

Conecte de novo por SSH (para o grupo `docker` valer).

## 6. Criar os arquivos do servidor

```bash
sudo mkdir -p /opt/osrm/data && sudo chown -R ubuntu:ubuntu /opt/osrm
cd /opt/osrm
```

**Chave de acesso.** Gere uma chave aleatória e guarde-a (vai no app também):

```bash
openssl rand -hex 24
```

**`/opt/osrm/.env`** (troque os dois valores):

```bash
OSRM_DOMAIN=famtrack-osrm.duckdns.org
OSRM_API_KEY=cole-aqui-a-chave-gerada
```

**`/opt/osrm/Caddyfile`:**

```caddy
{$OSRM_DOMAIN} {
    # Recusa chamadas sem a chave do app.
    @semchave not header X-FamTrack-Key {$OSRM_API_KEY}
    respond @semchave 401

    reverse_proxy osrm:5000
}
```

**`/opt/osrm/docker-compose.yml`:**

```yaml
services:
  osrm:
    image: ghcr.io/project-osrm/osrm-backend:latest
    command: osrm-routed --algorithm mld /data/sudeste-latest.osrm
    volumes:
      - ./data:/data
    restart: unless-stopped

  caddy:
    image: caddy:2
    ports:
      - "80:80"
      - "443:443"
    env_file: .env
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - caddy_data:/data
    depends_on:
      - osrm
    restart: unless-stopped

volumes:
  caddy_data:
```

A porta 5000 do OSRM **não** é publicada: só o Caddy fala com ele.

**`/opt/osrm/preparar-mapa.sh`** (baixa e processa o mapa do Sudeste):

```bash
#!/usr/bin/env bash
set -euo pipefail
cd /opt/osrm/data
IMG=ghcr.io/project-osrm/osrm-backend:latest
MAPA=sudeste-latest

wget -N "https://download.geofabrik.de/south-america/brazil/${MAPA}.osm.pbf"

docker run --rm -v "$PWD:/data" "$IMG" osrm-extract -p /opt/car.lua "/data/${MAPA}.osm.pbf"
docker run --rm -v "$PWD:/data" "$IMG" osrm-partition "/data/${MAPA}.osrm"
docker run --rm -v "$PWD:/data" "$IMG" osrm-customize "/data/${MAPA}.osrm"
echo "Mapa pronto."
```

```bash
chmod +x preparar-mapa.sh
```

> Para o Brasil inteiro, troque o download por
> `https://download.geofabrik.de/south-america/brazil-latest.osm.pbf` e
> `MAPA=brazil-latest` (também no `command` do `docker-compose.yml`). O
> processamento do país inteiro pode exceder 12 GB; nesse caso rode o
> `preparar-mapa.sh` no seu PC e envie a pasta `data/` com `scp`.

## 7. Processar o mapa e subir o servidor

```bash
cd /opt/osrm
./preparar-mapa.sh          # 20 a 60 minutos
docker compose up -d
docker compose logs -f osrm # espere "running and waiting for requests"; Ctrl+C para sair
```

## 8. Testar

Do seu PC (PowerShell ou terminal), troque o domínio e a chave:

```bash
curl -H "X-FamTrack-Key: SUA_CHAVE" "https://famtrack-osrm.duckdns.org/route/v1/driving/-49.8706,-22.9783;-49.8590,-22.9701?overview=false"
```

- Resposta com `"code":"Ok"`: funcionando.
- `401`: chave errada ou ausente (a proteção está funcionando).
- Sem resposta: revise o passo 3 (as duas regras de firewall) e o IP no DuckDNS.

## 9. Apontar o app para o servidor

No `android/local.properties` (nunca versionado):

```properties
OSRM_BASE_URL=https://famtrack-osrm.duckdns.org
OSRM_API_KEY=SUA_CHAVE
```

Recompile (`.\gradlew.bat assembleDebug`) e instale. No logcat, o filtro
`FamTrackRouteMatching` mostra as chamadas ao novo servidor.

> A chave fica dentro do APK e pode ser extraída por alguém determinado.
> Ela impede o uso casual do seu servidor por terceiros, não é segurança forte.

## 10. Manutenção

**Atualizar o mapa** (ruas novas), por exemplo uma vez por mês:

```bash
cd /opt/osrm && ./preparar-mapa.sh && docker compose restart osrm
```

Para automatizar, `crontab -e` e adicione (todo dia 1º às 4h):

```
0 4 1 * * cd /opt/osrm && ./preparar-mapa.sh >> /opt/osrm/atualizacao.log 2>&1 && docker compose restart osrm
```

**Instâncias ociosas.** A Oracle pode recolher instâncias do plano gratuito
com uso muito baixo por vários dias. Confira a política atual em
*Always Free Resources* na documentação da Oracle. Se a instância for
recolhida, os arquivos deste guia permitem recriá-la em menos de uma hora.

**Ver se está no ar:**

```bash
docker compose ps
```

## Atribuição obrigatória

Os dados de mapa são © colaboradores do OpenStreetMap, sob a licença ODbL.
O app já exibe essa atribuição no painel de Camadas (com o rastro ligado) e
em *Configurações → Sobre*. Não remova.
