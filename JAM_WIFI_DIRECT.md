# Jam Wi-Fi Direct — 0.17.0

Jam presencial, um anfitrião e um convidado, sem hospedagem ou internet. Usa WifiP2pManager e sockets locais; a permissão INTERNET é necessária para sockets, mesmo sem tráfego pela internet. Wi-Fi Direct deve ser suportado pelos dois celulares.

## Uso

1. Instale a mesma versão nos dois aparelhos. Ative Wi-Fi; no Android 12 ou anterior, ative também localização.
2. No primeiro celular: menu ☰ → Jam · Wi-Fi Direct → Criar sala.
3. No segundo: abra a mesma opção, digite o código do primeiro, toque em procurar e selecione o celular do anfitrião. Aceite a conexão Wi-Fi Direct quando o Android solicitar.
4. Escolha músicas no anfitrião. Cada arquivo original é enviado integralmente e verificado antes de tocar no convidado. Cada um mantém o próprio fone e os próprios efeitos/volume.
5. Encerre pela tela Jam ou pela notificação. A cópia temporária do convidado é removida; sua biblioteca não é modificada.

## Escopo e limites

- Um convidado por sala; arquivos até 512 MiB, sujeitos ao espaço livre do aparelho.
- O convite contém 12 caracteres aleatórios. Autenticação mútua HMAC, chave derivada PBKDF2-HMAC-SHA256 e frames AES-256-GCM com sequência validada. A aplicação não transmite o código pelo socket. Não compartilhe o código fora da dupla.
- O anfitrião toca enquanto o arquivo é enviado. O convidado entra na posição atual após receber, não na primeira batida. Nova faixa pode ter intervalo durante o envio. Não transmite DSP/crossfade/AutoMix sobreposto; envia o arquivo original para decodificação local pelo FFmpeg.
- Sincronismo aproximado: posição/pausa/velocidade a cada 750 ms, relógios monotônicos estimados por ping, correção de deriva superior a 900 ms no máximo a cada 5 s. Não há garantia de alinhamento de amostras ou compensação de latência entre diferentes fones Bluetooth.
- Sem sinal de estado por mais de 4 s, o convidado pausa. Falha de conexão exige encerrar e recriar a sala. Grupos antigos podem precisar ser desconectados nos ajustes de Wi-Fi Direct do Android.
- Serviço foreground do tipo connectedDevice, notificação de encerramento, wake lock com teto de 2 h. A reprodução mantém seu próprio serviço mediaPlayback.
- Desconectar o fone mantém a saída silenciada; frames remotos não removem essa trava. Somente o toque explícito em reproduzir autoriza saída novamente. Perda de foco de áudio pausa localmente.

## Verificação

Testes automatizados cobrem autenticação incorreta, transporte duplex com bytes e Unicode intactos, limite de frames, SHA-256, limite de arquivos, relógio/speed/pausa, trava de fone e abertura da Activity. Wi-Fi Direct e latências de Bluetooth precisam de teste físico com dois aparelhos; Robolectric e sockets locais não validam o rádio do celular.

Referência de permissões: https://developer.android.com/develop/connectivity/wifi/wifip2p
