# Plano de desenvolvimento — AI Civilization

Use este arquivo como referência de andamento. Marque cada item como `FEITO`, `TESTADO` ou `PENDENTE`. Ao testar dentro do Minecraft, anote versão, comando/ação e resultado observado.

## MVP

Um cidadão autônomo consegue perceber uma árvore, navegar até ela, coletar madeira com inventário próprio e guardar os itens em um baú.

## Referência: projeto antigo `minecraft-bots`

O repositório antigo é um bot Mineflayer para Minecraft 1.20.6, conectado ao servidor como cliente. A profissão `woodcutter` já separa etapas (`CHECK_STATUS`, ferramenta, busca da árvore, deslocamento, corte, replantio e depósito), guarda alvo/espécie no contexto e tem fallback quando não encontra machado. Essa organização é a principal ideia a portar para o mod.

### Conceitos que vamos reaproveitar

- [x] Identidade de companion com modelo/equipamento próprios, inspirada no padrão do AI-Buddies, sem transformar a entidade em um jogador real.
- [x] Extrair a profissão atual de `CitizenEntity.tick()` para uma tarefa com estados explícitos, alvo e transições controladas.
- [ ] Escolher ferramentas pelo bloco-alvo, medir progresso de quebra e interromper/retomar quando o alvo deixar de ser válido.
- [x] Tratar árvore como conjunto conectado de troncos, em vez de cortar somente uma coluna vertical.
- [ ] Usar quota/inventário cheio como gatilho futuro para depósito no baú.
- [ ] Manter recuperação de caminho isolada da lógica de profissão; evitar apagar listeners globais ao instalar anti-stuck.

### Diferenças importantes

- O projeto antigo automatiza um cliente Mineflayer; este mod controla uma entidade Minecraft dentro do servidor integrado/dedicado. Código de `bot.pathfinder`, `bot.dig()` e `bot.equip()` não pode ser portado diretamente.
- O Mineflayer pode esperar Promises; a entidade Fabric executa no ciclo de ticks e precisa manter estado entre os ticks.
- O `woodcutter` antigo corta troncos em sequência vertical, não resolve toda a copa conectada, e depende de locais de estoque. Torre, coleta de terra e fuga de buracos são novas necessidades da versão de NPC.
- O `MovementManager` antigo chama `removeAllListeners("physicsTick")` ao preparar o anti-stuck; isso pode remover listeners do cérebro e de outros módulos. Não repetir esse padrão no mod.
- O README do projeto antigo não descreve toda a implementação atual: a árvore de fontes também contém profissões, ferramentas, mineração e logística.
- O TreeChop Fabric 0.19.3 enviado declara `minecraft: ~1.21` no `fabric.mod.json`, mas a versão publicada aparece como 1.21.1 e requer Forge Config API Port; o carregamento/API no Minecraft 1.21.10 ainda não foi testado. A profissão atual usa uma adaptação nativa e não depende desse JAR.

## Estado atual

### Implementado

