#!/bin/bash
# $1 = tudo | botoes | novas ; $2 = segundos para o teste de botões
case "$1" in
  botoes) bash test/botoes.sh "${2:-2400}" ;;
  novas) bash test/novas.sh ;;
  *) bash test/smoke.sh; bash test/novas.sh; bash test/botoes.sh "${2:-2400}" ;;
esac
