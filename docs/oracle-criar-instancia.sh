#!/usr/bin/env bash
# =============================================================================
# FamTrack - cria a máquina do servidor OSRM na Oracle, tentando de novo
# enquanto a Oracle responder "Out of capacity".
#
# Rode no Cloud Shell da Oracle (ícone ">_" no topo do console), na mesma
# pasta onde enviou o famtrack-oracle-init.sh já preenchido:
#
#     bash oracle-criar-instancia.sh
#
# O que ele faz (uma vez só, reaproveitando o que já existir):
#   - rede própria (famtrack-vcn) com saída para a internet;
#   - libera as portas 22, 80 e 443 (substitui a "Parte 3" manual);
#   - tenta criar a instância a cada 60 s até conseguir.
# =============================================================================
set -uo pipefail

NOME="famtrack-osrm"
SHAPE="VM.Standard.A1.Flex"
OCPUS=2
MEMORIA_GB=12
DISCO_GB=50
INIT_FILE="famtrack-oracle-init.sh"
INTERVALO=60

C="${OCI_TENANCY:?Rode este script no Cloud Shell da Oracle}"

[ -f "$INIT_FILE" ] || { echo "ERRO: envie o $INIT_FILE para esta pasta (menu do Cloud Shell > Upload)."; exit 1; }
if grep -q 'PREENCHA' "$INIT_FILE"; then
  echo "ERRO: preencha as linhas PREENCHA (DuckDNS) no $INIT_FILE antes de continuar."; exit 1
fi

q() { oci "$@" --raw-output 2>/dev/null; }

# Chave SSH (só para emergências; o servidor se configura sem SSH)
[ -f ~/.ssh/id_rsa.pub ] || ssh-keygen -t rsa -b 4096 -N "" -f ~/.ssh/id_rsa -q

echo "== Rede"
VCN=$(q network vcn list --compartment-id "$C" --display-name famtrack-vcn \
      --lifecycle-state AVAILABLE --query 'data[0].id')
if [ -z "$VCN" ] || [ "$VCN" = "null" ]; then
  VCN=$(q network vcn create --compartment-id "$C" --display-name famtrack-vcn \
        --cidr-blocks '["10.0.0.0/16"]' --dns-label famtrack \
        --wait-for-state AVAILABLE --query 'data.id')
  echo "   VCN criada"
fi
[ -n "$VCN" ] && [ "$VCN" != "null" ] || { echo "ERRO ao criar a rede."; exit 1; }

IGW=$(q network internet-gateway list --compartment-id "$C" --vcn-id "$VCN" --query 'data[0].id')
if [ -z "$IGW" ] || [ "$IGW" = "null" ]; then
  IGW=$(q network internet-gateway create --compartment-id "$C" --vcn-id "$VCN" \
        --is-enabled true --display-name famtrack-igw \
        --wait-for-state AVAILABLE --query 'data.id')
  echo "   Internet gateway criado"
fi

RT=$(q network vcn get --vcn-id "$VCN" --query 'data."default-route-table-id"')
oci network route-table update --rt-id "$RT" --force \
  --route-rules "[{\"destination\":\"0.0.0.0/0\",\"destinationType\":\"CIDR_BLOCK\",\"networkEntityId\":\"$IGW\"}]" >/dev/null

SL=$(q network vcn get --vcn-id "$VCN" --query 'data."default-security-list-id"')
oci network security-list update --security-list-id "$SL" --force --ingress-security-rules '[
  {"protocol":"6","source":"0.0.0.0/0","tcpOptions":{"destinationPortRange":{"min":22,"max":22}}},
  {"protocol":"6","source":"0.0.0.0/0","tcpOptions":{"destinationPortRange":{"min":80,"max":80}}},
  {"protocol":"6","source":"0.0.0.0/0","tcpOptions":{"destinationPortRange":{"min":443,"max":443}}},
  {"protocol":"1","source":"0.0.0.0/0","icmpOptions":{"type":3,"code":4}},
  {"protocol":"1","source":"10.0.0.0/16","icmpOptions":{"type":3}}
]' >/dev/null
echo "   Portas 22, 80 e 443 liberadas"

