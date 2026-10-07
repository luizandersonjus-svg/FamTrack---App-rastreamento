#!/bin/bash
# =============================================================================
# FamTrack - servidor OSRM na Oracle Cloud (script de inicialização)
#
# Cole este arquivo INTEIRO em: Criar instância > Opções avançadas >
# Gerenciamento > "Colar script cloud-init". A Oracle executa tudo sozinha na
# primeira inicialização (cerca de 1 hora). Não é preciso usar SSH.
#
# Antes de colar, preencha as 3 linhas abaixo.
# =============================================================================
DUCKDNS_SUBDOMINIO="PREENCHA"   # só o nome, ex.: famtrack-osrm (sem .duckdns.org)
DUCKDNS_TOKEN="PREENCHA"        # o "token" mostrado no topo da página do DuckDNS
OSRM_API_KEY="PREENCHA"         # chave do app (a mesma do local.properties)
MAPA="sudeste-latest"           # mapa da Geofabrik (região Sudeste)
# =============================================================================

exec > /var/log/famtrack-osrm.log 2>&1
set -euo pipefail
echo "inicio: $(date)"

# 1) Firewall do Ubuntu (a imagem da Oracle bloqueia 80/443 por padrão)
iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
netfilter-persistent save || true

# 2) Swap de 4 GB (folga durante o processamento do mapa)
if [ ! -f /swapfile ]; then
  fallocate -l 4G /swapfile && chmod 600 /swapfile
  mkswap /swapfile && swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

# 3) DuckDNS: aponta o subdomínio para o IP desta máquina (e mantém a cada 5 min)
DUCK_URL="https://www.duckdns.org/update?domains=${DUCKDNS_SUBDOMINIO}&token=${DUCKDNS_TOKEN}&ip="
curl -fsS "$DUCK_URL" || true
echo "*/5 * * * * root curl -fsS '${DUCK_URL}' >/dev/null 2>&1" > /etc/cron.d/duckdns

# 4) Docker
curl -fsSL https://get.docker.com | sh
usermod -aG docker ubuntu || true

# 5) Arquivos do servidor
mkdir -p /opt/osrm/data
cd /opt/osrm

cat > .env <<EOF
OSRM_DOMAIN=${DUCKDNS_SUBDOMINIO}.duckdns.org
OSRM_API_KEY=${OSRM_API_KEY}
EOF
chmod 600 .env

cat > Caddyfile <<'EOF'
{$OSRM_DOMAIN} {
    # Recusa chamadas sem a chave do app.
    @semchave not header X-FamTrack-Key {$OSRM_API_KEY}
    respond @semchave 401

    reverse_proxy osrm:5000
}
EOF

cat > docker-compose.yml <<EOF
services:
  osrm:
    image: ghcr.io/project-osrm/osrm-backend:latest
    command: osrm-routed --algorithm mld /data/${MAPA}.osrm
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
EOF

cat > preparar-mapa.sh <<EOF
#!/usr/bin/env bash
set -euo pipefail
cd /opt/osrm/data
IMG=ghcr.io/project-osrm/osrm-backend:latest
wget -q -N "https://download.geofabrik.de/south-america/brazil/${MAPA}.osm.pbf"
docker run --rm -v "\$PWD:/data" "\$IMG" osrm-extract -p /opt/car.lua "/data/${MAPA}.osm.pbf"
docker run --rm -v "\$PWD:/data" "\$IMG" osrm-partition "/data/${MAPA}.osrm"
docker run --rm -v "\$PWD:/data" "\$IMG" osrm-customize "/data/${MAPA}.osrm"
echo "mapa pronto: \$(date)"
EOF
chmod +x preparar-mapa.sh
chown -R ubuntu:ubuntu /opt/osrm

# 6) Processa o mapa (20 a 60 min) e sobe o servidor
./preparar-mapa.sh
docker compose up -d

# 7) Atualiza o mapa todo dia 1º às 4h
echo "0 4 1 * * root cd /opt/osrm && ./preparar-mapa.sh >> /opt/osrm/atualizacao.log 2>&1 && docker compose restart osrm" > /etc/cron.d/osrm-mapa

echo "PRONTO: $(date)" | tee /opt/osrm/STATUS
