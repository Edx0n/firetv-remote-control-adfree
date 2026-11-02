# Como o celular controla o Fire TV — engenharia reversa

Fontes analisadas:

1. **Um Fire TV de verdade na rede** (Fire TV Stick 3ª geração, modelo `AFTSSS`): portas,
   respostas SSDP/DIAL, cadeia de certificados e respostas da API.
2. **O app oficial "Amazon Fire TV"** (`com.amazon.storm.lightning.client.aosp` 4.9.1, baixado do
   APKMirror; assinatura conferida: *CN=Amazon Services LLC*, SHA-256 `2f19adeb…6d7625`). É React
   Native: a lógica está no bundle **Hermes** (`assets/index.android.bundle`, bytecode v96),
   descompilado com `hermes-dec`, e as requisições saem por um módulo nativo
   (`expo.modules.feniksnetworking`, OkHttp). Referência completa, com linhas do código
   descompilado: [spec-app-oficial.md](spec-app-oficial.md).
3. **Um app de terceiros com anúncios** ("Remoto para Fire TV & FireStick", BoostVision,
   `tv.remote.control.firetv` 1.7.4), descompilado com `jadx`.

## Em uma frase

O Fire TV roda um servidor **HTTPS na porta 8080** (a Amazon chama de *Turnstile*). O celular
manda coisas como `POST /v1/FireTV?action=dpad_up` com uma chave fixa (`x-api-key: 0987654321`)
e um token obtido digitando um **PIN** que a TV mostra. Os apps "Fire TV Remote" da Play Store
são uma tela por cima dessa mesma API — com anúncios no meio.

## O que o Fire TV expõe na rede

| Porta | Serviço | Observação |
|---|---|---|
| 8080/tcp | API de controle (HTTPS) | Só fica aberta com o serviço ativo; ociosa, recusa conexão |
| 8009/tcp | DIAL | `GET /apps/FireTVRemote` → XML com `<state>`; `POST` (re)inicia a API em ~0,5 s |
| 60000/tcp | descritor UPnP (`/dd.xml`) | nome, `manufacturer=Amazon`, `modelName`, `UDN`; cabeçalho `Application-URL` |
| 9090/tcp | WebSocket de voz (Alexa) | aberto só durante a busca por voz |
| 9080/tcp, 41128/tcp | Netflix (MDX) e Spotify Connect | não usados |
| 5555/tcp | ADB | fechada por padrão ("Depuração ADB" desligada) — a API não depende de ADB |

Visto ao vivo: depois de um tempo sem uso a porta 8080 passou a recusar conexões; um único
`POST http://<ip>:8009/apps/FireTVRemote` a reabriu em cerca de 500 ms.

### Certificado

A TV envia a cadeia completa: `O=Aralink` ← `Turnstile Server Subordinate - 5` ← `Turnstile Server`
(CA raiz da Amazon Lab 126, EC P-256, válida até 2040). O app oficial confia **só** nessa raiz
(embutida no APK) e ignora o nome do host. Este app faz igual — validado contra um Fire TV
real (`openssl verify` → OK, e o teste `LiveTvTest` com o próprio cliente Kotlin).

## Descoberta

1. M-SEARCH SSDP para `239.255.255.250:1900`, `ST: urn:dial-multiscreen-org:service:dial:1`,
   `MX: 1`, repetido a cada 1 s (o oficial procura por 10 s).
2. A resposta traz `LOCATION: http://<ip>:60000/dd.xml`; baixar dá o nome e o modelo, e o
   cabeçalho `Application-URL` (`http://<ip>:8009/apps/`) é a base do DIAL.
3. É Fire TV se `GET <Application-URL>FireTVRemote` responder abaixo de 400.
4. Alguns modelos mandam `WAKEUP: MAC=…` no SSDP e o oficial dispara Wake-on-LAN; o Fire TV
   Stick testado não manda, então este app usa só o DIAL para acordar.

## Cabeçalhos (todas as requisições)

```
x-api-key: 0987654321                 fixo, igual no app oficial e no de terceiros
x-client-token: <token>               depois do pareamento
x-amzn-request-id: <UUID novo>
Content-Type: application/json; charset=utf-8   (inclusive em GET; POST "vazio" vai com corpo de 0 bytes)
```

## Pareamento

1. `POST :8009/apps/FireTVRemote` (DIAL), espera 500 ms.
2. `POST /v1/FireTV/pin/display` `{"friendlyName":"<nome do celular>"}` → a TV mostra um PIN de **4 dígitos**.
3. `POST /v1/FireTV/pin/verify` `{"pin":"1234"}` → `{"description":"<token>"}`.
   `description` **vazia** = PIN errado (a TV descarta esse PIN; o app pede outro).
   HTTP 429 = tentativas demais.
4. Desistiu? `POST /v1/FireTV/pin/verify` `{"pin":"-1"}` tira o PIN da tela.
5. Qualquer 403 depois disso = token revogado → parear de novo.

## Conectar a uma TV já pareada