SUBNET=$(q network subnet list --compartment-id "$C" --vcn-id "$VCN" --query 'data[0].id')
if [ -z "$SUBNET" ] || [ "$SUBNET" = "null" ]; then
  SUBNET=$(q network subnet create --compartment-id "$C" --vcn-id "$VCN" \
           --cidr-block 10.0.0.0/24 --display-name famtrack-subnet --dns-label pub \
           --wait-for-state AVAILABLE --query 'data.id')
  echo "   Sub-rede criada"
fi
[ -n "$SUBNET" ] && [ "$SUBNET" != "null" ] || { echo "ERRO ao criar a sub-rede."; exit 1; }

echo "== Imagem e zona"
AD=$(q iam availability-domain list --compartment-id "$C" --query 'data[0].name')
IMG=$(q compute image list --compartment-id "$C" --operating-system "Canonical Ubuntu" \
      --operating-system-version "24.04" --shape "$SHAPE" \
      --sort-by TIMECREATED --sort-order DESC --limit 1 --query 'data[0].id')
[ -n "$IMG" ] && [ "$IMG" != "null" ] || { echo "ERRO: imagem Ubuntu 24.04 ARM não encontrada."; exit 1; }
echo "   Zona: $AD"

EXISTE=$(q compute instance list --compartment-id "$C" --display-name "$NOME" \
         --query "data[?\"lifecycle-state\"!='TERMINATED'].id | [0]")
if [ -n "$EXISTE" ] && [ "$EXISTE" != "null" ]; then
  echo "Já existe uma instância $NOME (não vou criar outra)."; exit 0
fi

echo "== Tentando criar a instância (Ctrl+C para parar)"
TENTATIVA=0
while true; do
  TENTATIVA=$((TENTATIVA + 1))
  SAIDA=$(oci compute instance launch \
    --compartment-id "$C" --availability-domain "$AD" \
    --display-name "$NOME" --shape "$SHAPE" \
    --shape-config "{\"ocpus\":$OCPUS,\"memoryInGBs\":$MEMORIA_GB}" \
    --image-id "$IMG" --boot-volume-size-in-gbs "$DISCO_GB" \
    --subnet-id "$SUBNET" --assign-public-ip true \
    --ssh-authorized-keys-file ~/.ssh/id_rsa.pub \
    --user-data-file "$INIT_FILE" 2>&1)
  if echo "$SAIDA" | grep -q '"lifecycle-state"'; then
    echo "$(date '+%H:%M:%S') tentativa $TENTATIVA: CRIADA!"
    break
  elif echo "$SAIDA" | grep -qiE 'capacity|TooManyRequests|429'; then
    echo "$(date '+%H:%M:%S') tentativa $TENTATIVA: sem vaga, tentando de novo em ${INTERVALO}s"
    sleep "$INTERVALO"
  else
    echo "Erro diferente de falta de vaga (copie e envie para análise):"
    echo "$SAIDA" | head -20
    exit 1
  fi
done

ID=$(echo "$SAIDA" | grep -m1 '"id": "ocid1.instance' | sed 's/.*"\(ocid1[^"]*\)".*/\1/')
echo "Aguardando a instância ligar..."
oci compute instance get --instance-id "$ID" --wait-for-state RUNNING >/dev/null 2>&1
IP=$(q compute instance list-vnics --instance-id "$ID" --query 'data[0]."public-ip"')
echo "=============================================================="
echo " Instância no ar. IP público: $IP"
echo " A instalação do OSRM continua sozinha (cerca de 1 hora)."
echo " Depois, abra https://SEU-SUBDOMINIO.duckdns.org no navegador:"
echo " uma página vazia ou erro 401 significa que está funcionando."
echo "=============================================================="
