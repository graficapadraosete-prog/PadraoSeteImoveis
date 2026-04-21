# Padrao 7 Imoveis — App Android v2.0

App Android para o Portal Padrao 7 Imoveis — Gestao de Contratos de Locacao.

## Novidades v2.0
- Nome corrigido: **Padrao 7 Imoveis**
- Suporte a **modo escuro** automatico (segue o celular)
- Botao voltar mostra menu com opcao de **deslogar**
- Sem android:z (compativel com Android 5+)
- Sem layouts duplicados (layout-land, layout-sw600dp removidos)
- Repositorios centralizados no settings.gradle
- Package: `br.com.padrao7imoveis`

## Como gerar o APK (GitHub Actions — gratis)

1. Crie conta em **github.com**
2. Crie repositorio: **padrao7imoveis-app**
3. Faca upload de todos os arquivos deste ZIP
4. Va em **Actions** — o build roda automaticamente
5. Clique em **Build APK → Artifacts → Padrao7Imoveis-v2-debug**
6. Baixe e instale no celular

## Como instalar no celular
1. Habilite "Fontes desconhecidas" nas configuracoes do Android
2. Transfira o APK para o celular
3. Toque no arquivo para instalar

## Funcionalidades do app
- Abre o portal padraosete.com.br de forma nativa
- Login/logout com cookie persistente (nao precisa logar todo dia)
- Modo escuro automatico conforme sistema
- Pagina amigavel sem internet
- Dialogo de saida com opcao de deslogar
- Barra de progresso verde ao carregar

## Compatibilidade
- Android 5.0 (API 21) em diante
- Testado: Android 5, 8, 10, 12, 14

## Estrutura do projeto
```
PadraoSeteApp/
  app/src/main/
    java/br/com/padrao7imoveis/
      MainActivity.java
    res/
      layout/activity_main.xml      (unico layout)
      values/strings.xml
      values/styles.xml
      values-night/styles.xml       (modo escuro)
      xml/network_security_config.xml
      mipmap-*/ic_launcher.png
    AndroidManifest.xml
  .github/workflows/build.yml
  build.gradle
  settings.gradle
  gradlew
```