- [x] Projeto Fabric para Minecraft Java 1.21.10, com entidade `ai_civilization:citizen`.
- [x] Comando `/aiciv npc` para invocar um cidadão.
- [x] Comando `/aiciv help` com a lista de comandos atuais.
- [x] Navegação inicial: `/aiciv npc seguir` manda o cidadão próximo acompanhar o jogador; `/aiciv npc parar` interrompe.
- [x] Compatibilidade das chamadas de API com as dependências resolvidas pelo Gradle.
- [x] `.\gradlew.bat compileJava` passou após a implementação dos comandos de seguimento.
- [x] Primeira versão da profissão lenhador: busca de troncos, machado e recuperação de caminho com degraus temporários. A nova derrubada em conjunto substitui a subida em torre para cortar árvores altas.
- [x] Comandos organizados em inglês: `/aiciv list`, `/aiciv list npc`, `/aiciv list job` e `/aiciv npc <name|slot> <job>`.
- [x] NPCs recebem nomes brasileiros e display `Nome - [Citizen]` / `Nome - [LumberJack]` acima da cabeça.
- [x] Falas no chat para início de seguimento/trabalho, coleta, deslocamento, teleporte e abertura do inventário.
- [x] Iniciar o trabalho cancela o seguimento e sua navegação/teleporte.
- [x] Clique direito abre um baú de 27 slots com o inventário do NPC.
- [x] Renderer do cidadão usa uma textura própria do mod em vez da textura de aldeão padrão.
- [x] Cada NPC invocado funda uma nação própria e fica ligado permanentemente ao UUID dela; vínculo, nome e inventário do cidadão são serializados.
- [x] Dados de nações e localizações são salvos no Overworld e compartilhados entre dimensões.
- [x] Sistema de localizações nomeadas: salvar posição atual, listar e remover locais; cidadãos podem receber uma ordem de viagem na mesma dimensão.
- [x] `/aiciv list` agora inclui `nation` e `location`; `/aiciv help` documenta os comandos novos.
- [x] `/aiciv create civilization <name>` cria LumberJack, Storager, Miner e Builder na mesma civilização.
- [x] `/aiciv delete civilization <name>` remove os cidadãos e os dados da nação; estruturas já construídas ficam no mundo.
- [x] Territórios de nação circulares com raio de 128 blocos; um cidadão invocado dentro entra na nação mais próxima e não atravessa a fronteira de outra.
- [x] Nações novas recebem nomes de países e um estandarte colorido no centro; `/aiciv list nation` mostra centro, fundador, dimensão e cidadãos.
- [x] Lenhador agora mira o tronco/folha com o controle de olhar durante o corte, seguindo a mecânica observada no `CompanionMineGoal` do AI Buddies.
- [x] Falas do lenhador reduzidas a início, árvore encontrada, machado fabricado, cada 16 troncos, bloqueio relevante e fim/falha do ciclo.
- [x] Lenhador patrulha waypoints espaçados pela área carregada da nação, atualizando a rota enquanto avança, em vez de parar quando não acha troncos perto do ponto atual.
- [x] Folhas podem soltar mudas; o lenhador recolhe mudas próximas e replantará no local do tronco quando a coluna estiver livre.
- [x] Padrão visual do chat: prefixo colorido para o mod, verde para sucesso, vermelho para erro e falas dos cidadãos com nome dourado e conteúdo branco.
- [x] Display do cidadão é reconstruído ao trocar a profissão e segue `Nome - [Profissão] - Nacionalidade` (por exemplo, `Gabriel - [LumberJack] - Brasileiro`).
- [x] Profissão Storager: define um armazém por nação, constrói baú de entrada e baús de saída, separa cada tipo de item, aceita packs de 64 troncos e atende pedidos do melhor machado disponível.
- [x] Comandos de storage: `/aiciv warehouse set <radius>`, `/aiciv warehouse status`, `/aiciv list warehouse` e `/aiciv storage queue`; área padrão de 12 blocos, configurável entre 4 e 32.
- [x] Construção do armazém aceita tábuas ou troncos no inventário do Storager, pode reaproveitar recursos da entrada e retira 2 troncos de um cidadão próximo da mesma nação para inicializar o primeiro baú.
- [x] Limite de um Storager por nação; ao receber a profissão, ele escolhe uma área de armazém fora da zona central de 20x20 do estandarte.
- [x] Após criar baús e ficar sem pedidos, o Storager pavimenta gradualmente o piso com tábuas e constrói uma cerca ao redor usando materiais disponíveis.
- [x] Profissão Miner: escolhe um local dentro da nação, escava uma galeria descendente com 7 blocos de largura, evita lava e tenta cobrir fontes de água.
- [x] Miner solicita uma picareta inicial ao Storager; a fabricação prioriza diamante, ferro e pedra, usando madeira como último recurso quando não há material para tier superior.
- [x] Miner respeita o nível de ferramenta exigido pelos minérios, não tenta quebrá-los com picareta de madeira e pede uma ferramenta compatível ao Storager.
- [x] Após quebrar um veio, Miner retorna ao armazém para entregar os drops de minério; também entrega packs de 64 pedregulhos.
- [x] Profissões e progresso da mina são salvos para que os NPCs retomem o trabalho após reabrir o mundo.
- [x] Cidadãos recebem `MOVEMENT_SPEED` para que navegação de trabalho e deslocamento funcionem; ao carregar, mantêm a profissão e anunciam uma única retomada.
- [x] Posição segura dentro da nação é persistida; se o cidadão carregar fora da fronteira, ele retorna para dentro sem perder a profissão.
- [x] Miner caminha até o local da mina mesmo enquanto aguarda a picareta inicial; a mensagem de ferramenta ausente não diz mais que ela quebrou.
- [x] Storager pode buscar troncos de cidadãos carregados em qualquer ponto da mesma nação para iniciar o primeiro baú.
- [x] Mina abre uma passagem de 7 blocos de largura e 2 de altura por degrau, preserva o piso inferior e avança um bloco para frente e um para baixo.
- [x] Miner prepara cada descida como uma escada: limpa o bloco à frente, o espaço da cabeça e o piso inferior antes de navegar para o próximo nível, seguindo o padrão observado no `CompanionMineGoal` do AI Buddies.
- [x] AI Buddies foi inspecionado localmente; Millénaire também foi inspecionado para referências de `VillagerNavDriver`, `WaypointNavigator`, `MiningHandler`, `ChoppingHandler` e diagnóstico de caminhos. TreeChop permanece referência apenas para a queda/coleta de árvores.
- [x] Watchdog de navegação: se um cidadão com profissão tiver um destino de trabalho e permanecer sem mudar de posição por 200 ticks, ele é levado ao estandarte da cidade, a navegação é reiniciada e o job continua.
- [x] Lenhador tenta desobstruir a rota e, se continuar sem progresso, abandona temporariamente aquela árvore e procura outra.
- [x] Busca/aproximação do Lumberjack adapta padrões do `CompanionMineGoal` do AI Buddies: valida o alvo, limita varreduras, testa rotas alcançáveis e evita árvores bloqueadas por um período. A rotina existente de derrubada/queda dos troncos foi mantida.
- [x] Storager busca troncos adicionais do Lumberjack quando faltam tábuas para completar a receita de ferramentas e gravetos; pedidos de ferramentas seguem com prioridade.
- [x] Lenhador entrega ao Storager packs de 64 troncos e todos os gravetos, maçãs e mudas coletados; anuncia as quantidades da carga.
- [x] Sem árvores próximas, lenhador prioriza plantar mudas em locais válidos; se estiver sem mudas, tenta retirar uma do armazém antes de patrulhar.
- [x] Lenhador procura madeira nas extremidades nomeadas sul, norte, leste e oeste da nação, alternando a fronteira quando a busca local falha.
- [x] Reflorestamento usa até 64 mudas por retirada do armazém, planta em parcelas espaçadas e exclui 20 blocos ao redor do centro e a área do warehouse.
- [x] Fabricação do machado prioriza diamante, ferro, pedra e madeira, consome os materiais correspondentes e usa 3 materiais + 2 gravetos; troncos de espécies diferentes podem virar as tábuas da receita.
- [x] Lenhador derruba em conjunto os troncos conectados, cria drops com movimento de queda, quebra folhas próximas com loot vanilla, recolhe troncos/mudas/gravetos/maçãs e planta uma muda na base quando disponível.
- [x] Mudas coletáveis são reconhecidas pela tag vanilla, incluindo espécies novas ou compatíveis que adotem essa tag.
- [x] Inventário cheio interrompe o trabalho do lenhador após preservar os itens restantes no mundo.
- [x] Blueprints em grade JSON carregados pelo mod: armazém 10x10 com a distribuição de baús definida e praça central 7x5 com estandarte no centro.
- [x] O Builder constrói gradualmente a praça 7x5 de tábuas em volta do estandarte; solicita 34 tábuas ao Storager e preserva células que não sejam terreno natural livre.
- [x] Storager constrói baús nas células `A` do blueprint do armazém após o Builder preparar o terreno 10x10; as células `x` ficam como corredores/espaços vazios.
- [x] O painel de pedidos do Storager abre pela tecla `K`, recebe a fila da nação pelo servidor e permite atualizar a lista; `/aiciv storage queue` continua disponível.
- [x] Pedidos de ferramenta ficam abertos até o Storager levar o item ao cidadão; enquanto isso o cidadão aguarda e não retira a mesma ferramenta sozinho do baú.
- [x] A busca de baús reconhece somente as células do blueprint, o baú de entrada registrado e posições do layout antigo; baús aleatórios próximos não são adotados como warehouse.
- [x] Antes de o Builder concluir o terreno, pedidos de ferramentas podem ser processados com recursos carregados pelo Storager; baús próximos não bloqueiam esse ciclo.