DIAL → `GET /v1/FireTV/status` → `GET /v1/FireTV2` (ou `/v1/FireTV` em TVs antigas).
O status traz `turnstileVersion`; se vier vazio (ou HTTP 405) a TV é "v1". As informações do
aparelho dizem se o volume funciona (`isVolumeControlsSupported`) e quantos controles podem
"tocar" (`ringableRemoteCount`).

**Recuperação automática:** se uma chamada falha sem resposta (ou com erro fora de
403/404/405/409/429), o oficial faz DIAL (com `x-api-key`), espera 200 ms e consulta o status.

## Teclas — `POST /v1/FireTV?action=<ação>`

Ações: `dpad_up`, `dpad_down`, `dpad_left`, `dpad_right`, `select`, `back`, `home`, `menu`,
`volume_up`, `volume_down`, `mute`, `backspace`, `sleep`, `power`, `wakeup`, `epg`.

Corpo: nenhum = clique; `{"keyActionType":"keyDown"}` / `{"keyActionType":"keyUp"}` = segurar e soltar.

Como o app oficial usa (e este app também):

| Botão | Envio |
|---|---|
| Direcional e OK | `keyDown` ao encostar o dedo, `keyUp` ao soltar — segurando, **a própria TV repete** |
| Toque no touchpad | `keyDown` e, 200 ms depois, `keyUp` |
| Volume, Início, Play, Retroceder/Avançar | toque curto = um clique ao soltar; após 300 ms vira `keyDown`…`keyUp` |
| Início | antes de `home` manda `wakeup` (acorda o aparelho) |
| Voltar, Menu, Mudo, Dormir, Power | um clique |

Tudo passa por **uma fila só**, na ordem: `keyDown`/`keyUp` nunca chegam trocados.

## Mídia — `POST /v1/media?action=…`

| Gesto | Requisição |
|---|---|
| Play/Pausa | `?action=play` (alterna; não existe `pause`) |
| Avançar 10 s | `?action=scan` `{"direction":"forward","durationInSeconds":"10","speed":"1"}` |
| Retroceder 10 s | igual, com `"direction":"back"` |
| Segurar | os mesmos + `"keyAction":{"keyActionType":"keyDown"}` (e depois `keyUp`) |

Atenção: os números vão **como texto** e a direção é `back` — não `backward`, como dizem
algumas bibliotecas abertas.

## Teclado

1. Ao abrir o teclado: `GET /v1/FireTV/keyboard` → `{"state": …}`.
2. `visible` ou `text`: a cada mudança, `POST /v1/FireTV/keyboard` `{"text":"<texto inteiro>"}`
   (a TV espelha o campo).
3. Outro estado (TVs antigas): uma letra por vez em `POST /v1/FireTV/text` `{"text":"a"}` e
   `?action=backspace` para apagar.

## Apps

- `GET /v1/FireTV/appsV2` (TV "v2") ou `/v1/FireTV/apps`: lista com `appId`, `name`,
  `tvIconArt` / `iconArtSmallUri`, `isShortcutApp`, `appShortcutLaunchIntent`. O oficial pede o
  ícone em 210 px inserindo `._SX210_` antes da extensão.
- Abrir: `POST /v1/FireTV/app/<appId>`; atalhos: `POST /v1/FireTV/shortcut/<intent codificada>`.
- Configurações do Fire TV: `POST /v1/FireTV/app/settings`.

## Voz (Alexa)

`POST /v1/FireTV/voiceCommand?action=start` → WebSocket `wss://<ip>:9090/` com os mesmos
cabeçalhos → áudio do microfone em PCM cru (16 kHz, mono, 16 bits) em frames binários →
fechar o WebSocket (código 1000) → `POST …?action=stop`.

## Outros

- `POST /v1/FireTV/ringRemotes` — faz tocar o controle físico (só controles com localizador,
  como o Alexa Voice Remote Pro; o oficial só mostra a opção se `ringableRemoteCount > 0`).
- `POST /v1/FireTV/clearTokens` — despareia **todos** os apps-controle (inclusive o com
  anúncios). Não está no app; dá para chamar com `tools/firetv.py raw POST /v1/FireTV/clearTokens`
  e depois parear este de novo.
- `POST /v1/FireTV/intent` `{"postData":"<base64 de um JSON>"}` — intents internas da Amazon.

## E o app com anúncios?

O app de terceiros analisado (BoostVision 1.7.4) usa exatamente estas rotas (`/v1/FireTV/pin/display`,
`/v1/FireTV?action=…`, `/v1/media?action=scan`, `/v1/FireTV/text`, `/v1/FireTV/apps`,
`/v1/FireTV/app/<id>`), com a mesma chave `0987654321` guardada no recurso `client_key`, o SDK de
descoberta da própria Amazon (Whisperlink) e um cliente ADB alternativo. Por cima disso vêm
**sete SDKs de anúncio/rastreamento**: Google AdMob (intersticial, "app open" e premiado),
AppLovin, Mintegral, Vungle/Liftoff, ByteDance/Pangle, InMobi e Firebase Analytics. Os
anúncios são chamados pela interface do app, não fazem parte do protocolo: sem eles, cada
toque vira uma requisição HTTPS de ~50 ms na rede local.
