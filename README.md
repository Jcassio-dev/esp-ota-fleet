# esp-ota-fleet

Atualização de firmware pelo ar para frotas de ESP32, feita do zero: servidor em Java (Spring Boot), CLI de publicação em Node e firmware em ESP-IDF. Não usa bibliotecas prontas de OTA (`esp_https_ota`, ArduinoOTA, ElegantOTA). Do chip, usa só as primitivas de gravação de partição (`esp_ota_*`) e o rollback do bootloader.

## Como funciona

1. **Check-in:** o aparelho envia a versão atual e a bateria.
2. **Oferta:** o servidor responde com uma atualização ou com `"update": null`.
3. **Download em blocos:** HTTP Range, 16 KB por vez, gravando na partição que não está rodando. Um bloco só vai para a flash depois de chegar inteiro na RAM.
4. **Verificação:** SHA-256, assinatura ECDSA P-256, estrutura da imagem e versão lida de dentro da imagem (sem downgrade).
5. **Troca:** a versão nova sobe em teste (`PENDING_VERIFY`).
6. **Autoteste:** conectou e fez check-in, confirma. Falhou ou travou, o bootloader volta para a versão anterior.
7. **Lançamento gradual:** o servidor libera para uma porcentagem da frota e pausa sozinho se os rollbacks passarem do limite.

## Princípios

- A chave privada nunca sai da máquina de quem publica. Servidor e firmware só têm a chave pública.
- O aparelho nunca vira tijolo: sempre existe uma versão anterior para onde voltar.
- A versão vem de dentro da imagem assinada, nunca de um campo declarado no upload.
- Eventos são idempotentes e o aparelho tem a palavra final sobre o resultado.

## Estrutura

```
esp-ota-fleet/
├── server/     Spring Boot 3, Java 21, Maven
├── cli/        Node 20+, ESM
├── firmware/   ESP-IDF v5.x, ESP32-WROOM (4 MB de flash)
├── sim/        simulador de frota em Node
└── docs/       ADRs e diagramas
```

## Como rodar

Requisitos: Java 21, Maven, Node 20+ e Docker. Para o firmware, ESP-IDF v5.x (testado com v5.5.6).

```bash
# 1. chaves: a privada fica em keys/ (fora do git), a pública vai para o firmware
node cli/src/cli.js keygen --out keys/ --firmware firmware/main/signing_key.pem

# 2. servidor + Postgres
export OTA_FLEET_ADMIN_TOKEN=troque-este-token
docker compose up -d --build

# 3. demonstração sem placa: 40 aparelhos simulados, uma versão boa e uma ruim
node sim/demo.js --devices 40
```

Saída da demonstração:

```
▶ Publicada 1.1.0 (id 1) com rollout de 100%
Rodada 1: ████████████████████████████████████████ 40 confirmados, 0 revertidos, 0 falhas

▶ Publicada 1.2.0 (id 2) com rollout de 100%
Rodada 1: ████████▒▒▒▒ 8 confirmados, 4 revertidos, 0 falhas
  ⏸ 1.2.0: Pausada automaticamente: 4 de 9 aparelhos reverteram (44%, limite 20%)
```

### CLI

```bash
node cli/src/cli.js inspect firmware.bin                       # versão, projeto, tamanho, hash
node cli/src/cli.js publish firmware.bin --key keys/private.pem --rollout 5
node cli/src/cli.js rollout <id> 20                            # também retoma uma versão pausada
node cli/src/cli.js pause <id> --reason "bug no sensor"
node cli/src/cli.js status
node cli/src/cli.js devices
```

Servidor e token vêm de `--server`/`--token` ou de `OTA_FLEET_SERVER` e `OTA_FLEET_ADMIN_TOKEN`.

### Firmware em várias versões

```bash
cp firmware/sdkconfig.local.example firmware/sdkconfig.local   # Wi-Fi e URL do servidor
./firmware/build-versions.sh                                   # gera dist/firmware-1.0.0, 1.1.0 e 1.2.0
idf.py -C firmware -B firmware/build-1.0.0 -p /dev/ttyUSB0 flash monitor
node cli/src/cli.js publish firmware/dist/firmware-1.1.0.bin --key keys/private.pem --rollout 100
node cli/src/cli.js publish firmware/dist/firmware-1.2.0.bin --key keys/private.pem --rollout 100
```

- **1.0.0**, gravada pela USB: o LED pisca 1 vez por ciclo.
- **1.1.0**, atualização boa: passa no autoteste e o LED passa a piscar 2 vezes.
- **1.2.0**, quebrada de propósito (`OTA_FLEET_SIMULATE_BROKEN_BUILD`): trava antes do autoteste, o bootloader volta para a 1.1.0 e ela avisa `ROLLED_BACK`. O servidor nunca mais oferece a 1.2.0 para esse aparelho.

## Testes

```bash
cd server && mvn test     # regras do core, integração com MockMvc + H2 e interoperabilidade com a CLI
cd cli && npm test
cd sim && npm test
```

## Status

Servidor, CLI e simulador completos e testados, inclusive com `docker compose` (Postgres). O firmware compila com ESP-IDF v5.5 e os binários gerados passam pelo servidor e pelo simulador; falta validar na placa.