### Testes dentro do Minecraft

- [x] Invocar um cidadão com `/aiciv npc` — resultado informado: o NPC apareceu.
- [ ] Abrir `/aiciv help` e confirmar que os comandos aparecem corretamente.
- [ ] Usar `/aiciv npc seguir`; afastar-se e confirmar que o cidadão acompanha o jogador.
- [ ] Usar `/aiciv npc parar` e confirmar que ele deixa de seguir.
- [ ] Repetir os testes após salvar e reabrir o mundo; registrar o que persiste.
- [ ] Conferir os nomes numerados visíveis acima da cabeça dos cidadãos.
- [ ] Conferir `/aiciv list`, `/aiciv list npc` e `/aiciv list job`; confirmar que os slots apontam para o NPC correto.
- [ ] Atribuir e encerrar a profissão com `/aiciv npc <name|slot> lumberjack` e `/aiciv npc <name|slot> stop`.
- [ ] Confirmar que o display mostra `Nome - [LumberJack]` ao trabalhar e volta a `Nome - [Citizen]` ao parar.
- [ ] Confirmar que iniciar o job enquanto segue cancela o seguimento e não ocorre teleporte para o jogador.
- [ ] Clicar com botão direito e verificar abertura, retirada e depósito de itens no inventário do NPC.
- [ ] Verificar se a textura própria renderiza corretamente no jogo e ajustar o atlas se necessário.
- [ ] Invocar dois cidadãos e confirmar que cada um funda uma nação separada; consultar `/aiciv list nation`.
- [ ] Usar `/aiciv create civilization Teste` e confirmar quatro NPCs: LumberJack, Storager, Miner e Builder.
- [ ] Usar `/aiciv delete civilization Teste` e confirmar remoção dos quatro NPCs e dos dados da nação, mantendo as estruturas existentes.
- [ ] Invocar outro cidadão dentro dos 128 blocos e confirmar que ele entra na nação existente; invocar fora e confirmar novo país/estandarte.
- [ ] Mandar cidadão seguir/juntar madeira até a borda e confirmar que ele não cruza para território vizinho.
- [ ] Confirmar que o estandarte aparece no centro e continua depois de salvar/reabrir o mundo.
- [ ] Criar uma civilização e confirmar que só o estandarte aparece inicialmente; depois acompanhar o Builder construindo gradualmente a praça 7x5 com materiais entregues pelo Storager.
- [ ] Salvar/reabrir mundo e confirmar persistência dos IDs de nação, inventário e locais.
- [ ] Marcar local com `/aiciv location set base`, listar, mandar NPC com `/aiciv npc <nome|slot> go base` e confirmar chegada.
- [ ] Testar viagem com obstáculo e dimensão diferente; avaliar as limitações da navegação.
- [ ] Testar o lenhador em árvore alta e confirmar que olha para cima, quebra folhas que bloqueiam e não repete falas a cada ação.
- [ ] Testar árvores de formatos diferentes e confirmar que troncos conectados caem como drops, folhas geram loot e o cidadão recolhe os recursos.
- [ ] Testar árvore com mais de 256 troncos conectados; confirmar que o limite evita deixar partes flutuando ou ajustar a estratégia.
- [ ] Confirmar replantio da muda no local da base depois que os drops assentarem.
- [ ] Confirmar que o lenhador caminha pela nação quando não há árvores próximas, recolhe uma muda derrubada por folhas e a replanta na base após cortar.
- [ ] Atribuir/remover lenhador e verificar que o display atualiza profissão e nacionalidade sem reinvocar o NPC.
- [ ] Verificar as cores do chat: prefixo de comando, confirmações, erros e falas do cidadão.
- [ ] Salvar e reabrir o mundo para confirmar persistência do inventário do cidadão.
- [ ] Testar `/aiciv npc <name|slot> lumberjack` em árvore baixa e alta; confirmar que não precisa subir em torre para cortar os troncos conectados.
- [ ] Usar `/aiciv npc <name|slot> stop` durante o trabalho e confirmar que ele para sem remover ou duplicar drops.
- [ ] Definir área com `/aiciv warehouse set 12`, atribuir `/aiciv npc <name|slot> storage` e consultar `/aiciv warehouse status`.
- [ ] Com o Storager e um lenhador próximos, confirmar que o Storager separa 2 troncos do inventário do lenhador, constrói o baú de entrada e o lenhador volta a completar/entregar 64.
- [ ] Atribuir um lenhador e consultar `/aiciv storage queue`; confirmar o pedido de machado quando não houver ferramenta disponível.
- [ ] Entregar um pack de 64 troncos e conferir chegada ao baú de entrada, criação de baús de saída e separação por recurso.
- [ ] Confirmar que o Storager posiciona os baús na grade 10x10 do blueprint, mantém os corredores vazios e cria os pares adjacentes previstos no desenho.
- [ ] Pressionar `K` dentro do mundo e confirmar que o painel lista pedidos pendentes por cidadão, permite rolar/atualizar e fecha normalmente.
- [ ] Confirmar que o Storager fabrica e disponibiliza o machado; consultar a fila e o status antes e depois do atendimento.
- [ ] Pedir uma ferramenta como Miner ou LumberJack e confirmar que o Storager sai até o solicitante, entrega o item e só então o pedido some da fila.
- [ ] Sem machado e com o Storager ativo, confirmar pedido, coleta de troncos quando faltarem tábuas, fabricação e retirada do machado pelo Lumberjack.
- [ ] Testar baú cheio, falta de material para baú, ausência de machado e inventário do lenhador cheio.
- [ ] Salvar e reabrir o mundo com cidadãos trabalhando; confirmar que Miner e Storager retomam suas rotinas.
- [ ] Confirmar que os três tipos de profissão caminham após a correção do atributo de movimento e retomam após fechar/reabrir o mundo.
- [ ] Atribuir dois cidadãos como Storager na mesma nação; confirmar que o segundo recebe erro e continua cidadão.
- [ ] Atribuir Storager sem warehouse configurado; confirmar que ele escolhe local fora da zona central 20x20.
- [ ] Após existirem pelo menos três baús e materiais excedentes, confirmar pavimentação gradual e cerca do armazém.
- [ ] Atribuir `/aiciv npc <name|slot> miner`; confirmar solicitação da picareta inicial ao Storager.
- [ ] Com madeira disponível e sem material para picareta de pedra, confirmar que o Storager fabrica a de madeira para blocos comuns; Miner não usa essa ferramenta em minérios que exigem tier superior.
- [ ] Colocar/minerar um veio de minério; confirmar que Miner coleta o veio e retorna para entregar todos os drops ao Storager.
- [ ] Deixar o Miner juntar 64 pedregulhos; confirmar que ele retorna ao warehouse e entrega o pack.
- [ ] Testar minérios de tiers diferentes: Miner deve esperar/buscar ferramenta suficiente sem avançar quebrando o minério com uma picareta fraca.
- [ ] Testar uma mina perto de lava e água; confirmar desvio/pausa diante de lava e cobertura das fontes de água.
- [ ] Confirmar que o Miner desce cada degrau mantendo o corredor de 7 blocos de largura.
- [ ] Bloquear propositalmente a rota de um cidadão e confirmar teleporte automático ao estandarte após aproximadamente 10 segundos.
- [ ] Confirmar que o lenhador se recupera sem intervenção quando fica preso perto de uma árvore.
- [ ] Confirmar que entrega 1 pack de madeira, gravetos, maçãs e mudas com quantidades corretas na fala e no baú.
- [ ] Remover as árvores acessíveis da nação e confirmar plantio de mudas e coleta delas no armazém para novas áreas.
- [ ] Confirmar no chat/debug as buscas `sul`, `norte`, `leste` e `oeste` quando não houver madeira próxima.
- [ ] Colocar 30–64 mudas no armazém e confirmar retirada em lote e plantio espaçado fora do centro e do warehouse.

