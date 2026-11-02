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

## Status

Em desenvolvimento.
