#!/bin/bash
# $1 = tudo | botoes ; $2 = segundos para o teste de botões
if [ "$1" != "botoes" ]; then bash test/smoke.sh; fi
bash test/botoes.sh "${2:-2400}"