## Próximas etapas

### 1. Robustez do comportamento de seguimento

- [ ] Testar o seguimento em terreno plano, obstáculos, água e mudanças de altura.
- [ ] Corrigir falhas de navegação observadas.
- [ ] Definir se o NPC deve lembrar seu jogador após sair do alcance, descarregar ou recarregar o mundo.
- [ ] Garantir que `/aiciv npc seguir` e `parar` escolham o cidadão esperado quando houver mais de um.

### 2. Percepção de recursos

- [ ] Criar observação limitada dos blocos de madeira próximos.
- [ ] Escolher a árvore acessível mais próxima e manter o alvo enquanto a tarefa estiver válida.
- [ ] Detectar quando não há árvores alcançáveis e comunicar a falha sem repetir tentativas indefinidamente.

### 3. Validação no jogo: lenhador e storage

- [ ] Validar derrubada da árvore conectada, coleta de troncos/folhas e replantio.
- [ ] Validar a entrega de packs, criação e organização dos baús, pedidos de machado e `/aiciv storage queue`.
- [ ] Ajustar limites e recuperação a partir dos resultados observados.

### 4. Persistência e acabamento do MVP

- [ ] Salvar e restaurar inventário, alvo e estado necessário da tarefa.
- [ ] Testar salvar/reabrir mundo e descarregar/recarregar chunks.
- [ ] Atualizar `/aiciv help` à medida que novos comandos forem adicionados.
- [ ] Documentar os comandos e limitações confirmadas no jogo.

### 5. Backlog de Funcionalidades e Ideias (Detalhes em `docs/ideas.md`)

- [ ] **Visualização de Limites da Nação:** Exibição clara de bordas/fronteiras (partículas de contorno circular/raio da nação, feedback no chat/ActionBar ou marcos físicos).
- [ ] **Skins Dedicadas por Profissão:** Vincular as texturas em `textures/entity/` (`builder.png`, `farmer.png`, `explorer.png`, `fisherman.png`, `hunter.png`, `guard.png`, `lumberjack.png`, `miner.png`, `storager.png`) no `CitizenEntityRenderer`.
- [ ] **Mural de Necessidades de Recursos:** Placas ou quadro de avisos dinâmico no armazém/centro indicando o que a nação precisa no momento (com interação para o jogador entregar itens).
- [ ] **Novas Profissões:**
  - [ ] Construtor (`builder`): Reparos, construções baseadas em blueprints, cercamento e pavimentação.
  - [ ] Fazendeiro (`farmer`): Plantações e criação de animais.
  - [ ] Explorador (`explorer`): Mapeamento, busca de biomas e cavernas.
  - [ ] Pescador (`fisherman`): Pesca e abastecimento de peixes/alimentos aquáticos.
  - [ ] Caçador (`hunter`): Caça de animais para carne, couro e penas.
  - [ ] Guarda (`guard`): Patrulha e proteção armada contra monstros.


## Registro de testes

| Data | Minecraft/loader | Ação ou comando | Resultado observado | Próximo ajuste |
| --- | --- | --- | --- | --- |
| 2026-11-14 | 1.21.10 / Fabric Loader 0.18.0 | `/aiciv npc` | Jogador confirmou que o NPC apareceu. | Testar help e seguimento. |
| 2026-11-14 | 1.21.10 / Fabric Loader 0.18.0 | `compileJava` e `compileClientJava` após storage e nova mecânica do lenhador | As duas compilações passaram com Fabric Loom 1.19.4. Ainda não foi testado dentro do jogo. | Executar os testes de storage e derrubada listados acima. |
| 2026-11-14 | 1.21.10 / Fabric Loader 0.18.0 | `compileJava` e `compileClientJava` após profissão Miner, seleção de tiers de picareta, retorno por veio e pack de pedregulho | `BUILD SUCCESSFUL` com Fabric Loom 1.19.4. Ainda não foi testado dentro do jogo. | Executar os novos cenários no Minecraft. |
| 2026-11-14 | 1.21.10 / Fabric Loader 0.18.0 | `compileJava` e `compileClientJava` após corrigir atributo de movimento e retomada de profissões | `BUILD SUCCESSFUL` com Fabric Loom 1.19.4. Ainda falta confirmar movimento e retomada no jogo. | Entrar no mundo salvo e verificar caminhada do Storager, Miner e lenhador. |
| 2026-11-14 | 1.21.10 / Fabric Loader 0.18.0 | `remapJar` após diagnóstico de NPCs parados | `BUILD SUCCESSFUL`; JAR atualizado copiado para a pasta global `.minecraft/mods`. O log anterior confirmou que Miner retomou a profissão, mas aguardava picareta; a pasta do launcher estava sem o JAR do mod. | Reiniciar o TLauncher para carregar a nova build e observar Storager, Miner e lenhador. |
| 2026-11-14 | 1.21.10 / Fabric Loader 0.18.0 | `build` após refatoração modular (`ProfessionJob`, `LumberjackJob`), correção de bugs (loop da mina, comandos `go`/`nation`, watchdog teleporte, banner substituível, flood no chat) | `BUILD SUCCESSFUL` gerando `ai-civilization-0.1.0.jar` (copiado para `~/.minecraft/mods`). | Testar novos comandos e comportamento modular no mundo. |
| 2026-11-14 | 1.21.10 / Fabric Loader 0.18.0 | Blueprints JSON da warehouse 10x10, praça central 7x5 e painel de pedidos pela tecla `K` | `compileJava` e `compileClientJava` passaram; no teste de `runClient`, abrir o painel com `K` causou `Can only blur once per frame` em `StorageQueueScreen.render`. | Corrigir a renderização, transferir a construção da praça ao Builder e aguardar nova aprovação de teste no jogo. |

## Regras de trabalho

- Implementar uma etapa por vez e manter este plano atualizado.
- Não alterar versões de Minecraft, Fabric Loom, Fabric Loader ou dependências sem combinar isso no plano.
- Depois de cada implementação, registrar o que foi compilado e deixar os testes de jogo para serem marcados após confirmação do jogador.
- Comandos novos devem ser acrescentados a `/aiciv help` e documentados aqui.
